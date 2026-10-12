package com.gustler.backend.worker.integration.observations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.forecasting.application.evaluation.ObservationDeliveryReceiver;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcObservationDeliveryStore;
import com.gustler.backend.gbis.api.GbisLocationResult.Success;
import com.gustler.backend.gbis.api.dto.BusLocationResponse.BusLocation;
import com.gustler.backend.observations.api.ObservationDeliveryBatch;
import com.gustler.backend.observations.application.ObservationBatchLedger;
import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.infrastructure.gbis.GbisObservationMapper;
import com.gustler.backend.support.PostgresTestContainer;
import com.gustler.backend.worker.WorkerApplication;
import com.gustler.backend.worker.configuration.CollectionRuntimeConfiguration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = WorkerApplication.class,
    properties = "forecast.evaluation.observation-cache.enabled=true")
@Import({PostgresTestContainer.class, CollectionRuntimeConfiguration.class,
    ObservationDeliveryIntegrationTest.ReceiverConfiguration.class})
class ObservationDeliveryIntegrationTest {
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-08-19T11:14:00+09:00");
    private static final String SOURCE_ROUTE = "delivery-test-route";
    @Autowired ObservationBatchLedger ledger;
    @Autowired JdbcClient jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired RecordingReceiver receiver;
    @Autowired JdbcObservationDeliveryStore store;
    private long version;
    private long batch;
    private CollectionPlan plan;

    @BeforeEach
    void 수집할_노선을_준비한다() {
        long route = jdbc.sql("""
            INSERT INTO route (public_route_id, source_id, source_route_id,
                display_name, start_stop_name, end_stop_name)
            VALUES (:source, 'GBIS', :source, '전달 검증', '출발', '도착') RETURNING id
            """).param("source", SOURCE_ROUTE).query(Long.class).single();
        version = jdbc.sql("""
            INSERT INTO route_version (route_id, content_digest, valid_from, turn_sequence)
            VALUES (:route, :digest, :at, 2) RETURNING id
            """).param("route", route).param("digest", "0".repeat(64)).param("at", AT)
            .query(Long.class).single();
        jdbc.sql("""
            INSERT INTO route_stop (route_version_id, stop_order, stop_id, name, direction, boarding_allowed)
            VALUES (:version, 1, 'stop-one', '출발', 'UP', true),
                   (:version, 2, 'stop-two', '반환', 'UP', true),
                   (:version, 3, 'stop-three', '도착', 'DOWN', true)
            """).param("version", version).update();
        plan = new CollectionPlan(version, AT, "delivery-test");
        batch = ledger.reserve(plan, AT).batchId();
        ledger.markDispatching(batch, AT, AT, "a");
    }

    @AfterEach
    void 저장한_자료와_수신_상태를_정리한다() {
        jdbc.sql("DROP TRIGGER IF EXISTS delivery_test_failure ON observation_delivery").update();
        jdbc.sql("DROP FUNCTION IF EXISTS delivery_test_failure()").update();
        receiver.deliveries.clear();
        receiver.fail = false;
        receiver.accept = true;
        jdbc.sql("""
            TRUNCATE vehicle_observation, observation_batch, route_stop, route_version, route,
                daily_call_quota RESTART IDENTITY CASCADE
            """).update();
    }

    @Test
    void 관측_저장이_취소되면_전달_기록도_남지_않는다() {
        // given
        var transaction = new TransactionTemplate(transactions);

        // when
        transaction.executeWithoutResult(status -> {
            save();
            assertThat(count("observation_delivery")).isEqualTo(1);
            status.setRollbackOnly();
        });

        // then
        assertThat(count("vehicle_observation")).isZero();
        assertThat(count("observation_delivery")).isZero();
        assertThat(receiver.deliveries).isEmpty();
    }

    @Test
    void 관측_저장이_커밋되기_전에는_메모리에_전달하지_않는다() {
        // given
        var transaction = new TransactionTemplate(transactions);

        // when
        transaction.executeWithoutResult(status -> {
            save();
            assertThat(count("vehicle_observation")).isEqualTo(1);
            assertThat(receiver.deliveries).isEmpty();
        });

        // then
        assertThat(receiver.deliveries).hasSize(1);
        ObservationDeliveryBatch delivered = receiver.deliveries.getFirst().batch();
        assertThat(delivered.batchId()).isEqualTo(batch);
        assertThat(delivered.rows()).hasSize(1);
        assertThat(delivered.rows().getFirst().observationId()).isEqualTo(
            jdbc.sql("SELECT id FROM vehicle_observation").query(Long.class).single());
        assertThat(delivered.rows().getFirst().passedStopOrder()).isEqualTo(
            jdbc.sql("SELECT passed_stop_order FROM vehicle_observation").query(Integer.class).single());
        assertThat(delivered.rows().getFirst().remainingSeats()).isEqualTo(20);
        assertThat(pending()).isEqualTo(1);
    }

    @Test
    void 전달에_실패해도_저장한_관측과_미전달_기록은_남는다() {
        // given
        receiver.fail = true;

        // when
        save();

        // then
        assertThat(count("vehicle_observation")).isEqualTo(1);
        assertThat(pending()).isEqualTo(1);
        assertThat(receiver.deliveries).isEmpty();
    }

    @Test
    void 같은_수집_배치를_다시_저장하면_새로운_전달로_구분한다() {
        // given
        save();

        // when
        long reused = ledger.reserve(plan, AT.plusSeconds(1)).batchId();
        ledger.markDispatching(reused, AT, AT.plusSeconds(1), "a");
        save();

        // then
        assertThat(reused).isEqualTo(batch);
        assertThat(receiver.deliveries).hasSize(3);
        assertThat(receiver.deliveries.stream().map(Delivery::id).distinct().count()).isEqualTo(3);
        assertThat(receiver.deliveries.stream().map(item -> item.batch().attempt()).toList())
            .containsExactly(1, 2, 2);
        assertThat(receiver.deliveries.stream().map(item -> item.batch().kind()).toList())
            .containsExactly(ObservationDeliveryBatch.Kind.STORED,
                ObservationDeliveryBatch.Kind.CLEARED, ObservationDeliveryBatch.Kind.STORED);
        assertThat(receiver.deliveries.get(1).batch().rows()).isEmpty();
        assertThat(count("vehicle_observation")).isEqualTo(1);
    }

    @Test
    void 이전_전달을_완료해도_새_전달은_미완료로_남는다() {
        // given
        receiver.accept = false;
        save();
        long previous = receiver.deliveries.getFirst().id();
        ledger.reserve(plan, AT.plusSeconds(1));
        ledger.markDispatching(batch, AT, AT.plusSeconds(1), "a");
        save();

        // when
        store.acknowledge(previous);

        // then
        assertThat(pending()).isEqualTo(2);
        assertThat(jdbc.sql("""
            SELECT id FROM observation_delivery WHERE acknowledged_at IS NOT NULL
            """).query(Long.class).list()).containsExactly(previous);
    }

    @Test
    void 완료_기록_반영에_실패해도_관측과_미전달_기록은_남는다() {
        // given
        failDeliverySql("UPDATE");
        save();

        // when
        assertThatThrownBy(() -> store.acknowledge(receiver.deliveries.getFirst().id()))
            .isInstanceOf(DataAccessException.class);

        // then
        assertThat(count("vehicle_observation")).isEqualTo(1);
        assertThat(receiver.deliveries).hasSize(1);
        assertThat(pending()).isEqualTo(1);
    }

    @Test
    void 메모리에_전달한_기록은_수집이_끝난_뒤_별도로_완료한다() {
        // given
        save();
        assertThat(pending()).isEqualTo(1);

        // when
        store.acknowledge(receiver.deliveries.getFirst().id());

        // then
        assertThat(pending()).isZero();
        assertThat(count("vehicle_observation")).isEqualTo(1);
    }

    @Test
    void 전달_기록을_저장하지_못하면_관측도_저장하지_않는다() {
        // given
        failDeliverySql("INSERT");

        // when
        assertThatThrownBy(this::save).isInstanceOf(DataAccessException.class);

        // then
        assertThat(count("vehicle_observation")).isZero();
        assertThat(count("observation_delivery")).isZero();
        assertThat(receiver.deliveries).isEmpty();
    }

    private void failDeliverySql(String operation) {
        jdbc.sql("""
            CREATE FUNCTION delivery_test_failure() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                RAISE EXCEPTION '전달 기록 실패 검증';
            END;
            $$
            """).update();
        jdbc.sql("CREATE TRIGGER delivery_test_failure BEFORE " + operation
            + " ON observation_delivery FOR EACH ROW EXECUTE FUNCTION delivery_test_failure()")
            .update();
    }

    private void save() {
        var bus = new BusLocation("경기70아0001", "delivery-test-vehicle", 0, SOURCE_ROUTE, 11,
            "stop-one", 1, 2, 20, 3, 1);
        ledger.conclude(batch,
            at -> GbisObservationMapper.response(new Success("2026-08-19 11:14:00.9", List.of(bus)), at),
            AT.plusSeconds(2));
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    private int pending() {
        return jdbc.sql("SELECT count(*) FROM observation_delivery WHERE acknowledged_at IS NULL")
            .query(Integer.class).single();
    }

    record Delivery(long id, ObservationDeliveryBatch batch) { }

    static class RecordingReceiver implements ObservationDeliveryReceiver {
        final List<Delivery> deliveries = new ArrayList<>();
        boolean fail;
        boolean accept = true;

        @Override
        public boolean receive(long id, ObservationDeliveryBatch batch) {
            if (fail) throw new IllegalStateException("전달 실패");
            deliveries.add(new Delivery(id, batch));
            return accept;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ReceiverConfiguration {
        @Bean
        RecordingReceiver observationDeliveryReceiver() {
            return new RecordingReceiver();
        }
    }
}
