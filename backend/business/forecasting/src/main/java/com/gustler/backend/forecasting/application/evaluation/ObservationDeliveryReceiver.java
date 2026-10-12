package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.observations.api.ObservationDeliveryBatch;

/** 실제 반영을 마쳤거나 동일 전달이 이미 반영됐을 때만 true를 반환한다. */
public interface ObservationDeliveryReceiver {
    boolean receive(long deliveryId, ObservationDeliveryBatch batch);
}
