package com.gustler.backend.processor;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 정산/품질 변경과 같은 route 잠금을 쓰고, 원본 계산과 저장을 한 트랜잭션으로 닫는다. */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class SameDayFullOutcomesInitializer {
    private final JdbcClient jdbc;
    private final SameDayFullOutcomesService service;
    private final SameDayFullOutcomesRepository repository;
    private final SameDayInitializationProperties properties;

    public SameDayFullOutcomesInitializer(JdbcClient jdbc, SameDayFullOutcomesService service,
        SameDayFullOutcomesRepository repository, SameDayInitializationProperties properties) {
        this.jdbc = jdbc;
        this.service = service;
        this.repository = repository;
        this.properties = properties;
    }

    // 원본 SQL 25초에 준비 확인/저장/커밋 여유를 둔다. 노선 목록 조회 제한과는 별개다.
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 30)
    public boolean initialize(long routeId, SeoulDay day) {
        return initialize(routeId, day, new SameDayInitializationAttempt());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 30)
    public boolean initialize(long routeId, SeoulDay day, SameDayInitializationAttempt attempt) {
        attempt.run(SameDayInitializationAttempt.Stage.CONFIGURE, this::configureTimeouts);
        attempt.measure(SameDayInitializationAttempt.Stage.LOCK, () ->
            jdbc.sql("SELECT id FROM route WHERE id = :routeId FOR UPDATE")
                .param("routeId", routeId).query(Long.class).single());
        // 잠금을 기다리는 동안 준비됐을 수 있으므로 잠금 획득 후 다시 확인한다.
        boolean initialized = service.initializeIfAbsent(routeId, day, attempt);
        attempt.awaitingCommit();
        return initialized;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 2, readOnly = true)
    public List<Long> activeRouteIds() {
        configureTimeouts();
        return repository.findActiveRouteIds();
    }

    private void configureTimeouts() {
        jdbc.sql("SELECT set_config('statement_timeout', :statement, true), set_config('lock_timeout', :lock, true)")
            .param("statement", properties.statementTimeout().toMillis() + "ms")
            .param("lock", properties.lockTimeout().toMillis() + "ms").query().singleRow();
    }
}
