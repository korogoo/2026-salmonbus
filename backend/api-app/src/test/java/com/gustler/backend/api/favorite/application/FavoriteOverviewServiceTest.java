package com.gustler.backend.api.favorite.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gustler.backend.api.arrival.application.ArrivalQueryService;
import com.gustler.backend.api.board.application.BoardOverview;
import com.gustler.backend.api.board.application.BoardQueryService;
import com.gustler.backend.api.board.domain.ApproachingVehicle;
import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.board.domain.BoardDirection;
import com.gustler.backend.api.board.domain.BoardRoute;
import com.gustler.backend.api.board.domain.ForecastModel;
import com.gustler.backend.api.board.domain.RouteHeadways;
import com.gustler.backend.api.board.domain.StopState;
import com.gustler.backend.api.board.domain.VehicleForecast;
import com.gustler.backend.api.favorite.domain.Favorite;
import com.gustler.backend.api.favorite.domain.FavoriteGroup;
import com.gustler.backend.api.favorite.domain.FavoriteGroupId;
import com.gustler.backend.api.favorite.domain.FavoriteGroupName;
import com.gustler.backend.api.favorite.domain.FavoriteId;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.favorite.domain.MemberFavoriteGroups;
import com.gustler.backend.api.favorite.application.FavoriteOverview.CardStatus;
import com.gustler.backend.api.favorite.application.FavoriteOverview.EtaReason;
import com.gustler.backend.api.route.domain.RouteStatus;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class FavoriteOverviewServiceTest {
    private static final UUID MEMBER_ID = UUID.randomUUID();
    private static final String ROUTE_ID = "204000057";
    private static final String STATION_ID = "277102322";
    private final MemoryRepository repository = new MemoryRepository();
    private final BoardQueryService boards = mock(BoardQueryService.class);
    private final ArrivalQueryService arrivals = mock(ArrivalQueryService.class);
    private final FavoriteOverviewService service = new FavoriteOverviewService(repository, boards, arrivals);

    @Test
    void 그룹과_즐겨찾기_순서대로_최대_세_대의_카드를_만든다() {
        repository.groups = groups(favorite(ROUTE_ID, STATION_ID));
        when(boards.getBoard(any())).thenReturn(new BoardOverview(board(ROUTE_ID, STATION_ID, 4), Duration.ZERO));

        FavoriteOverview.Card card = service.get(MEMBER_ID).groups().getFirst().favorites().getFirst();

        assertThat(card.status()).isEqualTo(CardStatus.READY);
        assertThat(card.target()).isEqualTo(new FavoriteTarget(ROUTE_ID, STATION_ID));
        assertThat(card.board().stop().stopId()).isEqualTo(STATION_ID);
        assertThat(card.board().stop().approachingVehicles()).hasSize(3);
        assertThat(card.board().stop().approachingVehicles().getFirst().watch().horizonStops()).isEqualTo(1);
        assertThat(card.board().stop().approachingVehicles().get(2).eta().reason())
            .isEqualTo(EtaReason.SOURCE_NOT_PROVIDED);
    }

    @Test
    void 현재_방문이_여러_개면_카드를_AMBIGUOUS로_유지한다() {
        repository.groups = groups(favorite(ROUTE_ID, STATION_ID));
        Board board = boardWithStops(ROUTE_ID, List.of(stop(STATION_ID, 1, 0), stop(STATION_ID, 8, 0)));
        when(boards.getBoard(any())).thenReturn(new BoardOverview(board, Duration.ZERO));

        FavoriteOverview.Card card = service.get(MEMBER_ID).groups().getFirst().favorites().getFirst();

        assertThat(card.status()).isEqualTo(CardStatus.AMBIGUOUS);
        assertThat(card.board()).isNull();
    }

    @Test
    void 한_카드_조회_실패가_다른_카드를_숨기지_않는다() {
        Favorite broken = favorite(ROUTE_ID, STATION_ID);
        Favorite available = favorite("204000058", "277102323");
        repository.groups = groups(broken, available);
        when(boards.getBoard(any())).thenAnswer(invocation -> {
            String routeId = invocation.getArgument(0, com.gustler.backend.api.route.RouteId.class).value();
            if (routeId.equals(ROUTE_ID)) {
                throw new IllegalStateException("조회 실패");
            }
            return new BoardOverview(board(routeId, available.target().stationId(), 1), Duration.ZERO);
        });

        List<FavoriteOverview.Card> cards = service.get(MEMBER_ID).groups().getFirst().favorites();

        assertThat(cards).extracting(FavoriteOverview.Card::status)
            .containsExactly(CardStatus.ERROR, CardStatus.READY);
    }

    private static MemberFavoriteGroups groups(Favorite... favorites) {
        return new MemberFavoriteGroups(List.of(new FavoriteGroup(new FavoriteGroupId(UUID.randomUUID()),
            new FavoriteGroupName("출근"), List.of(favorites))));
    }

    private static Favorite favorite(String routeId, String stationId) {
        return new Favorite(new FavoriteId(UUID.randomUUID()), new FavoriteTarget(routeId, stationId));
    }

    private static Board board(String routeId, String stationId, int vehicleCount) {
        return boardWithStops(routeId, List.of(stop(stationId, 1, vehicleCount)));
    }

    private static Board boardWithStops(String routeId, List<StopState> stops) {
        OffsetDateTime now = OffsetDateTime.now();
        BoardRoute route = new BoardRoute(routeId, "노선", "기점", "종점", RouteStatus.FORECAST_READY,
            null, List.of(), "1", RouteHeadways.none());
        return new Board(route, now, now.plusMinutes(1), new ForecastModel(1, "model", now), 3, stops);
    }

    private static StopState stop(String stationId, int sequence, int vehicleCount) {
        List<ApproachingVehicle> vehicles = java.util.stream.IntStream.range(0, vehicleCount)
            .mapToObj(index -> new ApproachingVehicle("vehicle-" + index, index + 1,
                new VehicleForecast.Available(0.8, 12.0)))
            .toList();
        return new StopState(sequence, stationId, "강남역", BoardDirection.UP, true, vehicles);
    }

    private static final class MemoryRepository implements FavoriteGroupRepository {
        private MemberFavoriteGroups groups = new MemberFavoriteGroups(List.of());

        @Override
        public Optional<MemberFavoriteGroups> findByMemberPublicId(UUID memberPublicId) {
            return Optional.of(groups);
        }

        @Override
        public Optional<MemberFavoriteGroups> update(UUID memberPublicId, UnaryOperator<MemberFavoriteGroups> change) {
            throw new UnsupportedOperationException();
        }
    }
}
