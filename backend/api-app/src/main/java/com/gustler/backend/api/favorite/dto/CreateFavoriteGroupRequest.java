package com.gustler.backend.api.favorite.dto;

public record CreateFavoriteGroupRequest(String name, FavoriteTargetRequest initialFavorite) {
}
