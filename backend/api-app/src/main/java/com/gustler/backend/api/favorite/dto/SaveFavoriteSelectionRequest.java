package com.gustler.backend.api.favorite.dto;

import java.util.List;
import java.util.UUID;

public record SaveFavoriteSelectionRequest(String routeId, String stationId, List<UUID> favoriteGroupIds) {
}
