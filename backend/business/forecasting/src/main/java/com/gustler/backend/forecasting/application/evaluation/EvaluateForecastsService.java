package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.forecasting.api.ForecastPolicy;
import com.gustler.backend.forecasting.api.evaluation.EvaluateForecasts;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalCandidate;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabel;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalLabelResolver;
import com.gustler.backend.forecasting.domain.evaluation.ArrivalObservationQuery;
import com.gustler.backend.forecasting.domain.evaluation.ForecastEvaluation;
import com.gustler.backend.forecasting.domain.evaluation.PendingForecast;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class EvaluateForecastsService implements EvaluateForecasts {
    private static final Logger log = LoggerFactory.getLogger(EvaluateForecastsService.class);
    private final EvaluationWorkStore work;
    private final EvaluationWorkPageReader pages;
    private final ForecastEvaluationWriter writer;
    private final ArrivalObservationQuery arrivals;
    private final RecentArrivalObservationCache observationCache;
    private final RouteDataQualityAccess quality;
    private final ForecastPolicy policy;
    private final Clock clock;
    private final PendingEvaluationBuffer buffer;
    private final int pageLimit;
    private final Map<Long, EvaluationSelection.Cursor> cursors = new LinkedHashMap<>();
    private long memoryRevision;

    public EvaluateForecastsService(EvaluationWorkStore work, EvaluationWorkPageReader pages,
        ForecastEvaluationWriter writer, ArrivalObservationQuery arrivals,
        RouteDataQualityAccess quality, ForecastPolicy policy, Clock clock,
        @Value("${forecast.evaluation.memory-limit:30000}") int memoryLimit,
        @Value("${forecast.evaluation.recovery-page-limit:3}") int pageLimit,
        RecentArrivalObservationCache observationCache) {
        if (pageLimit < 1) throw new IllegalArgumentException("복원 페이지 한도는 양수여야 한다");
        this.work = work;
        this.pages = pages;
        this.writer = writer;
        this.arrivals = arrivals;
        this.observationCache = observationCache;
        this.quality = quality;
        this.policy = policy;
        this.clock = clock;
        this.buffer = new PendingEvaluationBuffer(memoryLimit);
        this.pageLimit = pageLimit;
    }

    @Override
    @Transactional
    public void settleArrivalLabels() {
        WorkerOperationLog.run("settlement_tick", "all", this::settleOnce);
    }

    private void settleOnce() {
        Instant now = clock.instant();
        Snapshot snapshot = snapshot();
        var observations = observationCache.begin(now);
        List<Long> versions = WorkerOperationLog.measure("settlement_pending_routes", "all", work::routes);
        Map<Long, Long> revisions = new LinkedHashMap<>();
        for (long version : versions) revisions.put(version, quality.lock(version));

        List<RouteInput> inputs = new ArrayList<>();
        List<ForecastEvaluation> results = new ArrayList<>();
        for (long version : versions) {
            RouteInput input = read(version, revisions.get(version), snapshot);
            inputs.add(input);
            results.addAll(evaluate(input, observations, now));
        }
        writer.complete(results);
        List<Change> changes = new ArrayList<>();
        for (RouteInput input : inputs) changes.add(finish(input));
        afterCommit(snapshot, List.copyOf(versions), List.copyOf(changes), observations);
    }

    private RouteInput read(long version, long revision, Snapshot snapshot) {
        boolean investigating = quality.anyInvestigationPending(version);
        EvaluationSelection.Cursor cursor = snapshot.cursors().getOrDefault(
            version, EvaluationSelection.Cursor.START);
        if (cursor.through() == 0) {
            cursor = new EvaluationSelection.Cursor(work.lastObservationId(version), 0, 0, 0);
        }
        Map<Long, PendingEvaluationBuffer.Entry> cached = new LinkedHashMap<>();
        for (var entry : snapshot.entries()) {
            if (entry.key().routeVersionId() == version) cached.put(entry.key().observationId(), entry);
        }
        Set<Long> checked = new LinkedHashSet<>();
        Map<Long, EvaluationWorkPageReader.Ready> ready = new LinkedHashMap<>();
        List<PendingForecast> selected = new ArrayList<>();
        for (int pageNumber = 0; pageNumber < pageLimit && cursor.through() != 0
             && selected.size() < policy.pendingLimit(); pageNumber++) {
            long after = cursor.after();
            long through = cursor.through();
            var page = WorkerOperationLog.measure("settlement_pending_forecasts", version,
                () -> pages.read(version, after, through, 100, revision, investigating, cached));
            checked.addAll(page.checkedIds());
            for (var item : page.ready()) ready.put(item.observationId(), item);
            var selection = EvaluationSelection.take(cursor, page, policy.pendingLimit() - selected.size());
            selected.addAll(selection.forecasts());
            cursor = selection.next();
        }
        return new RouteInput(version, revision, investigating, cursor, List.copyOf(checked),
            List.copyOf(ready.values()), List.copyOf(selected));
    }

    private List<ForecastEvaluation> evaluate(RouteInput input,
        RecentArrivalObservationCache.Round observations, Instant now) {
        long version = input.version();
        List<PendingForecast> forecasts = input.selected();
        Map<String, List<PendingForecast>> byVehicle = new LinkedHashMap<>();
        for (var forecast : forecasts) {
            byVehicle.computeIfAbsent(forecast.vehicleId(), ignored -> new ArrayList<>()).add(forecast);
        }
        List<ForecastEvaluation> results = new ArrayList<>();
        for (var vehicle : byVehicle.entrySet()) {
            Instant earliest = vehicle.getValue().stream().map(PendingForecast::generatedAt)
                .min(Instant::compareTo).orElseThrow();
            List<ArrivalCandidate> candidates = vehicle.getKey() == null ? List.of()
                : WorkerOperationLog.measure("settlement_arrival_candidates", version,
                    () -> observations.read(arrivals, version, input.revision(), input.investigating(),
                        vehicle.getKey(), earliest, policy.arrivalLimit()));
            for (PendingForecast forecast : vehicle.getValue()) {
                ArrivalLabel label = ArrivalLabelResolver.resolve(forecast, candidates, now);
                if (label.settles()) results.add(ForecastEvaluation.completed(
                    forecast.vehicleObservationId(), forecast.targetStopOrder(), label, now));
            }
        }
        return results;
    }

    private Change finish(RouteInput input) {
        Set<EvaluationWorkStore.Key> completed = new HashSet<>();
        for (int from = 0; from < input.checked().size(); from += 100) {
            List<Long> ids = input.checked().subList(from, Math.min(from + 100, input.checked().size()));
            completed.addAll(work.completed(input.version(), ids));
            work.releaseCompleted(input.version(), ids);
        }
        List<EvaluationWorkPageReader.Ready> remaining = new ArrayList<>();
        if (!input.investigating()) {
            for (var item : input.ready()) {
                List<PendingForecast> pending = item.forecasts().stream()
                    .filter(forecast -> !completed.contains(new EvaluationWorkStore.Key(
                        forecast.vehicleObservationId(), forecast.targetStopOrder()))).toList();
                if (!pending.isEmpty()) remaining.add(new EvaluationWorkPageReader.Ready(
                    item.observationId(), input.revision(), item.workRevision(), pending));
            }
        }
        return new Change(input.version(), input.cursor(), Set.copyOf(input.checked()), List.copyOf(remaining));
    }

    private synchronized Snapshot snapshot() {
        return new Snapshot(memoryRevision, Map.copyOf(cursors), buffer.snapshot());
    }

    private void afterCommit(Snapshot snapshot, List<Long> versions, List<Change> changes,
        RecentArrivalObservationCache.Round observations) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("정산 회차에는 실제 트랜잭션이 필요하다");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_ROLLED_BACK) apply(snapshot, versions, changes, status == STATUS_COMMITTED);
                if (status == STATUS_COMMITTED) {
                    try {
                        observations.committed(clock.instant());
                    } catch (RuntimeException failure) {
                        log.warn("event=arrival_cache_publish_failed exceptionType={}",
                            failure.getClass().getSimpleName());
                    }
                }
            }
        });
    }

    private synchronized void apply(Snapshot snapshot, List<Long> versions, List<Change> changes,
                                    boolean committed) {
        if (snapshot.revision() != memoryRevision) return;
        memoryRevision++;
        if (!committed) {
            resetMemory();
            log.warn("event=evaluation_memory_reset reason=UNKNOWN_TRANSACTION_RESULT");
            return;
        }
        try {
            Set<Long> active = Set.copyOf(versions);
            Map<Long, Change> byVersion = new LinkedHashMap<>();
            for (Change change : changes) byVersion.put(change.version(), change);
            for (var entry : buffer.snapshot()) {
                Change change = byVersion.get(entry.key().routeVersionId());
                if (!active.contains(entry.key().routeVersionId())
                    || (change != null && change.checked().contains(entry.key().observationId()))) {
                    buffer.invalidate(entry);
                }
            }
            cursors.keySet().retainAll(active);
            for (Change change : changes) {
                cursors.put(change.version(), change.cursor());
                for (var item : change.remaining()) {
                    while (!buffer.restore(item.qualityRevision(), item.workRevision(), item.forecasts())) {
                        buffer.evictOldest();
                    }
                }
            }
        } catch (RuntimeException failure) {
            resetMemory();
            log.warn("event=evaluation_memory_reset reason=AFTER_COMMIT_FAILED exceptionType={}",
                failure.getClass().getSimpleName());
        }
    }

    private void resetMemory() {
        buffer.clear();
        cursors.clear();
    }

    private record Snapshot(long revision, Map<Long, EvaluationSelection.Cursor> cursors,
                            List<PendingEvaluationBuffer.Entry> entries) { }
    private record RouteInput(long version, long revision, boolean investigating,
        EvaluationSelection.Cursor cursor, List<Long> checked,
        List<EvaluationWorkPageReader.Ready> ready, List<PendingForecast> selected) { }
    private record Change(long version, EvaluationSelection.Cursor cursor, Set<Long> checked,
                          List<EvaluationWorkPageReader.Ready> remaining) { }
}
