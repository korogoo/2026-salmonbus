package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.observations.api.ObservationDeliveryBatch;

/** 커밋된 관측을 수집 시점의 품질 버전과 함께 캐시에 전달한다. */
public interface ObservationDeliveryReceiver {
    boolean receive(long qualityRevision, ObservationDeliveryBatch batch);
}
