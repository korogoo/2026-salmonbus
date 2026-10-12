package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.application.evaluation.RecentObservationBatches;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcRecentObservationBatches implements RecentObservationBatches {
    private final JdbcClient jdbc;

    public JdbcRecentObservationBatches(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    public List<Header> headers(long version, Instant after, int limit) {
        return jdbc.sql("""
            SELECT b.id, b.attempt_number, b.response_received_at, COALESCE(b.stored_rows, -1) AS rows,
                   COALESCE(d.id, 0) AS delivery_id, COALESCE(d.quality_revision, -1) AS quality_revision,
                   v.turn_sequence, (d.id IS NOT NULL AND d.acknowledged_at IS NULL) AS pending
            FROM observation_batch b
            JOIN route_version v ON v.id = b.route_version_id
            LEFT JOIN LATERAL (
                SELECT id, quality_revision, acknowledged_at FROM observation_delivery
                WHERE observation_batch_id = b.id AND attempt_number = b.attempt_number
                  AND kind = 'STORED'
                ORDER BY id DESC LIMIT 1
            ) d ON true
            WHERE b.route_version_id = :version AND b.response_received_at > :after
            ORDER BY b.response_received_at, b.id LIMIT :limit
            """).param("version", version).param("after", after.atOffset(ZoneOffset.UTC))
            .param("limit", limit).query((rs, n) -> new Header(rs.getLong("id"),
                rs.getInt("attempt_number"), rs.getObject("response_received_at", OffsetDateTime.class).toInstant(),
                rs.getInt("rows"), rs.getLong("delivery_id"), rs.getLong("quality_revision"),
                rs.getObject("turn_sequence", Integer.class), rs.getBoolean("pending"))).list();
    }

    @Override
    public List<Row> restore(long version, List<Long> batches, int limit) {
        if (batches.isEmpty()) return List.of();
        return jdbc.sql("""
            SELECT o.observation_batch_id, o.id, o.vehicle_id, o.route_version_id, o.passed_stop_order,
                   b.response_received_at, o.remaining_seats, o.crowd_level,
                   CASE WHEN o.forecast_eligible THEN o.quality_direction END AS quality_direction,
                   o.id AS assessed_id
            FROM forecast_observation_quality o
            JOIN observation_batch b ON b.id = o.observation_batch_id AND b.route_version_id = o.route_version_id
            WHERE o.route_version_id = :version AND o.observation_batch_id IN (:batches)
            ORDER BY b.response_received_at, o.id LIMIT :limit
            """).param("version", version).param("batches", batches).param("limit", limit)
            .query((rs, n) -> new Row(rs.getLong("observation_batch_id"),
                JdbcArrivalObservationQuery.candidate(rs))).list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void acknowledge(List<Long> deliveries) {
        for (int from = 0; from < deliveries.size(); from += 100) {
            jdbc.sql("""
                UPDATE observation_delivery SET acknowledged_at = clock_timestamp()
                WHERE id IN (:ids) AND acknowledged_at IS NULL
                """).param("ids", deliveries.subList(from, Math.min(from + 100, deliveries.size()))).update();
        }
    }
}
