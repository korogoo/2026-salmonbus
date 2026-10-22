package com.gustler.backend.api.favorite.controller;

import com.gustler.backend.api.account.MemberPrincipal;
import com.gustler.backend.api.favorite.InvalidFavoriteRequestException;
import com.gustler.backend.api.favorite.application.FavoriteGroupService;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.favorite.dto.CreateFavoriteGroupRequest;
import com.gustler.backend.api.favorite.dto.FavoriteGroupListResponse;
import com.gustler.backend.api.favorite.dto.FavoriteGroupResponse;
import com.gustler.backend.api.favorite.dto.FavoriteTargetRequest;
import com.gustler.backend.api.favorite.dto.RenameFavoriteGroupRequest;
import com.gustler.backend.api.favorite.dto.ReorderFavoriteGroupsRequest;
import java.net.URI;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/favorite-groups")
@ConditionalOnProperty(name = "auth.enabled", havingValue = "true")
public class FavoriteGroupController {
    private final FavoriteGroupService service;

    public FavoriteGroupController(FavoriteGroupService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<FavoriteGroupListResponse> findAll(
        @AuthenticationPrincipal MemberPrincipal principal
    ) {
        return ok(FavoriteGroupListResponse.from(service.findAll(principal.publicId())));
    }

    @PostMapping
    public ResponseEntity<FavoriteGroupResponse> create(
        @AuthenticationPrincipal MemberPrincipal principal,
        @RequestBody CreateFavoriteGroupRequest request
    ) {
        if (request == null) {
            throw new InvalidFavoriteRequestException();
        }
        FavoriteTarget initialFavorite = targetOf(request.initialFavorite());
        FavoriteGroupResponse response = FavoriteGroupResponse.from(
            service.create(principal.publicId(), request.name(), initialFavorite));
        return ResponseEntity.created(URI.create("/api/me/favorite-groups/" + response.id()))
            .cacheControl(CacheControl.noStore())
            .body(response);
    }

    @PatchMapping("/{groupId}")
    public ResponseEntity<FavoriteGroupResponse> rename(
        @AuthenticationPrincipal MemberPrincipal principal,
        @PathVariable UUID groupId,
        @RequestBody RenameFavoriteGroupRequest request
    ) {
        if (request == null) {
            throw new InvalidFavoriteRequestException();
        }
        return ok(FavoriteGroupResponse.from(service.rename(principal.publicId(), groupId, request.name())));
    }

    @DeleteMapping("/{groupId}")
    public ResponseEntity<Void> delete(
        @AuthenticationPrincipal MemberPrincipal principal,
        @PathVariable UUID groupId
    ) {
        service.delete(principal.publicId(), groupId);
        return noContent();
    }

    @PutMapping("/order")
    public ResponseEntity<Void> reorder(
        @AuthenticationPrincipal MemberPrincipal principal,
        @RequestBody ReorderFavoriteGroupsRequest request
    ) {
        if (request == null) {
            throw new InvalidFavoriteRequestException();
        }
        service.reorder(principal.publicId(), request.groupIds());
        return noContent();
    }

    private FavoriteTarget targetOf(FavoriteTargetRequest request) {
        return request == null ? null : service.targetOf(request.routeId(), request.stationId());
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static ResponseEntity<Void> noContent() {
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
