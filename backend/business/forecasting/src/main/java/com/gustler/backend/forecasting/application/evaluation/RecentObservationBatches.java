package com.gustler.backend.forecasting.application.evaluation;

import com.gustler.backend.forecasting.domain.evaluation.ArrivalCandidate;
import java.time.Instant;
import java.util.List;

/** 관측 본문과 분리된 최근 수집 목록. 전달 번호는 커밋 순서로 사용하지 않는다. */
public interface RecentObservationBatches {
    List<Header> headers(long version, Instant after, int limit);
    List<Row> restore(long version, List<Long> batches, int limit);
    void acknowledge(List<Long> deliveries);

    record Header(long batchId, int attempt, Instant observedAt, int rows,
                  long deliveryId, long deliveryQualityRevision, Integer turnSequence,
                  boolean pending) { }
    record Row(long batchId, ArrivalCandidate candidate) { }
}
