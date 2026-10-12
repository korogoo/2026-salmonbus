package com.gustler.backend.forecasting.infrastructure.observations;

import com.gustler.backend.forecasting.application.evaluation.ObservationDeliveryReceiver;
import com.gustler.backend.forecasting.application.evaluation.ObservationDeliveryStore;
import com.gustler.backend.observations.api.ObservationDeliveryBatch;
import com.gustler.backend.observations.api.ObservationDeliveryHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@ConditionalOnProperty(prefix = "forecast.evaluation.observation-cache", name = "enabled", havingValue = "true")
public class ObservationDeliveryAdapter implements ObservationDeliveryHook {
    private static final Logger log = LoggerFactory.getLogger(ObservationDeliveryAdapter.class);
    private final ObservationDeliveryStore store;
    private final ObjectProvider<ObservationDeliveryReceiver> receivers;

    public ObservationDeliveryAdapter(ObservationDeliveryStore store,
        ObjectProvider<ObservationDeliveryReceiver> receivers) {
        this.store = store;
        this.receivers = receivers;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(ObservationDeliveryBatch batch) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("관측 전달에는 트랜잭션 동기화가 필요하다");
        }
        long id = store.append(batch.batchId(), batch.routeVersionId(), batch.attempt(), batch.kind().name());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deliver(id, batch);
            }
        });
    }

    private void deliver(long id, ObservationDeliveryBatch batch) {
        try {
            ObservationDeliveryReceiver receiver = receivers.getIfAvailable();
            if (receiver != null) receiver.receive(id, batch);
            // 완료 기록은 다음 처리 회차에서 반영한다. 콜백 안에서 DB 연결을 더 빌리지 않는다.
        } catch (RuntimeException failure) {
            // 이미 커밋한 수집을 실패로 바꾸지 않는다. 기록은 미전달 상태로 남겨 재시도한다.
            log.warn("event=observation_delivery_failed deliveryId={} batchId={} exceptionType={}",
                id, batch.batchId(), failure.getClass().getSimpleName());
        }
    }
}
