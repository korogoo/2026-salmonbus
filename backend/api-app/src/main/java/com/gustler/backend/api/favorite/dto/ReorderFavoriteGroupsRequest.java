package com.gustler.backend.api.favorite.dto;

import java.util.List;
import java.util.UUID;

public record ReorderFavoriteGroupsRequest(List<UUID> groupIds) {
}
