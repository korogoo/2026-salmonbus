package com.gustler.backend.forecasting.configuration;

import com.gustler.backend.forecasting.infrastructure.statistics.EvaluationArchiveObjectStore;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "forecast.archive", name = "enabled", havingValue = "true")
public class EvaluationArchiveConfiguration {
    @Bean
    EvaluationArchiveObjectStore evaluationArchiveObjectStore(
        @Value("${forecast.archive.work-directory}") Path directory,
        @Value("${forecast.archive.max-file-bytes:1048576}") long maxBytes) {
        return new EvaluationArchiveObjectStore(directory, maxBytes);
    }
}
