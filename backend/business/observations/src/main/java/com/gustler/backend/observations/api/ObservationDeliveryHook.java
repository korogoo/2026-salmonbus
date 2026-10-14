package com.gustler.backend.observations.api;

/** 관측 변경 트랜잭션에 커밋 후 캐시 반영을 등록한다. 별도 전달 기록은 저장하지 않는다. */
public interface ObservationDeliveryHook {
    void record(ObservationDeliveryBatch batch);
}
