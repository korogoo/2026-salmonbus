package com.gustler.backend.forecasting.application.evaluation;

public interface ObservationDeliveryStore {
    long append(long batchId, long routeVersionId, int attempt, String kind);
    void acknowledge(long deliveryId);
}
