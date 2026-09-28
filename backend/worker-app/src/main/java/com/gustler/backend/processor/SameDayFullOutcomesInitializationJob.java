package com.gustler.backend.processor;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
        try {
            boolean initialized = WorkerOperationLog.measure("same_day_initialize_and_commit", route,
                () -> initializer.initialize(route, day));
            // 별도 빈의 프록시가 커밋에 성공해서 돌아온 뒤에만 성공 로그를 남긴다.
            if (initialized) {
                log.info("event=same_day_initialization status=COMPLETED routeId={} outcomeDate={}", route, day.date());
            }
        } finally {
            // 실행 종료부터 간격을 둔다. 실패한 노선도 다음 회차를 독점하지 못한다.
            retryAt.put(route, clock.instant().plus(properties.retryInterval()));
        }
    }
}
