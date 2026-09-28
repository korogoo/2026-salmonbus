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

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 2)
    public boolean initialize(long routeId, SeoulDay day) {
        configureTimeouts();
        jdbc.sql("SELECT id FROM route WHERE id = :routeId FOR UPDATE")
            .param("routeId", routeId).query(Long.class).single();
        // 잠금을 기다리는 동안 다른 작업이 준비했을 수 있으므로 반드시 잠금 뒤에 다시 확인한다.
        return service.initializeIfAbsent(routeId, day);
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
