package com.gustler.backend.forecasting.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gustler.backend.forecasting.domain.evaluation.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class ForecastMonitoringTest {
    @Test void 좌석오차는_예측좌석으로_계산하고_누락과_제외는_정답으로_세지_않는다() {
        Logger logger = (Logger) LoggerFactory.getLogger(ForecastMonitoring.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>(); logs.start(); logger.addAppender(logs);
        try {
            ForecastMonitoring monitoring = new ForecastMonitoring(new SimpleMeterRegistry(), mock(DataSource.class), java.time.Clock.systemUTC());
            monitoring.settled(List.of(sample(10.0, true), sample(null, true), sample(30.0, false)));
            monitoring.flushAccuracy();
            String line = logs.list.getFirst().getFormattedMessage();
            assertThat(line).contains("routeName=\"3330\"", "stopName=\"야탑역 광장\"", "completed=3", "maeCount=1",
                "absoluteErrorSum=7.0", "probabilityCount=2", "brierSum=0.125")
                .doesNotContain("private-vehicle");
            monitoring.flushAccuracy();
            assertThat(logs.list).hasSize(1);
        } finally { logger.detachAppender(logs); }
    }
    @Test void 정상_실행도_처리시간_분포에_포함하고_노선별_히스토그램은_만들지_않는다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ForecastMonitoring monitoring = new ForecastMonitoring(registry, mock(DataSource.class), java.time.Clock.systemUTC());
        monitoring.completed("forecast_tick", "all", 5000000, false);
        monitoring.completed("forecast_tick", 123L, 2000000, false);
        assertThat(registry.get("salmonbus.worker.operation").tag("outcome", "returned").timer().count()).isEqualTo(2);
        assertThat(registry.find("salmonbus.worker.operation").timers()).hasSize(1);
    }
    @Test void 이름의_공백_따옴표_개행을_로그_필드에서_안전하게_표현한다() {
        assertThat(ForecastMonitoring.quote("역 \"A\"\n출구")).isEqualTo("\"역 \\\"A\\\"\\n출구\"");
    }
    private SettledEvaluation sample(Double prediction, boolean usable) {
        return new SettledEvaluation(1, 6, 2, 3, 4, 2, .2, ScoringState.SETTLED,
            5L, 3, Instant.now(), Instant.now(), usable, "private-vehicle", 40, true,
            new EvaluationDiagnostics("3330", "야탑역 광장", "station1", "UP", 6, prediction, .25));
    }
}
