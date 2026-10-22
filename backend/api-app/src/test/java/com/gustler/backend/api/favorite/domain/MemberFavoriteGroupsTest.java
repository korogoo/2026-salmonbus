package com.gustler.backend.api.favorite.domain;

import static com.gustler.backend.api.favorite.domain.FavoriteDomainFixture.emptyGroup;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.api.favorite.FavoriteGroupLimitExceededException;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemberFavoriteGroupsTest {
    @Test
    void 즐겨찾기_그룹은_세_개까지만_만든다() {
        // given
        MemberFavoriteGroups groups = new MemberFavoriteGroups(List.of(
            emptyGroup("출근"),
            emptyGroup("퇴근"),
            emptyGroup("주말")
        ));

        // when & then
        assertThatThrownBy(() -> groups.append(emptyGroup("추가")))
            .isInstanceOf(FavoriteGroupLimitExceededException.class);
    }
}
