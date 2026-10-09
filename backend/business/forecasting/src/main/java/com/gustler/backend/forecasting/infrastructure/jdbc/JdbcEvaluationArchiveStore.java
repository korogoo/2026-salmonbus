package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.application.evaluation.EvaluationArchiveStore;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Key;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.Row;
import com.gustler.backend.forecasting.domain.evaluation.EvaluationArchiveBatch.State;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcEvaluationArchiveStore implements EvaluationArchiveStore {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final JdbcClient jdbc;
    private final RouteDataQualityAccess quality;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public JdbcEvaluationArchiveStore(JdbcClient jdbc, RouteDataQualityAccess quality, Clock clock,
        PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.quality = quality;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setTimeout(2);
    }

    @Override
    public Optional<EvaluationArchiveBatch> reserve(long routeVersionId, List<Key> candidates) {
        if (routeVersionId <= 0 || candidates == null || candidates.isEmpty() || candidates.size() > 100
            || candidates.stream().anyMatch(key -> key == null)) {
            throw new IllegalArgumentException("노선과 한정된 정산 후보가 필요하다");
        }
        List<Key> keys = candidates.stream().distinct()
            .sorted(Comparator.comparingLong(Key::observationId).thenComparingInt(Key::stopOrder)).toList();
        return inTransaction(() -> {
            long revision = quality.lock(routeVersionId);
            if (quality.anyInvestigationPending(routeVersionId)) {
                return Optional.empty();
            }
            var before = clock.instant().atZone(SEOUL).toLocalDate().atStartOfDay(SEOUL)
                .toInstant().atOffset(ZoneOffset.UTC);
            List<Row> rows = new ArrayList<>();
            for (Key key : keys) {
                jdbc.sql("""
                    SELECT to_jsonb(e)::text AS original FROM forecast_evaluation_result e
                    WHERE e.vehicle_observation_id=:observation AND e.target_stop_order=:stop
                      AND e.route_version_id=:route AND e.scoring_state<>'PENDING'
                      AND e.scored_at<:before AND (e.arrived_at IS NULL OR e.arrived_at<:before)
                      AND NOT EXISTS(SELECT 1 FROM evaluation_archive_member m
                          WHERE m.vehicle_observation_id=e.vehicle_observation_id
                            AND m.target_stop_order=e.target_stop_order)
                    FOR UPDATE OF e
                    """).param("observation", key.observationId()).param("stop", key.stopOrder())
                    .param("route", routeVersionId).param("before", before).query(String.class).optional()
                    .ifPresent(json -> rows.add(new Row(key, json, digest(json))));
            }
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            UUID id = UUID.randomUUID();
            var batch = jdbc.sql("""
                INSERT INTO evaluation_archive_batch(id,route_version_id,quality_revision,lease_token,lease_until,row_count)
                VALUES(:id,:route,:revision,:token,clock_timestamp()+interval '5 minutes',:count)
                RETURNING *
                """).param("id", id).param("route", routeVersionId).param("revision", revision)
                .param("token", UUID.randomUUID()).param("count", rows.size())
                .query(JdbcEvaluationArchiveStore::batchOf).single();
            for (Row row : rows) {
                jdbc.sql("""
                    INSERT INTO evaluation_archive_member(vehicle_observation_id,target_stop_order,
                        route_version_id,batch_id,original_sha256) VALUES(?,?,?,?,?)
                    """).params(row.key().observationId(), row.key().stopOrder(), routeVersionId, id, row.sha256())
                    .update();
            }
            return Optional.of(batch);
        });
    }

    @Override
    public Optional<EvaluationArchiveBatch> reclaim(UUID batchId) {
        return inTransaction(() -> {
            long route = jdbc.sql("SELECT route_version_id FROM evaluation_archive_batch WHERE id=?")
                .param(batchId).query(Long.class).single();
            quality.lock(route);
            return jdbc.sql("""
                UPDATE evaluation_archive_batch SET lease_token=:token,
                    lease_until=clock_timestamp()+interval '5 minutes'
                WHERE id=:id AND lease_until<=clock_timestamp() RETURNING *
                """).param("id", batchId).param("token", UUID.randomUUID())
                .query(JdbcEvaluationArchiveStore::batchOf).optional();
        });
    }

    @Override
    public List<Row> readOwned(EvaluationArchiveBatch batch) {
        return inTransaction(() -> {
            assertOwned(batch);
            return readUnchanged(batch);
        });
    }

    @Override
    public void recordVerified(EvaluationArchiveBatch batch, String manifestSha256) {
        if (manifestSha256 == null || !manifestSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("파일 설명서의 검증값이 필요하다");
        }
        inTransaction(() -> {
            assertOwned(batch);
            readUnchanged(batch);
            int changed = jdbc.sql("""
                UPDATE evaluation_archive_batch SET state='VERIFIED',manifest_sha256=:digest,
                    verified_at=COALESCE(verified_at,clock_timestamp())
                WHERE id=:id AND lease_token=:token AND lease_until>clock_timestamp()
                  AND (manifest_sha256 IS NULL OR manifest_sha256=:digest)
                """).param("id", batch.id()).param("token", batch.leaseToken())
                .param("digest", manifestSha256).update();
            if (changed != 1) {
                throw new IllegalStateException("작업 소유권이 만료됐거나 이미 검증한 파일과 다르다");
            }
            return null;
        });
    }

    private void assertOwned(EvaluationArchiveBatch batch) {
        long revision = quality.lock(batch.routeVersionId());
        if (revision != batch.qualityRevision() || quality.anyInvestigationPending(batch.routeVersionId())) {
            throw new IllegalStateException("이관 예약 이후 품질 기준이 바뀌었다");
        }
        boolean owned = jdbc.sql("""
            SELECT EXISTS(SELECT 1 FROM evaluation_archive_batch WHERE id=:id AND route_version_id=:route
              AND quality_revision=:quality AND row_count=:count AND lease_token=:token
              AND lease_until>clock_timestamp())
            """).param("id", batch.id()).param("route", batch.routeVersionId())
            .param("quality", batch.qualityRevision()).param("count", batch.rowCount())
            .param("token", batch.leaseToken()).query(Boolean.class).single();
        if (!owned) {
            throw new IllegalStateException("현재 실행자가 소유한 이관 작업이 아니다");
        }
    }

    private List<Row> readUnchanged(EvaluationArchiveBatch batch) {
        List<Row> rows = jdbc.sql("""
            SELECT m.vehicle_observation_id,m.target_stop_order,m.original_sha256,to_jsonb(e)::text AS original
            FROM evaluation_archive_member m
            JOIN forecast_evaluation_result e ON e.vehicle_observation_id=m.vehicle_observation_id
              AND e.target_stop_order=m.target_stop_order AND e.route_version_id=m.route_version_id
            WHERE m.batch_id=:id ORDER BY m.vehicle_observation_id,m.target_stop_order
            FOR UPDATE OF e
            """).param("id", batch.id()).query((rs, n) -> new Row(
                new Key(rs.getLong("vehicle_observation_id"), rs.getInt("target_stop_order")),
                rs.getString("original"), rs.getString("original_sha256"))).list();
        if (rows.size() != batch.rowCount() || rows.stream().anyMatch(row -> !digest(row.originalJson()).equals(row.sha256()))) {
            throw new IllegalStateException("예약한 완료 정산의 원본이 바뀌었거나 없어졌다");
        }
        return rows;
    }

    private <T> T inTransaction(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("이관 DB 작업은 외부 트랜잭션 없이 호출해야 한다");
        }
        return transaction.execute(status -> {
            jdbc.sql("SET LOCAL statement_timeout='500ms'").update();
            jdbc.sql("SET LOCAL lock_timeout='100ms'").update();
            // 원본 JSON의 timestamptz 표현이 연결별 시간대에 따라 달라지지 않게 한다.
            jdbc.sql("SET LOCAL TIME ZONE 'UTC'").update();
            return work.get();
        });
    }

    private static EvaluationArchiveBatch batchOf(ResultSet rs, int row) throws SQLException {
        return new EvaluationArchiveBatch(rs.getObject("id", UUID.class), rs.getLong("route_version_id"),
            rs.getLong("quality_revision"), rs.getObject("lease_token", UUID.class),
            rs.getObject("lease_until", OffsetDateTime.class).toInstant(), State.valueOf(rs.getString("state")),
            rs.getInt("row_count"), rs.getString("manifest_sha256"));
    }

    private static String digest(String json) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
