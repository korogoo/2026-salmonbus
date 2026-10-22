package com.gustler.backend.api.favorite.dto;

import java.util.List;
import java.util.UUID;

public record ReorderFavoritesRequest(List<UUID> favoriteIds) {
}
