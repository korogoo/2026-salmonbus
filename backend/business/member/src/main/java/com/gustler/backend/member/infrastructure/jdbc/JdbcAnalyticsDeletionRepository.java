package com.gustler.backend.member.infrastructure.jdbc;

import com.gustler.backend.member.domain.AnalyticsDeletionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAnalyticsDeletionRepository implements AnalyticsDeletionRepository {
    private final JdbcClient jdbc;

    public JdbcAnalyticsDeletionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(UUID publicId) {
        jdbc.sql("INSERT INTO analytics_deletion (public_id, attempts) VALUES (?, 0)")
            .param(publicId).update();
    }

    @Override
    public List<UUID> lockPending(int limit) {
        return jdbc.sql("SELECT public_id FROM analytics_deletion ORDER BY attempts, public_id LIMIT ? FOR UPDATE SKIP LOCKED")
            .param(limit).query(UUID.class).list();
    }

    @Override
    public boolean lock(UUID publicId) {
        return jdbc.sql("SELECT public_id FROM analytics_deletion WHERE public_id = ? FOR UPDATE SKIP LOCKED")
            .param(publicId).query(UUID.class).optional().isPresent();
    }

    @Override
    public void failed(UUID publicId) {
        jdbc.sql("UPDATE analytics_deletion SET attempts = LEAST(attempts + 1::bigint, 2147483647) WHERE public_id = ?")
            .param(publicId).update();
    }

    @Override
    public void delete(UUID publicId) {
        jdbc.sql("DELETE FROM analytics_deletion WHERE public_id = ?").param(publicId).update();
    }
}
