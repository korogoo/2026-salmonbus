package com.gustler.backend.api.favorite.domain;

import com.gustler.backend.api.favorite.FavoriteGroupLimitExceededException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/** 현재 회원이 소유한 즐겨찾기 그룹의 순서와 개수 규칙이다. */
public final class MemberFavoriteGroups {
    public static final int MAX_GROUPS = 3;

    private final List<FavoriteGroup> values;

    public MemberFavoriteGroups(
        List<FavoriteGroup> values
    ) {
        this.values = List.copyOf(Objects.requireNonNull(values, "즐겨찾기 그룹 목록은 null일 수 없습니다."));
        requireWithinLimit();
        requireUniqueIds();
        requireUniqueNames();
    }

    public List<FavoriteGroup> values() {
        return values;
    }

    public MemberFavoriteGroups append(
        FavoriteGroup group
    ) {
        Objects.requireNonNull(group, "즐겨찾기 그룹은 null일 수 없습니다.");
        if (values.size() == MAX_GROUPS) {
            throw new FavoriteGroupLimitExceededException();
        }
        return new MemberFavoriteGroups(Stream.concat(values.stream(), Stream.of(group)).toList());
    }

    public Optional<FavoriteGroup> find(
        FavoriteGroupId id
    ) {
        return values.stream()
            .filter(group -> group.id().equals(id))
            .findFirst();
    }

    private void requireWithinLimit() {
        if (values.size() > MAX_GROUPS) {
            throw new FavoriteGroupLimitExceededException();
        }
    }

    private void requireUniqueIds() {
        List<FavoriteGroupId> ids = values.stream()
            .map(FavoriteGroup::id)
            .toList();
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException("즐겨찾기 그룹 ID는 중복될 수 없습니다.");
        }
    }

    private void requireUniqueNames() {
        List<FavoriteGroupName> names = values.stream()
            .map(FavoriteGroup::name)
            .toList();
        if (new HashSet<>(names).size() != names.size()) {
            throw new IllegalArgumentException("같은 회원의 즐겨찾기 그룹 이름은 중복될 수 없습니다.");
        }
    }
}
