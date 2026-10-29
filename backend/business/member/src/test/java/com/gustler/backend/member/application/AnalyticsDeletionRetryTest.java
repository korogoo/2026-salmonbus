package com.gustler.backend.member.application;

import static org.mockito.Mockito.*;

import com.gustler.backend.member.domain.AnalyticsDeletionRepository;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class AnalyticsDeletionRetryTest {
    private final AnalyticsDeletionRepository repository = mock(AnalyticsDeletionRepository.class);
    private final AnalyticsDeletionRetry retry = new AnalyticsDeletionRetry(repository);
    private final UUID id = UUID.randomUUID();
    @SuppressWarnings("unchecked")
    private final Consumer<List<UUID>> send = mock(Consumer.class);

    @Test
    void 전송이_성공하면_공개_ID를_지운다() {
        when(repository.lock(id)).thenReturn(true);
        retry.attempt(id, send);
        verify(send).accept(List.of(id));
        verify(repository).delete(id);
        verify(repository, never()).failed(any());
    }

    @Test
    void 실패하면_시도_횟수만_늘리고_대기_기록을_유지한다() {
        when(repository.lock(id)).thenReturn(true);
        doThrow(new IllegalStateException()).when(send).accept(any());
        retry.attempt(id, send);
        verify(repository).failed(id);
        verify(repository, never()).delete(any());
    }

    @Test
    void 다른_서버가_잠근_요청이나_없는_요청은_전송하지_않는다() {
        retry.attempt(id, send);
        verifyNoInteractions(send);
        verify(repository, never()).delete(any());
    }

    @Test
    void 재시도는_최대_100명을_한_요청으로_보낸다() {
        UUID other = UUID.randomUUID();
        when(repository.lockPending(100)).thenReturn(List.of(id, other));
        retry.retry(send);
        verify(send).accept(List.of(id, other));
        verify(repository).delete(id);
        verify(repository).delete(other);
    }

    @Test
    void 묶음이_실패하면_모든_요청을_남긴다() {
        UUID other = UUID.randomUUID();
        when(repository.lockPending(100)).thenReturn(List.of(id, other));
        doThrow(new IllegalStateException()).when(send).accept(any());
        retry.retry(send);
        verify(repository).failed(id);
        verify(repository).failed(other);
        verify(repository, never()).delete(any());
    }

    @Test
    void 대기_기록이_없으면_외부_API를_호출하지_않는다() {
        when(repository.lockPending(100)).thenReturn(List.of());
        retry.retry(send);
        verifyNoInteractions(send);
    }
}
