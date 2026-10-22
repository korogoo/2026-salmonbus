package com.gustler.backend.api.favorite.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.gustler.backend.api.favorite.application.FavoriteOverview.Card;
import com.gustler.backend.api.favorite.application.FavoriteOverview.CardReason;
import com.gustler.backend.api.favorite.application.FavoriteOverview.CardStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

@JsonInclude(Include.NON_NULL)
@Schema(requiredProperties = {"id", "status", "route", "stop", "approachingVehicles"})
public record FavoriteCardResponse(
    UUID id,
    @Schema(description = "즐겨찾기 카드 상태")
    CardStatus status,
    @Schema(description = "카드를 만들 수 없는 이유. READY, NO_VEHICLE이면 생략")
    CardReason reason,
    FavoriteBoardRouteResponse route,
    FavoriteStopResponse stop,
    @Schema(description = "watch와 같은 접근 차량 구조에 ETA만 추가한 목록")
    List<FavoriteApproachingVehicleResponse> approachingVehicles
) {
    public FavoriteCardResponse {
        approachingVehicles = List.copyOf(approachingVehicles);
    }

    static FavoriteCardResponse from(Card card) {
        var board = card.board();
        var vehicles = board == null ? List.<FavoriteApproachingVehicleResponse>of()
            : board.stop().approachingVehicles().stream().map(FavoriteApproachingVehicleResponse::from).toList();
        return new FavoriteCardResponse(card.id(), card.status(), card.reason(),
            FavoriteBoardRouteResponse.from(card.target().routeId(), board == null ? null : board.route()),
            FavoriteStopResponse.from(card.target().stopId(), board == null ? null : board.stop()), vehicles);
    }
}
