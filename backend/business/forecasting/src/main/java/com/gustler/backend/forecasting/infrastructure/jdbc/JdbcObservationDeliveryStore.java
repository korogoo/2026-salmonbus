package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.application.evaluation.ObservationDeliveryStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcObservationDeliveryStore implements ObservationDeliveryStore {
    private final JdbcClient jdbc;

    public JdbcObservationDeliveryStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long append(long batchId, long routeVersionId, int attempt, String kind) {
        return jdbc.sql("""
            INSERT INTO observation_delivery (observation_batch_id, route_version_id, attempt_number, kind)
            VALUES (:batch, :version, :attempt, :kind) RETURNING id
            """).param("batch", batchId).param("version", routeVersionId)
            .param("attempt", attempt).param("kind", kind).query(Long.class).single();
    }

    @Override
    @Transactional
    public void acknowledge(long deliveryId) {
        jdbc.sql("""
            UPDATE observation_delivery SET acknowledged_at = clock_timestamp()
            WHERE id = :id AND acknowledged_at IS NULL
            """).param("id", deliveryId).update();
    }
}
