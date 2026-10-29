package com.gustler.backend.member.application;

import com.gustler.backend.member.api.AnalyticsDeletions;
import com.gustler.backend.member.domain.AnalyticsDeletionRepository;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AnalyticsDeletionRetry implements AnalyticsDeletions {
    private static final Logger LOGGER = LoggerFactory.getLogger(AnalyticsDeletionRetry.class);
    private final AnalyticsDeletionRepository repository;

    public AnalyticsDeletionRetry(AnalyticsDeletionRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void retry(Consumer<List<UUID>> send) {
        send(repository.lockPending(100), send);
    }

    /**
     * 탈퇴 트랜잭션과 분리한다. API 호출 동안 행 잠금을 유지해 여러 서버의 중복 전송을 막는다.
     * 호출자는 연결 3초, 응답 5초로 제한하며, 한 번에 최대 100행을 잠그고 한 요청으로 보낸다.
     * 전송 후 DB 커밋 전에 서버가 종료되면 같은 ID를 다시 요청할 수 있다.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void attempt(UUID publicId, Consumer<List<UUID>> send) {
        if (!repository.lock(publicId)) {
            return;
        }
        send(List.of(publicId), send);
    }

    private void send(List<UUID> publicIds, Consumer<List<UUID>> send) {
        if (publicIds.isEmpty()) {
            return;
        }
        try {
            send.accept(publicIds);
        } catch (RuntimeException exception) {
            publicIds.forEach(repository::failed);
            LOGGER.warn("Amplitude 삭제 요청에 실패했다. 대기 기록을 유지한다. count={} reason={}",
                publicIds.size(), exception.getClass().getSimpleName());
            return;
        }
        publicIds.forEach(repository::delete);
    }
}
