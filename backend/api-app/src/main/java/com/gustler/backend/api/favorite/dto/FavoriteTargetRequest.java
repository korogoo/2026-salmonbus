package com.gustler.backend.api.favorite.dto;

import com.gustler.backend.api.favorite.domain.FavoriteDirection;

public record FavoriteTargetRequest(String routeId, String stopId, FavoriteDirection direction) {
}
