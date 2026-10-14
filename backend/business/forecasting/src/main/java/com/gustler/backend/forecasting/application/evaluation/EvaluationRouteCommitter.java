package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluation;
import com.gustler.backend.forecasting.domain.evaluation.PendingForecast;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 한 노선 버전의 정산·통계 표본·당일 합계·작업 종료를 함께 확정한다. */
@Component
public class EvaluationRouteCommitter {
    private static final Logger log = LoggerFactory.getLogger(EvaluationRouteCommitter.class);
    private final EvaluationWorkStore work;
    private final RouteDataQualityAccess quality;
    private final ForecastEvaluationWriter writer;

    public EvaluationRouteCommitter(EvaluationWorkStore work, RouteDataQualityAccess quality,
        ForecastEvaluationWriter writer) {
        this.work = work;
        this.quality = quality;
        this.writer = writer;
    }

    @Transactional
    public void commit(EvaluateForecastsService.RouteInput input, List<ForecastEvaluation> results,
        BiConsumer<Integer, Change> completion) {
        requireTransaction();
        long actualRevision = quality.lock(input.version());
        if (actualRevision != input.revision()) {
            log.info("event=settlement_skipped reason=QUALITY_CHANGED routeVersionId={} expectedRevision={} actualRevision={}",
                input.version(), input.revision(), actualRevision);
            return;
        }
        if (!workUnchanged(input)) {
            log.info("event=settlement_skipped reason=WORK_CHANGED routeVersionId={}", input.version());
            return;
        }

        Set<EvaluationWorkStore.Key> completed = new HashSet<>();
        for (List<Long> ids : chunks(input.checked())) {
            work.closeExcluded(input.version(), ids, 500);
            completed.addAll(work.completed(input.version(), ids));
        }
        Set<EvaluationWorkStore.Key> selected = new HashSet<>();
        for (PendingForecast forecast : input.selected()) {
            selected.add(new EvaluationWorkStore.Key(forecast.vehicleObservationId(), forecast.targetStopOrder()));
        }
        List<ForecastEvaluation> pending = results.stream().filter(result -> {
            var key = new EvaluationWorkStore.Key(result.vehicleObservationId(), result.targetStopOrder());
            return selected.contains(key) && !completed.contains(key);
        }).toList();
        writer.complete(pending);
        for (List<Long> ids : chunks(input.checked())) {
            completed.addAll(work.completed(input.version(), ids));
            work.releaseCompleted(input.version(), ids);
        }

        List<EvaluationWorkPageReader.Ready> remaining = new ArrayList<>();
        if (!input.investigating()) {
            for (var item : input.ready()) {
                List<PendingForecast> forecasts = item.forecasts().stream()
                    .filter(forecast -> !completed.contains(new EvaluationWorkStore.Key(
                        forecast.vehicleObservationId(), forecast.targetStopOrder()))).toList();
                if (!forecasts.isEmpty()) remaining.add(new EvaluationWorkPageReader.Ready(
                    item.observationId(), input.revision(), item.workRevision(), forecasts));
            }
        }
        Change change = new Change(input.version(), input.cursor(), Set.copyOf(input.checked()),
            List.copyOf(remaining));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                completion.accept(status, change);
            }
        });
    }

    private boolean workUnchanged(EvaluateForecastsService.RouteInput input) {
        Map<Long, Long> current = new LinkedHashMap<>();
        for (List<Long> ids : chunks(input.checked())) current.putAll(work.revisions(input.version(), ids));
        return current.size() == input.checked().size() && current.equals(input.workRevisions());
    }

    private static List<List<Long>> chunks(List<Long> ids) {
        List<List<Long>> chunks = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += 100) {
            chunks.add(ids.subList(from, Math.min(from + 100, ids.size())));
        }
        return chunks;
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("노선별 정산 확정에는 실제 트랜잭션이 필요하다");
        }
    }

    public record Change(long version, EvaluationSelection.Cursor cursor, Set<Long> checked,
                         List<EvaluationWorkPageReader.Ready> remaining) { }
}
