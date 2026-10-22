package com.gustler.backend.api.favorite.domain;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

final class FavoriteDomainFixture {
    static final FavoriteTarget TARGET = new FavoriteTarget("204000057", "277102322");
    static final FavoriteTarget OTHER_TARGET = new FavoriteTarget("204000057", "277102323");
    static final FavoriteTarget THIRD_TARGET = new FavoriteTarget("204000057", "277102324");

    private FavoriteDomainFixture() {
    }

    static FavoriteGroup emptyGroup(String key) {
        return new FavoriteGroup(groupId(key), List.of());
    }

    static FavoriteGroup group(String key, FavoriteTarget... targets) {
        List<Favorite> favorites = java.util.stream.IntStream.range(0, targets.length)
            .mapToObj(index -> new Favorite(favoriteId(key + "-" + index), targets[index]))
            .toList();
        return new FavoriteGroup(groupId(key), favorites);
    }

    static FavoriteGroupId groupId(String key) {
        return new FavoriteGroupId(uuid("group-" + key));
    }

    static FavoriteId favoriteId(String key) {
        return new FavoriteId(uuid("favorite-" + key));
    }

    private static UUID uuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
