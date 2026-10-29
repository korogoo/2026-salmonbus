package com.gustler.backend.member.api;

import java.util.UUID;

/** 회원 삭제와 분석 기록 삭제 요청을 저장한 뒤 발행한다. */
public record MemberWithdrawn(UUID publicId) {
}
