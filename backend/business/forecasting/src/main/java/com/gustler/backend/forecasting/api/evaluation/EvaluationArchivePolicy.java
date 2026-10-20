package com.gustler.backend.forecasting.api.evaluation;

public record EvaluationArchivePolicy(boolean deleteEnabled, int pageSize) {
    public EvaluationArchivePolicy {
        if (pageSize < 1 || pageSize > 1000) {
            throw new IllegalArgumentException("이관 페이지는 1~1000개여야 한다");
        }
    }
}
