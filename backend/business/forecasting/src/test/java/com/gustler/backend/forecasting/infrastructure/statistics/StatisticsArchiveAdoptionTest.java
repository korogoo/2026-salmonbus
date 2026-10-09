package com.gustler.backend.forecasting.infrastructure.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.ArgumentMatchers.contains;

import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.time.Clock;
import java.lang.management.ManagementFactory;
import com.gustler.backend.forecasting.application.statistics.DemandStatisticsPipeline;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcStopDemandStatisticsRepository;
import com.gustler.backend.forecasting.domain.statistics.*;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluation;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluationRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.when;
import tools.jackson.databind.json.JsonMapper;
import java.util.HashMap;
import java.nio.file.Files;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@ForecastingIntegrationTest
@TestPropertySource(properties = "sal175.adoption-test=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StatisticsArchiveAdoptionTest {
    @Autowired DemandStatisticsPipeline pipeline;
    @Autowired JdbcStopDemandStatisticsRepository statistics;
    @Autowired ForecastEvaluationRepository evaluations;
    @Autowired RouteDataQualityAccess quality;
    @MockitoBean Clock clock;
    @MockitoSpyBean JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    private JdbcStatisticsInputExtractor extractor;
    private long route;
    private long source;
    @TempDir Path directory;
    private static final Instant UNTIL = Instant.parse("2026-10-09T00:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @BeforeEach
    void 완료된_정산이_있는_독립된_노선을_준비한다() {
        when(clock.instant()).thenReturn(UNTIL);
        when(clock.getZone()).thenReturn(ZONE);
        extractor = new JdbcStatisticsInputExtractor(jdbc, transactions);
        String key = UUID.randomUUID().toString().substring(0, 20);
        long routeId = jdbc.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES(:key,'GBIS',:key,'추출 시험','출발','도착') RETURNING id
            """).param("key", key).query(Long.class).single();
        route = jdbc.sql("""
            INSERT INTO route_version(route_id,content_digest,valid_from)
            VALUES(:route,:digest,'2026-10-01T00:00:00Z') RETURNING id
            """).param("route", routeId).param("digest", "0".repeat(64)).query(Long.class).single();
        jdbc.sql("INSERT INTO route_data_quality(route_id) VALUES(?)").param(routeId).update();
        jdbc.sql("""
            INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed)
            VALUES(:route,8,'stop-eight','출발','UP',true),
              (:route,9,'stop-nine','도착','UP',true),(:route,10,'stop-ten','다음','UP',true)
            """).param("route", route).update();
        long batch = jdbc.sql("""
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,
              requested_at,response_received_at,outcome,normalization_version,collection_strategy_version)
            VALUES(:route,'2026-10-08T23:00:00Z',1,:key,'2026-10-08T23:00:00Z','2026-10-08T23:00:00Z',
              'SUCCESS_ROWS','normalization-v1.0.0','adaptive-kst-v1.0.1') RETURNING id
            """).param("route", route).param("key", key).query(Long.class).single();
        source = jdbc.sql("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,
              vehicle_id,stop_order,stop_id,passed_stop_order,running_state,remaining_seats)
            VALUES(:batch,:route,0,'bus',8,'stop-eight',8,2,20) RETURNING id
            """).param("batch", batch).param("route", route).query(Long.class).single();
        long model = jdbc.sql("""
            INSERT INTO model_deployment(deployment_key,release_id,model_key,model_version,bundle_digest,
              prediction_target_version,calculation_version,supported_scope_digest,data_until,state)
            VALUES(:key,'extract-test','seat-full-chance','1',:digest,'SEAT_FULL_CHANCE_V1','CALCULATION_V1',
              :digest,'2026-10-01T00:00:00Z','STAGED') RETURNING id
            """).param("key", UUID.randomUUID()).param("digest", "0".repeat(64)).query(Long.class).single();
        long publication = jdbc.sql("""
            INSERT INTO forecast_publication(source_batch_id,route_version_id,model_deployment_id,
              demand_statistics_revision,quality_revision,observed_at,generated_at,published_at,prediction_count)
            VALUES(:batch,:route,:model,1,1,'2026-10-08T23:00:00Z','2026-10-08T23:00:00Z',
              '2026-10-08T23:00:00Z',2) RETURNING id
            """).param("batch", batch).param("route", route).param("model", model).query(Long.class).single();
        jdbc.sql("""
            INSERT INTO seat_forecast(vehicle_observation_id,target_stop_order,route_version_id,stops_to_target,
              model_deployment_id,demand_statistics_revision,seat_full_chance_raw,seat_full_chance,
              expected_seats,generated_at,quality_revision,publication_id)
            SELECT :source,target,:route,target-8,:model,1,0.5,0.5,10,'2026-10-08T23:00:00Z',1,:publication
            FROM generate_series(9,10) target
            """).param("source", source).param("route", route).param("model", model).param("publication", publication).update();
        jdbc.sql("""
            INSERT INTO forecast_evaluation_result(vehicle_observation_id,target_stop_order,route_version_id,
              scoring_state,scored_at)
            VALUES(:source,9,:route,'LOST','2026-10-08T23:30:00Z'),
              (:source,10,:route,'LOST','2026-10-08T23:30:00Z')
            """).param("source", source).param("route", route).update();
    }


    @Test
    void 실제_정기_집계와_파일_계산은_같은_입력에서_같은_통계를_만든다() {
        // given
        settle();

        // when
        int steps = finishPipeline();
        var file = calculate(export(), Map.of("bus", 20));
        var published = statistics.readAsOf(route, TimeSlot.MORNING,
            DemandStatisticsVersion.CURRENT_CALCULATION_VERSION, UNTIL);

        // then
        assertThat(published.cells()).containsExactly(file.getFirst().cell());
        assertThat(file.getFirst().cell().averageFillRate()).isEqualTo(0.75);
        assertThat(file.getFirst().cell().sampleCount()).isEqualTo(1);
        report("runtime-equivalence", Map.of("pipelineSteps", steps, "sampleCount", 1, "fillRate", 0.75));
    }

    @Test
    void 완료_이력만_삭제하면_기존_저장_경로는_같은_정산을_다시_대기로_만든다() {
        // given
        settle();
        var pending = List.of(ForecastEvaluation.pending(source, 9));
        transaction(() -> { quality.lock(route); evaluations.addPending(route, pending); });
        assertThat(pendingCount()).isZero();

        // when
        jdbc.sql("DELETE FROM forecast_evaluation_result WHERE vehicle_observation_id=? AND target_stop_order=9")
            .param(source).update();
        transaction(() -> { quality.lock(route); evaluations.addPending(route, pending); });

        // then
        assertThat(pendingCount()).isEqualTo(1);
        report("deletion-requeues-settlement", Map.of("beforePending", 0, "afterPending", 1,
            "adoptionGate", "FAIL"));
    }

    @Test
    void 정산에_직접_연결되지_않은_과거_관측도_삭제하면_정원과_통계가_바뀐다() {
        // given
        settle();
        long capacityObservation = observation(batch("2026-10-08T22:00:00Z"), "bus", 8, 40);
        var before = reference().getFirst().cell();
        var archived = export();
        var archivedResult = calculate(archived, Map.of("bus", 40));

        // when
        jdbc.sql("DELETE FROM vehicle_observation WHERE id=?").param(capacityObservation).update();
        var after = reference().getFirst().cell();

        // then
        assertThat(before.averageFillRate()).isEqualTo(0.875);
        assertThat(after.averageFillRate()).isEqualTo(0.75);
        assertThat(before.averageNetBoardingRate()).isEqualTo(0.375);
        assertThat(after.averageNetBoardingRate()).isEqualTo(0.75);
        assertThat(archivedResult.getFirst().cell()).isEqualTo(before);
        report("capacity-retention", Map.of("beforeFillRate", before.averageFillRate(),
            "afterFillRate", after.averageFillRate(), "beforeNetBoardingRate", before.averageNetBoardingRate(),
            "afterNetBoardingRate", after.averageNetBoardingRate(), "adoptionGate", "FAIL"));
    }

    @Test
    void 관측_번호만_진행시키면_뒤늦게_완료된_이전_관측의_정산을_놓친다() {
        // given
        jdbc.sql("UPDATE forecast_evaluation_result SET scored_at='2026-10-10T00:00:00Z' WHERE vehicle_observation_id=? AND target_stop_order=9")
            .param(source).update();
        var first = extractor.extract(route, source - 1, source, UNTIL, ZONE, 10);
        assertThat(first.rows()).extracting(StatisticsInputRow::targetStopOrder).containsExactly(10);

        // when
        var next = extractor.extract(route, source, source + 1, UNTIL.plusSeconds(172800), ZONE, 10);
        var revisited = extractor.extract(route, source - 1, source, UNTIL.plusSeconds(172800), ZONE, 10);

        // then
        assertThat(next.rows()).isEmpty();
        assertThat(revisited.rows()).hasSize(2);
        report("late-settlement-cursor", Map.of("firstRows", 1, "newerIdRows", 0, "revisitedRows", 2,
            "adoptionGate", "FAIL"));
    }

    @Test
    void 완료_이력을_지운_뒤에도_파일_계산은_같고_원본을_복원하면_DB_계산도_돌아온다() {
        // given
        settle();
        var before = reference();
        var archived = export();
        String raw = jdbc.sql("SELECT jsonb_agg(to_jsonb(e))::text FROM forecast_evaluation_result e WHERE vehicle_observation_id=?")
            .param(source).query(String.class).single();

        // when
        int removed = jdbc.sql("DELETE FROM forecast_evaluation_result WHERE vehicle_observation_id=?").param(source).update();
        var missing = reference();
        var file = calculate(archived, Map.of("bus", 20));
        jdbc.sql("INSERT INTO forecast_evaluation_result SELECT * FROM jsonb_populate_recordset(NULL::forecast_evaluation_result, CAST(? AS jsonb))")
            .param(raw).update();
        var restored = reference();

        // then
        assertThat(removed).isEqualTo(2);
        assertThat(missing).isEmpty();
        assertThat(file).isEqualTo(before);
        assertThat(restored).isEqualTo(before);
        report("delete-and-restore", Map.of("deletedResults", removed, "dbSamplesAfterDelete", 0,
            "fileSamplesAfterDelete", file.getFirst().cell().sampleCount(),
            "dbSamplesAfterRestore", restored.getFirst().cell().sampleCount()));
    }

    @Test
    void 동일한_대량_입력으로_실행_중인_파이프라인과_파일_계산의_시간과_결과를_비교한다() throws Exception {
        // given
        final int sampleCount = 3000;
        final int vehicleCount = 20;
        String batchPrefix = UUID.randomUUID().toString().substring(0, 8);
        jdbc.sql("""
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,
              requested_at,response_received_at,outcome,normalization_version,collection_strategy_version)
            SELECT :route,at,1,:prefix||'-'||kind||'-'||g,at,at,
              'SUCCESS_ROWS','normalization-v1.0.0','adaptive-kst-v1.0.1'
            FROM generate_series(0,149) g CROSS JOIN (VALUES ('source',0),('arrival',10)) k(kind,minutes)
            CROSS JOIN LATERAL (SELECT '2026-10-01T00:00:00Z'::timestamptz
              + g*interval '20 minutes' + minutes*interval '1 minute' AS at) times
            """).param("route", route).param("prefix", batchPrefix).update();
        jdbc.sql("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,
              vehicle_id,stop_order,stop_id,passed_stop_order,running_state,remaining_seats)
            SELECT b.id,:route,(g-1)%20,'benchmark-'||((g-1)%20),8,'stop-eight',8,2,12+(g%9)
            FROM generate_series(1,:size) g JOIN observation_batch b
              ON b.attempt_key=:prefix||'-source-'||((g-1)/20)
            """).param("prefix", batchPrefix).param("route", route).param("size", sampleCount).update();
        jdbc.sql("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,
              vehicle_id,stop_order,stop_id,passed_stop_order,running_state,remaining_seats)
            SELECT b.id,:route,(g-1)%20,'benchmark-'||((g-1)%20),9,'stop-nine',9,2,g%21
            FROM generate_series(1,:size) g JOIN observation_batch b
              ON b.attempt_key=:prefix||'-arrival-'||((g-1)/20)
            """).param("prefix", batchPrefix).param("route", route).param("size", sampleCount).update();
        jdbc.sql("""
            INSERT INTO seat_forecast(vehicle_observation_id,target_stop_order,route_version_id,stops_to_target,
              model_deployment_id,demand_statistics_revision,seat_full_chance_raw,seat_full_chance,
              expected_seats,generated_at,quality_revision,publication_id)
            SELECT o.id,9,:route,1,f.model_deployment_id,1,0.5,0.5,10,f.generated_at,1,f.publication_id
            FROM vehicle_observation o CROSS JOIN seat_forecast f
            WHERE o.route_version_id=:route AND o.vehicle_id LIKE 'benchmark-%' AND o.stop_order=8
              AND f.vehicle_observation_id=:source AND f.target_stop_order=9
            """).param("route", route).param("source", source).update();
        jdbc.sql("""
            INSERT INTO forecast_evaluation_result(vehicle_observation_id,target_stop_order,route_version_id,
              scoring_state,scored_at,arrival_observation_id,seats_on_arrival,arrived_at,
              arrival_route_version_id,arrival_vehicle_id,arrival_stop_order,arrival_running_state,
              arrival_remaining_seats,arrival_quality_direction)
            SELECT s.id,9,:route,'SETTLED','2026-10-08T23:30:00Z',a.id,a.remaining_seats,ab.response_received_at,
              :route,a.vehicle_id,9,2,a.remaining_seats,0
            FROM vehicle_observation s JOIN vehicle_observation a ON a.vehicle_id=s.vehicle_id
            JOIN observation_batch sb ON sb.id=s.observation_batch_id
            JOIN observation_batch ab ON ab.id=a.observation_batch_id
            WHERE s.route_version_id=:route AND a.route_version_id=:route AND s.vehicle_id LIKE 'benchmark-%'
              AND s.stop_order=8 AND a.stop_order=9
              AND ab.response_received_at=sb.response_received_at+interval '10 minutes'
            """).param("route", route).update();
        Map<String, Integer> capacities = new HashMap<>();
        capacities.put("bus", 20);
        for (int i = 0; i < vehicleCount; i++) capacities.put("benchmark-" + i, 20);
        // 대량 fixture 적재 직후의 빈 통계로 실행 계획이 왜곡되지 않게 한다.
        for (String table : List.of("vehicle_observation", "observation_batch", "seat_forecast",
            "forecast_evaluation_result", "forecast_evaluation_pending", "route_version", "route_stop",
            "route_data_quality", "stop_demand_pending_sample")) {
            jdbc.sql("ANALYZE " + table).update();
        }
        AtomicInteger sqlCalls = new AtomicInteger();
        doAnswer(invocation -> {
            sqlCalls.incrementAndGet();
            return invocation.callRealMethod();
        }).when(jdbc).sql(org.mockito.ArgumentMatchers.anyString());

        // when
        long start = System.nanoTime();
        int steps = finishPipeline();
        double pipelineMs = millis(start);
        int pipelineSqlCalls = sqlCalls.get();
        List<StopDemandMeasurement> published = new ArrayList<>();
        for (var slot : TimeSlot.values()) {
            for (var cell : statistics.readAsOf(route, slot, DemandStatisticsVersion.CURRENT_CALCULATION_VERSION, UNTIL).cells()) {
                published.add(new StopDemandMeasurement(slot, cell));
            }
        }
        start = System.nanoTime();
        long upper = jdbc.sql("SELECT max(id) FROM vehicle_observation WHERE route_version_id=?").param(route).query(Long.class).single();
        var extracted = extractor.extract(route, source - 1, upper, UNTIL, ZONE, 10000);
        var bundle = StatisticsExtractWriter.write(directory, extracted, 16 * 1024 * 1024);
        double exportMs = millis(start);
        int beforeFileSqlCalls = sqlCalls.get();
        for (var pool : ManagementFactory.getMemoryPoolMXBeans()) pool.resetPeakUsage();
        List<Double> fileMs = new ArrayList<>();
        List<StopDemandMeasurement> calculated = List.of();
        for (int i = 0; i < 5; i++) {
            start = System.nanoTime();
            calculated = calculate(bundle, capacities);
            fileMs.add(millis(start));
        }
        long heapPoolPeaks = ManagementFactory.getMemoryPoolMXBeans().stream()
            .filter(pool -> pool.getType() == java.lang.management.MemoryType.HEAP)
            .mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
        int fileSqlCalls = sqlCalls.get() - beforeFileSqlCalls;
        when(clock.instant()).thenReturn(UNTIL.plusSeconds(21600));
        int beforeRefreshSqlCalls = sqlCalls.get();
        start = System.nanoTime();
        int refreshSteps = finishPipeline();
        double refreshMs = millis(start);

        // then
        assertThat(calculated).hasSameSizeAs(published);
        double maxError = 0;
        for (int i = 0; i < calculated.size(); i++) {
            var actual = calculated.get(i);
            var expected = published.get(i);
            assertThat(actual.timeSlot()).isEqualTo(expected.timeSlot());
            assertThat(actual.cell().stopOrder()).isEqualTo(expected.cell().stopOrder());
            assertThat(actual.cell().sampleCount()).isEqualTo(expected.cell().sampleCount());
            assertThat(actual.cell().dayCount()).isEqualTo(expected.cell().dayCount());
            maxError = Math.max(maxError, Math.abs(actual.cell().averageFillRate() - expected.cell().averageFillRate()));
            maxError = Math.max(maxError, Math.abs(actual.cell().averageNetBoardingRate() - expected.cell().averageNetBoardingRate()));
        }
        assertThat(maxError).isLessThanOrEqualTo(1e-12);
        assertThat(calculated.stream().mapToInt(value -> value.cell().sampleCount()).sum()).isEqualTo(sampleCount);
        assertThat(fileSqlCalls).isZero();
        Map<String, Object> metrics = new java.util.LinkedHashMap<>();
        metrics.put("samples", sampleCount);
        metrics.put("vehicles", vehicleCount);
        metrics.put("maxAbsoluteError", maxError);
        metrics.put("calculatedCells", calculated);
        metrics.put("pipelineFirstMs", pipelineMs);
        metrics.put("pipelineFirstSteps", steps);
        metrics.put("pipelineFirstSqlCalls", pipelineSqlCalls);
        metrics.put("pipelineRefreshMs", refreshMs);
        metrics.put("pipelineRefreshSteps", refreshSteps);
        metrics.put("pipelineRefreshSqlCalls", sqlCalls.get() - beforeRefreshSqlCalls);
        metrics.put("exportMs", exportMs);
        metrics.put("fileCalculationMs", fileMs);
        metrics.put("fileCalculationSqlCalls", fileSqlCalls);
        metrics.put("fileBytes", Files.size(bundle.directory().resolve("rows.jsonl")));
        metrics.put("heapPoolPeakSumBytes", heapPoolPeaks);
        metrics.put("scope", "synthetic; no S3; capacity map supplied; JVM pool peaks are not process RSS; sql calls include settings and transaction work");
        report("bounded-performance", metrics);
    }

    @Test
    void 행을_삭제하는_것과_전체_스캔이_읽는_페이지를_줄이는_것은_다르다() {
        // given: 일회용 Testcontainers DB에만 만드는 독립 실험 테이블이다.
        jdbc.sql("""
            CREATE TABLE sal175_adoption_scan_probe AS
            SELECT row_number() OVER () AS sample_id,e.*,repeat('x',256) AS experimental_payload
            FROM forecast_evaluation_result e CROSS JOIN generate_series(1,5000) g
            WHERE e.vehicle_observation_id=:source
            """).param("source", source).update();
        try {
            jdbc.sql("ANALYZE sal175_adoption_scan_probe").update();
            var before = scanProbe();

            // when
            jdbc.sql("DELETE FROM sal175_adoption_scan_probe WHERE sample_id % 10 <> 0").update();
            jdbc.sql("ANALYZE sal175_adoption_scan_probe").update();
            var deleted = scanProbe();
            jdbc.sql("VACUUM (ANALYZE) sal175_adoption_scan_probe").update();
            var vacuumed = scanProbe();
            // 운영 권장 작업이 아니다. 물리 재작성과 일반 VACUUM의 차이를 재는 실험이다.
            jdbc.sql("VACUUM (FULL, ANALYZE) sal175_adoption_scan_probe").update();
            var rewritten = scanProbe();

            // then
            assertThat(before.rows()).isEqualTo(10000);
            assertThat(deleted.rows()).isEqualTo(1000);
            assertThat(deleted.bytes()).isEqualTo(before.bytes());
            assertThat(vacuumed.bytes()).isEqualTo(before.bytes());
            assertThat(rewritten.rows()).isEqualTo(deleted.rows());
            assertThat(rewritten.bytes()).isLessThan(before.bytes());
            assertThat(rewritten.bufferPages()).isLessThan(deleted.bufferPages());
            report("delete-versus-pages", Map.of("before", before, "afterDelete", deleted,
                "afterVacuum", vacuumed, "afterRewrite", rewritten,
                "scope", "synthetic isolated heap; PostgreSQL buffer accesses, not EBS physical reads"));
        } finally {
            jdbc.sql("DROP TABLE sal175_adoption_scan_probe").update();
        }
    }

    private ScanProbe scanProbe() {
        String plan = jdbc.sql("EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) SELECT count(*) FROM sal175_adoption_scan_probe WHERE scoring_state='LOST'")
            .query(String.class).single();
        var document = JsonMapper.builder().build().readTree(plan).get(0);
        var root = document.get("Plan");
        long buffers = root.get("Shared Hit Blocks").asLong() + root.get("Shared Read Blocks").asLong();
        long bytes = jdbc.sql("SELECT pg_relation_size('sal175_adoption_scan_probe')").query(Long.class).single();
        long rows = jdbc.sql("SELECT count(*) FROM sal175_adoption_scan_probe").query(Long.class).single();
        return new ScanProbe(rows, bytes, buffers, document.get("Execution Time").asDouble());
    }

    private record ScanProbe(long rows, long bytes, long bufferPages, double executionMs) { }

    private long settle() {
        long arrival = observation(batch("2026-10-08T23:10:00Z"), "bus", 9, 5);
        jdbc.sql("""
            UPDATE forecast_evaluation_result SET scoring_state='SETTLED',arrival_observation_id=:arrival,
              seats_on_arrival=5,arrived_at='2026-10-08T23:10:00Z',arrival_route_version_id=:route,
              arrival_vehicle_id='bus',arrival_stop_order=9,arrival_running_state=2,arrival_remaining_seats=5,
              arrival_quality_direction=0 WHERE vehicle_observation_id=:source AND target_stop_order=9
            """).param("arrival", arrival).param("route", route).param("source", source).update();
        return arrival;
    }

    private long batch(String at) {
        return jdbc.sql("""
            INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,
              requested_at,response_received_at,outcome,normalization_version,collection_strategy_version)
            VALUES(:route,CAST(:at AS timestamptz),1,:key,CAST(:at AS timestamptz),CAST(:at AS timestamptz),
              'SUCCESS_ROWS','normalization-v1.0.0','adaptive-kst-v1.0.1') RETURNING id
            """).param("route", route).param("at", at).param("key", UUID.randomUUID().toString().substring(0,20))
            .query(Long.class).single();
    }

    private long observation(long batch, String vehicle, int stop, int seats) {
        return jdbc.sql("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,
              vehicle_id,stop_order,stop_id,passed_stop_order,running_state,remaining_seats)
            VALUES(:batch,:route,0,:vehicle,:stop,:stopId,:stop,2,:seats) RETURNING id
            """).param("batch", batch).param("route", route).param("vehicle", vehicle).param("stop", stop)
            .param("stopId", stop == 8 ? "stop-eight" : "stop-nine").param("seats", seats).query(Long.class).single();
    }

    private StatisticsExtractWriter.Bundle export() {
        return StatisticsExtractWriter.write(directory,
            extractor.extract(route, source - 1, source, UNTIL, ZONE, 10), 8192);
    }

    private List<StopDemandMeasurement> calculate(StatisticsExtractWriter.Bundle bundle, Map<String,Integer> capacities) {
        return StatisticsFileCalculator.calculate(bundle.directory(), bundle.manifest().files(),
            bundle.manifest().scope(), capacities, 10000, 65536);
    }

    private List<StopDemandMeasurement> reference() {
        return new TransactionTemplate(transactions).execute(status ->
            StopDemandAggregator.aggregate(statistics.readHourlyTotals(route, UNTIL), clock));
    }

    private int finishPipeline() {
        for (int i = 1; i <= 1000; i++) {
            if (pipeline.step(route).status() == DemandStatisticsPipeline.Step.Status.COMPLETED) return i;
        }
        throw new AssertionError("통계 파이프라인이 제한된 단계 안에 완료되지 않았다");
    }

    private void transaction(Runnable action) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> action.run());
    }

    private long pendingCount() {
        return jdbc.sql("SELECT count(*) FROM forecast_evaluation_pending WHERE vehicle_observation_id=?")
            .param(source).query(Long.class).single();
    }

    private static double millis(long start) { return (System.nanoTime() - start) / 1_000_000.0; }

    private static void report(String name, Map<String, ?> result) {
        try {
            Path output = Path.of("build", "reports", "sal175-adoption");
            Files.createDirectories(output);
            Files.writeString(output.resolve(name + ".json"), JsonMapper.builder().build().writeValueAsString(result));
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }
}
