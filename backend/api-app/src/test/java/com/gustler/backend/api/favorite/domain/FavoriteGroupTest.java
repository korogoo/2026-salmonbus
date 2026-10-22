package com.gustler.backend.api.favorite.domain;

import static com.gustler.backend.api.favorite.domain.FavoriteDomainFixture.OTHER_TARGET;
import static com.gustler.backend.api.favorite.domain.FavoriteDomainFixture.TARGET;
import static com.gustler.backend.api.favorite.domain.FavoriteDomainFixture.THIRD_TARGET;
import static com.gustler.backend.api.favorite.domain.FavoriteDomainFixture.favoriteId;
import static com.gustler.backend.api.favorite.domain.FavoriteDomainFixture.group;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.api.favorite.FavoriteLimitExceededException;
import org.junit.jupiter.api.Test;

class FavoriteGroupTest {
    @Test
    void 즐겨찾기는_두_개까지만_등록한다() {
        // given
        FavoriteGroup group = group("출근", TARGET, OTHER_TARGET);

        // when & then
        assertThatThrownBy(() -> group.add(new Favorite(favoriteId("세번째"), THIRD_TARGET)))
            .isInstanceOf(FavoriteLimitExceededException.class);
    }

    @Test
    void 같은_노선과_정류장을_두_번_등록해도_한_개다() {
        // given
        FavoriteGroup group = group("출근", TARGET);

        // when
        FavoriteGroup actual = group.add(new Favorite(favoriteId("중복"), TARGET));

        // then
        assertThat(actual).isSameAs(group);
        assertThat(actual.favorites()).hasSize(1);
    }
}
