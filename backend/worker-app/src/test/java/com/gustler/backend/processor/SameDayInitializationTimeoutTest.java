package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class SameDayInitializationTimeoutTest extends SameDayTransactionBoundaryTest {
    @Override
    SameDayInitializationProperties initializationProperties() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new PropertiesPropertySource("worker-yaml", yaml.getObject()));
        return Binder.get(environment).bind("forecast.same-day-initialization",
            Bindable.of(SameDayInitializationProperties.class)).get();
    }

    @Test
    void 초기화는_2초를_넘긴_조회도_25초_SQL과_30초_트랜잭션_예산_안에서_완료한다() {
        var attempt = new SameDayInitializationAttempt();
        doAnswer(call -> {
            assertThat(jdbc.sql("SHOW statement_timeout").query(String.class).single()).isEqualTo("25s");
            assertThat(jdbc.sql("SHOW lock_timeout").query(String.class).single()).isEqualTo("100ms");
            var holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(dataSource);
            assertThat(holder.getTimeToLiveInMillis()).isGreaterThan(25_000L).isLessThanOrEqualTo(30_000L);
            // 과거 트랜잭션 제한 2초를 실제 DB 호출로 넘긴다. 운영 데이터 부하는 재현하지 않는다.
            jdbc.sql("SELECT pg_sleep(2.1)").query().singleRow();
            return call.callRealMethod();
        }).when(countsSpy).countFromSource(anyLong(), any(), any());

        assertThat(context.getBean(SameDayFullOutcomesInitializer.class)
            .initialize(routeId, SeoulDay.containing(NOW), attempt)).isTrue();
        assertThat(attempt.sourceQueryMs()).isGreaterThanOrEqualTo(2_000L);
        assertThat(count("same_day_full_outcomes")).isEqualTo(1);
    }
}
