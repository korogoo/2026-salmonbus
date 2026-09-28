package com.gustler.backend.processor;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 기존 처리 스레드에서 한 번에 한 노선만 실행한다. 별도 동시 작업자를 늘리지 않는다. */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class SameDayFullOutcomesInitializationJob {
    private static final Logger log = LoggerFactory.getLogger(SameDayFullOutcomesInitializationJob.class);
    private final SameDayFullOutcomesInitializer initializer;
    private final SameDayInitializationProperties properties;
    private final Clock clock;
    private final Map<Long, Instant> retryAt = new HashMap<>();
    private LocalDate date;
    private long lastRouteId = Long.MIN_VALUE;

    public SameDayFullOutcomesInitializationJob(SameDayFullOutcomesInitializer initializer,
        SameDayInitializationProperties properties, Clock clock) {
        this.initializer = initializer;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${forecast.same-day-initialization.interval:10s}")
    public void initializeNext() {
        Instant now = clock.instant();
        SeoulDay day = SeoulDay.containing(now);
        if (!day.date().equals(date)) {
            date = day.date();
            retryAt.clear();
            lastRouteId = Long.MIN_VALUE;
        }
        List<Long> routes = WorkerOperationLog.measure("same_day_initialization_routes", "all",
            initializer::activeRouteIds).stream().distinct().sorted().toList();
        retryAt.keySet().retainAll(routes);
        List<Long> due = routes.stream()
            .filter(route -> !now.isBefore(retryAt.getOrDefault(route, Instant.MIN))).toList();
        if (due.isEmpty()) { return; }
        long route = due.stream().filter(id -> id > lastRouteId).findFirst().orElse(due.getFirst());
        lastRouteId = route;
        var attempt = new SameDayInitializationAttempt();
        Instant startedAt = clock.instant();
        try {
            // 별도 빈의 트랜잭션 프록시가 커밋을 완료하고 반환해야 성공이다.
            boolean initialized = initializer.initialize(route, day, attempt);
            attempt.completed(initialized);
        } catch (RuntimeException failure) {
            attempt.failed(failure);
            throw failure;
        } finally {
            log.atLevel(attempt.status().equals("FAILED") ? Level.ERROR : Level.INFO)
                .log("event=same_day_initialization startedAt={} routeId={} outcomeDate={} status={} sourceAttempted={} lockAcquireMs={} sourceQueryMs={} totalMs={} failedStage={} sqlState={}",
                startedAt.atZone(ZoneId.of("Asia/Seoul")), route, day.date(), attempt.status(),
                attempt.sourceAttempted(), attempt.lockAcquireMs(), attempt.sourceQueryMs(),
                attempt.totalMs(), attempt.failedStage(), attempt.sqlState());
            // 실행 종료부터 간격을 둔다. 실패한 노선도 다음 회차를 독점하지 못한다.
            retryAt.put(route, clock.instant().plus(properties.retryInterval()));
        }
    }
}
