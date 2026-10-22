package com.gustler.backend.api.favorite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.gustler.backend.api.board.domain.BoardRoute;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(Include.NON_NULL)
@Schema(requiredProperties = "id")
public record FavoriteBoardRouteResponse(
    @Schema(description = "watch와 같은 9자리 GBIS 노선 ID")
    String id,
    @Schema(description = "watch와 같은 화면 표시용 노선 이름")
    String displayName
) {
    static FavoriteBoardRouteResponse from(String routeId, BoardRoute route) {
        return new FavoriteBoardRouteResponse(routeId, route == null ? null : route.displayName());
    }
}
