package com.gustler.backend.api.favorite.dto;

import com.gustler.backend.api.favorite.domain.Favorite;
import com.gustler.backend.api.favorite.domain.FavoriteDirection;
import java.util.UUID;

public record FavoriteResponse(UUID id, String routeId, String stopId, FavoriteDirection direction) {
    public static FavoriteResponse from(Favorite favorite) {
        return new FavoriteResponse(favorite.id().value(), favorite.target().routeId(), favorite.target().stopId(),
            favorite.target().direction());
    }
}
