package com.gustler.backend.api.account.analytics;

import com.gustler.backend.member.api.AnalyticsDeletions;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"auth.enabled", "analytics.amplitude.enabled"}, havingValue = "true")
@EnableConfigurationProperties(AmplitudeDeletionProperties.class)
public class AmplitudeDeletionConfiguration {
    @Bean
    AmplitudeDeletionClient amplitudeDeletionClient(AmplitudeDeletionProperties properties) {
        properties.requireComplete();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return new AmplitudeDeletionClient(RestClient.builder().requestFactory(factory).build(), properties);
    }

    @Bean(defaultCandidate = false)
    ThreadPoolTaskExecutor amplitudeDeletionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("amplitude-deletion-");
        return executor;
    }

    @Bean
    AmplitudeDeletionJob amplitudeDeletionJob(
        AnalyticsDeletions deletions,
        AmplitudeDeletionClient client,
        @Qualifier("amplitudeDeletionExecutor") Executor executor
    ) {
        return new AmplitudeDeletionJob(deletions, client, executor);
    }
}
