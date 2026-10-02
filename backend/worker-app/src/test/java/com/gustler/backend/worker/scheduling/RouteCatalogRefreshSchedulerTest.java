package com.gustler.backend.worker.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gustler.backend.routecatalog.api.RefreshRouteCatalog;
import com.gustler.backend.routecatalog.api.RouteReference;
import com.gustler.backend.worker.configuration.CollectionProperties;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class RouteCatalogRefreshSchedulerTest {
    @Test
    void 실패_이유와_알려진_이름만_남기고_예외_본문은_노출하지_않는다() {
        var catalog = mock(RefreshRouteCatalog.class);
        when(catalog.knownRouteName("id-1")).thenReturn("3330");
        when(catalog.knownRouteName("id-2")).thenReturn("");
        when(catalog.refresh(eq("id-1"), any())).thenReturn(Optional.empty());
        when(catalog.refresh(eq("id-2"), any())).thenThrow(new IllegalStateException("serviceKey=SECRET\nHTTP body"));
        when(catalog.refresh(eq("id-3"), any())).thenReturn(Optional.of(new RouteReference(3)));
        var scheduler = new RouteCatalogRefreshScheduler(
            new CollectionProperties(true, List.of("id-1", "id-2", "id-3")), catalog, Clock.systemUTC());
        var logger = (Logger) LoggerFactory.getLogger(RouteCatalogRefreshScheduler.class);
        var logs = new ListAppender<ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            scheduler.refreshAllRoutes();
            assertThat(logs.list).hasSize(2);
            assertThat(logs.list.getFirst().getFormattedMessage()).contains(
                "routeId=\"id-1\"", "routeName=\"3330\"", "reason=UPSTREAM_OR_QUOTA");
            assertThat(logs.list.getLast().getFormattedMessage()).contains(
                "routeId=\"id-2\"", "routeName=\"\"", "reason=REFRESH_EXCEPTION", "exceptionType=\"IllegalStateException\"")
                .doesNotContain("SECRET", "HTTP body");
            assertThat(logs.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
            verify(catalog).refresh(eq("id-3"), any());
            verify(catalog, never()).knownRouteName("id-3");
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }
}
