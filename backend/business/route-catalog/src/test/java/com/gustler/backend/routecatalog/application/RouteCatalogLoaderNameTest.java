package com.gustler.backend.routecatalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.gustler.backend.quota.api.ApiCallQuota;
import com.gustler.backend.routecatalog.domain.*;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

class RouteCatalogLoaderNameTest {
    @Test
    void 이미_받은_이름을_재사용하며_로그용_조회는_추가하지_않는다() {
        var current = mock(CurrentRouteVersionQuery.class);
        var quota = mock(ApiCallQuota.class);
        var source = mock(RouteSource.class);
        var registry = mock(RouteRegistry.class);
        var versions = mock(RouteVersionLoader.class);
        var transaction = mock(TransactionTemplate.class);
        var loader = new RouteCatalogLoader(current, quota, source, registry, versions, transaction);
        assertThat(loader.knownRouteName("204000057")).isEmpty();
        verifyNoInteractions(current, quota, source, registry, versions, transaction);
        when(source.requiredCallsPerRead()).thenReturn(2);
        when(quota.reserveRouteCatalog(any(), eq(2))).thenReturn(true, false);
        when(source.read("204000057")).thenReturn(new RouteSourceResult.Success(
            new UpstreamRoute("204000057", "3330", "start", "end", null, null)));
        when(transaction.execute(any())).thenReturn(1L);
        loader.refresh("204000057", OffsetDateTime.now());
        assertThat(loader.refresh("204000057", OffsetDateTime.now())).isEmpty();
        clearInvocations(current, quota, source, registry, versions, transaction);
        assertThat(loader.knownRouteName("204000057")).isEqualTo("3330");
        assertThat(loader.knownRouteName("unknown")).isEmpty();
        verifyNoInteractions(current, quota, source, registry, versions, transaction);
    }
}
