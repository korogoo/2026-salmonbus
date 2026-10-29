package com.gustler.backend.member.domain;

import java.util.List;
import java.util.UUID;

public interface AnalyticsDeletionRepository {
    void save(UUID publicId);

    List<UUID> lockPending(int limit);

    boolean lock(UUID publicId);

    void failed(UUID publicId);

    void delete(UUID publicId);
}
