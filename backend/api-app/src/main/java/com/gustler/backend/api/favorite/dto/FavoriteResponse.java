package com.gustler.backend.api.favorite.dto;

import com.gustler.backend.api.favorite.domain.Favorite;
import java.util.UUID;

public record FavoriteResponse(UUID id, String routeId, String stationId) {
    public static FavoriteResponse from(Favorite favorite) {
        return new FavoriteResponse(favorite.id().value(), favorite.target().routeId(), favorite.target().stationId());
    }
}
