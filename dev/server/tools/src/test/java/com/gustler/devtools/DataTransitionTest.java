package com.gustler.devtools;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DataTransitionTest {
    private final Instant now = Instant.parse("2026-10-16T00:00:00Z");
    private final RevisionStore.Revision previous = revision("a");
    private final RevisionStore.Revision next = revision("b");

    private RevisionStore.Revision revision(String key) {
        return new RevisionStore.Revision(1, "dev", "basis", key.repeat(40), key.repeat(64),
                key.repeat(64), false, Map.of(), key.repeat(64));
    }

    @Test
    void 확인한_두_배포판과_유효한_백업_식별자가_있어야_승인한다() {
        // given
        var approval = new DataTransition.Approval(previous.releaseId(), next.releaseId(),
                "dev-before-sal175", now.plusSeconds(900));

        // when & then
        assertDoesNotThrow(() -> approval.verify(previous, next, now));
        assertThrows(IllegalArgumentException.class, () -> approval.verify(previous, revision("c"), now));
        assertThrows(IllegalArgumentException.class, () -> approval.verify(revision("c"), next, now));
    }

    @Test
    void 만료됐거나_너무_오래_유효한_승인은_사용하지_않는다() {
        // given
        var expired = new DataTransition.Approval(previous.releaseId(), next.releaseId(), "backup", now);
        var tooLong = new DataTransition.Approval(previous.releaseId(), next.releaseId(), "backup", now.plusSeconds(1801));

        // when & then
        assertThrows(IllegalArgumentException.class, () -> expired.verify(previous, next, now));
        assertThrows(IllegalArgumentException.class, () -> tooLong.verify(previous, next, now));
    }

    @Test
    void 백업_식별자가_없거나_SQL_문자가_섞이면_승인하지_않는다() {
        // given
        var empty = new DataTransition.Approval(previous.releaseId(), next.releaseId(), "", now.plusSeconds(900));
        var malformed = new DataTransition.Approval(previous.releaseId(), next.releaseId(), "backup';", now.plusSeconds(900));

        // when & then
        assertThrows(IllegalArgumentException.class, () -> empty.verify(previous, next, now));
        assertThrows(IllegalArgumentException.class, () -> malformed.verify(previous, next, now));
    }
}
