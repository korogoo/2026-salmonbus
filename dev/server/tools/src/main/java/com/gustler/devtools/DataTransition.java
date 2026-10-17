package com.gustler.devtools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** SAL-175 전환은 운영자가 확인한 백업과 정확한 두 basis 배포판에만 승인한다. */
class DataTransition {
    static Map<String, Object> authorize(Layout layout, Host host, Path incoming, String backupReference)
            throws Exception {
        Checks.require(host.root(), "ROOT_REQUIRED");
        new Preflight(layout, host).check("prepare", Map.of());
        host.verifyInstance(host.identity());
        var app = RevisionStore.verify(incoming, true);
        var previous = RevisionStore.verify(RevisionStore.selectedBasis(layout), true);
        var next = RevisionStore.verify(incoming.resolve("basis"), true);
        Checks.require(app.kind().equals("api") && next.kind().equals("basis")
                        && app.commit().equals(next.commit()) && app.basisDigest().equals(next.basisDigest())
                        && !java.util.Objects.equals(previous.files().get("tools/data-contract.sha256"),
                                next.files().get("tools/data-contract.sha256")),
                "DEV_DATA_TRANSITION_APPROVAL");
        var now = Instant.now();
        var approval = new Approval(previous.releaseId(), next.releaseId(), backupReference, now.plusSeconds(1800));
        approval.verify(previous, next, now);
        var directory = layout.data().resolve("deployment-preparation");
        FileOps.directory(directory, 0700);
        FileOps.atomicFile(directory.resolve("approval.json"), Json.bytes(Map.of(
                "transition", "SAL-175", "fromReleaseId", approval.fromReleaseId(),
                "toReleaseId", approval.toReleaseId(), "backupReference", approval.backupReference(),
                "expiresAt", approval.expiresAt().toString())), 0600);
        return Map.of("status", "ok", "expiresAt", approval.expiresAt().toString(),
                "databaseChanged", false, "backupVerifiedByTool", false);
    }

    record Approval(String fromReleaseId, String toReleaseId, String backupReference, Instant expiresAt) {
        void verify(RevisionStore.Revision previous, RevisionStore.Revision next, Instant now) {
            Checks.require(fromReleaseId.equals(previous.releaseId()) && toReleaseId.equals(next.releaseId())
                    && !fromReleaseId.equals(toReleaseId()) && backupReference.matches("[a-zA-Z0-9:/_.-]{1,200}")
                    && expiresAt.isAfter(now) && !expiresAt.isAfter(now.plusSeconds(1800)),
                    "DEV_DATA_TRANSITION_APPROVAL");
        }
    }

    static Approval approval(Layout layout, RevisionStore.Revision previous, RevisionStore.Revision next)
            throws Exception {
        var path = layout.data().resolve("deployment-preparation/approval.json");
        Checks.require(FileOps.exists(path) && Files.size(path) <= 4096, "DEV_DATA_CHANGE_REQUIRES_PLAN");
        var value = Json.read(FileOps.privateText(path).getBytes());
        Checks.require(value.isObject() && value.propertyStream().map(Map.Entry::getKey)
                        .collect(java.util.stream.Collectors.toSet()).equals(Set.of(
                                "transition", "fromReleaseId", "toReleaseId", "backupReference", "expiresAt"))
                        && value.path("transition").asText().equals("SAL-175"),
                "DEV_DATA_TRANSITION_APPROVAL");
        var approval = new Approval(value.path("fromReleaseId").asText(), value.path("toReleaseId").asText(),
                value.path("backupReference").asText(), Instant.parse(value.path("expiresAt").asText()));
        approval.verify(previous, next, Instant.now());
        return approval;
    }

    void approveDatabase(Layout layout, Path basis, Approval approval) throws Exception {
        var settings = Environment.load(layout.config());
        var api = settings.get("api");
        Checks.require(api.get("DB_URL").equals(DatabaseTarget.url(layout.config()))
                        && api.get("DB_URL").equals(settings.get("worker").get("DB_URL")),
                "DEV_DATA_TRANSITION_DATABASE");
        var sql = basis.resolve("tools/prepare-evaluation-work-transition.sql");
        Checks.require(Files.isRegularFile(sql) && Files.size(sql) <= 16384, "DEV_DATA_TRANSITION_SQL");
        var properties = new Properties();
        properties.setProperty("user", api.get("DB_USERNAME"));
        properties.setProperty("password", api.get("DB_PASSWORD"));
        properties.setProperty("ApplicationName", "salmonbus-evaluation-transition");
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "15");
        try (var connection = DriverManager.getConnection(api.get("DB_URL"), properties)) {
            approve(connection, Files.readString(sql), approval.backupReference());
        } catch (java.sql.SQLException failure) {
            // DB 주소·인증정보·SQL 원문을 배포 로그로 보내지 않는다.
            throw new IllegalArgumentException("DEV_DATA_TRANSITION_DATABASE_PREPARATION_FAILED");
        }
    }

    static void approve(java.sql.Connection connection, String sql, String backupReference) throws Exception {
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(15);
            statement.execute("SET LOCAL search_path=public");
            try (var rows = statement.executeQuery("""
                    SELECT COALESCE(max(version::int),0) < 39
                      AND EXISTS(SELECT 1 FROM pg_class
                        WHERE oid=to_regclass('public.forecast_evaluation_pending') AND relkind='r')
                    FROM public.flyway_schema_history WHERE success
                    """)) {
                Checks.require(rows.next() && rows.getBoolean(1), "DEV_DATA_TRANSITION_SCHEMA");
            }
            try (var bind = connection.prepareStatement("SELECT set_config('salmonbus.backup_reference', ?, true)")) {
                bind.setString(1, backupReference);
                bind.execute();
            }
            // 같은 준비 SQL을 사용한다. worker/정비 연결 검사와 승인 기록 생성만 수행한다.
            statement.execute(sql);
            connection.commit();
        } catch (Exception failure) {
            connection.rollback();
            throw new IllegalArgumentException("DEV_DATA_TRANSITION_DATABASE_PREPARATION_FAILED");
        }
    }

    static void requireApplication(Layout layout, RevisionStore.Revision app) throws Exception {
        var journal = layout.data().resolve("deployment-preparation/current.json");
        if (!FileOps.exists(journal)) return;
        var state = Json.read(FileOps.privateText(journal).getBytes());
        if (!state.path("dataTransition").asBoolean() || Set.of("COMPLETE", "ROLLED_BACK").contains(state.path("phase").asText())) return;
        var allowed = app.kind().equals("api") ? Set.of("PREPARED", "API_READY") : Set.of("API_READY");
        Checks.require(app.commit().equals(state.path("commit").asText())
                        && allowed.contains(state.path("phase").asText()),
                "DEV_DATA_TRANSITION_RECOVERY_REQUIRED");
    }
}
