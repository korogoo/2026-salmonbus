package com.gustler.backend.worker.scheduling;

import com.gustler.backend.routecatalog.api.RefreshRouteCatalog;
import static com.gustler.backend.diagnostics.Logfmt.quote;
import com.gustler.backend.worker.configuration.CollectionProperties;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 수집과 같은 단일 스레드에서 최초 1회 및 완료 후 24시간마다 순차 확인한다. */
@Component
@ConditionalOnProperty(prefix = "collection", name = "enabled", havingValue = "true")
public class RouteCatalogRefreshScheduler {
    private static final Logger log = LoggerFactory.getLogger(RouteCatalogRefreshScheduler.class);
    private final CollectionProperties properties;
    private final RefreshRouteCatalog catalog;
    private final Clock clock;

    public RouteCatalogRefreshScheduler(CollectionProperties properties, RefreshRouteCatalog catalog, Clock clock) {
        this.properties = properties;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "PT0S", fixedDelayString = "PT24H", scheduler = "collectionTaskScheduler")
    public void refreshAllRoutes() {
        for (String routeId : properties.routeIds()) {
            try {
                if (catalog.refresh(routeId, OffsetDateTime.now(clock)).isEmpty()) {
                    log.warn("event=route_catalog_refresh_failed routeId={} routeName={} reason=UPSTREAM_OR_QUOTA",
                        quote(routeId), quote(catalog.knownRouteName(routeId)));
                }
            } catch (RuntimeException failure) {
                // 인증키가 포함될 수 있는 HTTP 예외 본문은 기록하지 않는다.
                log.warn("event=route_catalog_refresh_failed routeId={} routeName={} reason=REFRESH_EXCEPTION exceptionType={}",
                    quote(routeId), quote(catalog.knownRouteName(routeId)), quote(failure.getClass().getSimpleName()));
            }
        }
    }
}
