package com.gustler.backend.api.favorite.dto;

import com.gustler.backend.api.favorite.domain.FavoriteDirection;
import java.util.List;
import java.util.UUID;

public record SaveFavoriteSelectionRequest(
    String routeId,
    String stopId,
    FavoriteDirection direction,
    List<UUID> favoriteGroupIds
) {
}
