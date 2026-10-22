package com.gustler.backend.api.favorite.controller;

import com.gustler.backend.api.account.MemberPrincipal;
import com.gustler.backend.api.docs.ApiDocsTags;
import com.gustler.backend.api.favorite.InvalidFavoriteRequestException;
import com.gustler.backend.api.favorite.application.FavoriteGroupService;
import com.gustler.backend.api.favorite.application.FavoriteSelectionService;
import com.gustler.backend.api.favorite.dto.ReorderFavoritesRequest;
import com.gustler.backend.api.favorite.dto.SaveFavoriteSelectionRequest;
import java.util.UUID;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = ApiDocsTags.FAVORITES)
@RequestMapping("/api/me")
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
public class FavoriteController {
    private final FavoriteGroupService groups;
    private final FavoriteSelectionService selections;

    public FavoriteController(FavoriteGroupService groups, FavoriteSelectionService selections) {
        this.groups = groups;
        this.selections = selections;
    }

    @PutMapping("/favorite-selections")
    public ResponseEntity<Void> select(
        @AuthenticationPrincipal MemberPrincipal principal,
        @RequestBody SaveFavoriteSelectionRequest request
    ) {
        if (request == null) {
            throw new InvalidFavoriteRequestException();
        }
        selections.select(principal.publicId(),
            groups.targetOf(request.routeId(), request.stopId(), request.direction()), request.favoriteGroupIds());
        return noContent();
    }

    @DeleteMapping("/favorite-groups/{groupId}/favorites/{favoriteId}")
    public ResponseEntity<Void> delete(
        @AuthenticationPrincipal MemberPrincipal principal,
        @PathVariable UUID groupId,
        @PathVariable UUID favoriteId
    ) {
        selections.delete(principal.publicId(), groupId, favoriteId);
        return noContent();
    }

    @PutMapping("/favorite-groups/{groupId}/favorites/order")
    public ResponseEntity<Void> reorder(
        @AuthenticationPrincipal MemberPrincipal principal,
        @PathVariable UUID groupId,
        @RequestBody ReorderFavoritesRequest request
    ) {
        if (request == null) {
            throw new InvalidFavoriteRequestException();
        }
        selections.reorder(principal.publicId(), groupId, request.favoriteIds());
        return noContent();
    }

    private static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
