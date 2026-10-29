package com.gustler.backend.api.account.analytics;

import com.gustler.backend.member.api.AnalyticsDeletions;
import com.gustler.backend.member.api.MemberWithdrawn;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 탈퇴 커밋 후 즉시 전송하고 실패한 삭제 요청을 다시 전송한다. */
public final class AmplitudeDeletionJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(AmplitudeDeletionJob.class);
    private final AnalyticsDeletions deletions;
    private final AmplitudeDeletionClient client;
    private final Executor executor;

    public AmplitudeDeletionJob(AnalyticsDeletions deletions, AmplitudeDeletionClient client, Executor executor) {
        this.deletions = deletions;
        this.client = client;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterWithdrawal(MemberWithdrawn event) {
        try {
            // 탈퇴 트랜잭션의 연결을 돌려주기 전에 새 DB 연결을 기다리지 않는다.
            executor.execute(() -> attempt(event.publicId()));
        } catch (RuntimeException exception) {
            LOGGER.warn("Amplitude 삭제 실행기가 바쁘거나 종료 중이다. 주기 작업에서 다시 시도한다");
        }
    }

    private void attempt(UUID publicId) {
        try {
            deletions.attempt(publicId, client::delete);
        } catch (RuntimeException exception) {
            // 응답 본문이나 ID, 인증 정보는 로그에 남기지 않는다. DB 장애가 나도 탈퇴는 끝난 상태다.
            LOGGER.error("Amplitude 삭제 결과를 기록하지 못했다. 대기 기록으로 다시 시도한다");
        }
    }

    @Scheduled(cron = "${analytics.amplitude.retry-cron:15 */5 * * * *}")
    public void retry() {
        try {
            deletions.retry(client::delete);
        } catch (RuntimeException exception) {
            LOGGER.error("Amplitude 삭제 재시도를 마치지 못했다. 다음 실행에서 다시 시도한다");
        }
    }
}
