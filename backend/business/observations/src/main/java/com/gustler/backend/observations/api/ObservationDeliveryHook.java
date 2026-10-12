package com.gustler.backend.observations.api;

/** 관측 변경과 같은 트랜잭션에서 전달 기록을 남긴다. */
public interface ObservationDeliveryHook {
    void record(ObservationDeliveryBatch batch);
}
