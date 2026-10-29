package com.gustler.backend.api.account.analytics;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.gustler.backend.member.api.AnalyticsDeletions;
import com.gustler.backend.member.api.MemberWithdrawn;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AmplitudeDeletionJobTest {
    private final AnalyticsDeletions deletions = mock(AnalyticsDeletions.class);
    private final AmplitudeDeletionClient client = mock(AmplitudeDeletionClient.class);
    private final AmplitudeDeletionJob job = new AmplitudeDeletionJob(deletions, client, Runnable::run);

    @Test
    void 커밋한_회원의_공개_ID로_즉시_시도한다() {
        UUID id = UUID.randomUUID();
        job.afterWithdrawal(new MemberWithdrawn(id));
        verify(deletions).attempt(eq(id), any());
    }

    @Test
    void 결과_저장에_실패해도_탈퇴_응답에는_예외를_보내지_않는다() {
        doThrow(new IllegalStateException()).when(deletions).attempt(any(), any());
        assertThatCode(() -> job.afterWithdrawal(new MemberWithdrawn(UUID.randomUUID())))
            .doesNotThrowAnyException();
    }

    @Test
    void 탈퇴_요청은_전송_작업을_등록하고_완료를_기다리지_않는다() {
        java.util.concurrent.atomic.AtomicReference<Runnable> queued = new java.util.concurrent.atomic.AtomicReference<>();
        AmplitudeDeletionJob async = new AmplitudeDeletionJob(deletions, client, queued::set);
        UUID id = UUID.randomUUID();
        async.afterWithdrawal(new MemberWithdrawn(id));
        verifyNoInteractions(deletions);
        queued.get().run();
        verify(deletions).attempt(eq(id), any());
    }

    @Test
    void 실행기가_요청을_받지_못해도_탈퇴_응답은_성공한다() {
        AmplitudeDeletionJob busy = new AmplitudeDeletionJob(deletions, client,
            task -> { throw new java.util.concurrent.RejectedExecutionException(); });
        assertThatCode(() -> busy.afterWithdrawal(new MemberWithdrawn(UUID.randomUUID())))
            .doesNotThrowAnyException();
        verifyNoInteractions(deletions);
    }

    @Test
    void 주기_작업이_남은_요청을_다시_시도한다() {
        job.retry();
        verify(deletions).retry(any());
    }
}
