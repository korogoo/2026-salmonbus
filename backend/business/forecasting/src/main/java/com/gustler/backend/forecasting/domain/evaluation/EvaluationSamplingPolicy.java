package com.gustler.backend.forecasting.domain.evaluation;

import java.time.Duration;
import java.util.Objects;

/** 모든 예보를 평가하되, 상세 결과를 남길 예보를 고른다. 한 정류장 앞은 항상 상세를 남긴다. */
public record EvaluationSamplingPolicy(Duration interval) {
    public EvaluationSamplingPolicy {
        Objects.requireNonNull(interval);
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("평가 표본 간격은 양수여야 한다");
        }
    }

    public boolean includeAll(EvaluationSamplingPoint previous, EvaluationSamplingPoint current) {
        Objects.requireNonNull(current);
        if (previous == null) {
            return true;
        }
        if (previous.routeVersionId() != current.routeVersionId()
            || previous.modelDeploymentId() != current.modelDeploymentId()
            || !previous.vehicleId().equals(current.vehicleId())
            || previous.passedStopOrder() != current.passedStopOrder()
            || !previous.direction().equals(current.direction())) {
            return true;
        }
        // 역순 재처리는 새 관측의 표본 기준을 뒤로 옮기지 않고 모두 보존한다.
        return !current.observedAt().isAfter(previous.observedAt())
            || Duration.between(previous.observedAt(), current.observedAt()).compareTo(interval) >= 0;
    }

    public boolean includesDistance(boolean includeAll, int stopsToTarget) {
        return includeAll || stopsToTarget == 1;
    }
}
