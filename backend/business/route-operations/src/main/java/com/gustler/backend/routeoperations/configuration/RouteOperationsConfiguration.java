package com.gustler.backend.routeoperations.configuration;

import com.gustler.backend.routeoperations.api.RouteOperations;
import com.gustler.backend.routeoperations.infrastructure.jdbc.JdbcRouteOperations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/** Opt in once at deployment; individual changes subsequently require no restart. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "route-operations", name = "enabled", havingValue = "true")
public class RouteOperationsConfiguration {
    @Bean
    RouteOperations routeOperations(JdbcClient jdbc, PlatformTransactionManager transactions) {
        return create(jdbc, transactions);
    }

    public static RouteOperations create(JdbcClient jdbc, PlatformTransactionManager transactions) {
        return new JdbcRouteOperations(jdbc, transactions);
    }
}
