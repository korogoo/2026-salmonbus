package com.gustler.backend.api.favorite.application;

import com.gustler.backend.api.arrival.ArrivalQuery;
import com.gustler.backend.api.arrival.application.ArrivalQueryService;
import com.gustler.backend.api.arrival.application.StationArrivalsOverview;
import com.gustler.backend.api.arrival.domain.ArrivalPrediction;
import com.gustler.backend.api.arrival.domain.RouteArrivals;
import com.gustler.backend.api.board.application.BoardOverview;
import com.gustler.backend.api.board.application.BoardQueryService;
import com.gustler.backend.api.board.domain.ApproachingVehicle;
import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.board.domain.StopState;
import com.gustler.backend.api.error.ApiException;
import com.gustler.backend.api.error.ErrorCode;
import com.gustler.backend.api.error.UnauthenticatedException;
import com.gustler.backend.api.favorite.application.FavoriteOverview.BoardSlice;
import com.gustler.backend.api.favorite.application.FavoriteOverview.Card;
import com.gustler.backend.api.favorite.application.FavoriteOverview.CardReason;
import com.gustler.backend.api.favorite.application.FavoriteOverview.CardStatus;
import com.gustler.backend.api.favorite.application.FavoriteOverview.Eta;
import com.gustler.backend.api.favorite.application.FavoriteOverview.EtaReason;
import com.gustler.backend.api.favorite.application.FavoriteOverview.Group;
import com.gustler.backend.api.favorite.application.FavoriteOverview.Stop;
import com.gustler.backend.api.favorite.application.FavoriteOverview.Vehicle;
import com.gustler.backend.api.favorite.domain.Favorite;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.domain.RouteStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class FavoriteOverviewService {
    private static final Logger log = LoggerFactory.getLogger(FavoriteOverviewService.class);
    private final FavoriteGroupRepository repository;
    private final BoardQueryService boards;
    private final ArrivalQueryService arrivals;

    public FavoriteOverviewService(FavoriteGroupRepository repository, BoardQueryService boards,
        ArrivalQueryService arrivals) {
        this.repository = repository;
        this.boards = boards;
        this.arrivals = arrivals;
    }

    public FavoriteOverview get(UUID memberPublicId) {
        var owned = repository.findByMemberPublicId(memberPublicId).orElseThrow(UnauthenticatedException::new);
        Map<String, BoardLoad> loadedBoards = new HashMap<>();
        Map<String, Optional<StationArrivalsOverview>> loadedArrivals = new HashMap<>();
        List<Group> groups = owned.values().stream()
            .map(group -> new Group(group.id().value(), group.name().value(), group.favorites().stream()
                .map(favorite -> card(favorite, loadedBoards, loadedArrivals))
                .toList()))
            .toList();
        return new FavoriteOverview(groups);
    }

    private Card card(Favorite favorite, Map<String, BoardLoad> loadedBoards,
        Map<String, Optional<StationArrivalsOverview>> loadedArrivals) {
        String routeId = favorite.target().routeId();
        String stationId = favorite.target().stationId();
        BoardLoad load = loadedBoards.computeIfAbsent(routeId, this::loadBoard);
        if (load.board() == null) {
            return unavailable(favorite, load.status(), load.reason());
        }
        Board board = load.board();
        List<StopState> matchingStops = board.stops().stream()
            .filter(StopState::boardingAllowed)
            .filter(stop -> stop.stopId().equals(stationId))
            .toList();
        if (matchingStops.isEmpty()) {
            return unavailable(favorite, CardStatus.UNAVAILABLE, CardReason.TARGET_NOT_IN_CURRENT_ROUTE);
        }
        if (matchingStops.size() > 1) {
            return unavailable(favorite, CardStatus.AMBIGUOUS, CardReason.MULTIPLE_CURRENT_VISITS);
        }
        StopState stop = matchingStops.getFirst();
        List<ApproachingVehicle> approaching = stop.approachingVehicles().stream().limit(3).toList();
        CardStatus status = board.route().status() == RouteStatus.PREPARING
            ? CardStatus.PREPARING : approaching.isEmpty() ? CardStatus.NO_VEHICLE : CardStatus.READY;
        List<Vehicle> vehicles = vehicles(board, stop, approaching, loadedArrivals);
        Stop selectedStop = new Stop(stop.stopId(), stop.name(), stop.direction(), vehicles);
        BoardSlice slice = new BoardSlice(board.route(), selectedStop);
        return new Card(favorite.id().value(), favorite.target(), status, null, slice);
    }

    private List<Vehicle> vehicles(Board board, StopState stop, List<ApproachingVehicle> approaching,
        Map<String, Optional<StationArrivalsOverview>> loadedArrivals) {
        if (approaching.isEmpty()) {
            return List.of();
        }
        String key = board.route().id() + ":" + stop.stopId() + ":" + stop.sequence();
        StationArrivalsOverview arrival = loadedArrivals
            .computeIfAbsent(key, ignored -> Optional.ofNullable(arrival(board, stop)))
            .orElse(null);
        return java.util.stream.IntStream.range(0, approaching.size())
            .mapToObj(index -> vehicle(approaching.get(index), index, arrival, board.route().id(), stop.sequence()))
            .toList();
    }

    private StationArrivalsOverview arrival(Board board, StopState stop) {
        try {
            return arrivals.get(new ArrivalQuery(stop.stopId(), board.route().id(),
                Long.parseLong(board.route().referenceVersionId()), stop.sequence()));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Vehicle vehicle(ApproachingVehicle source, int index, StationArrivalsOverview arrival,
        String routeId, int stopSequence) {
        Eta eta = index >= 2 ? Eta.unavailable(EtaReason.SOURCE_NOT_PROVIDED)
            : eta(source.vehicleId(), arrival, routeId, stopSequence);
        return new Vehicle(source, eta);
    }

    private Eta eta(String vehicleId, StationArrivalsOverview overview, String routeId, int stopSequence) {
        if (overview == null) {
            return Eta.unavailable(EtaReason.ARRIVAL_UNAVAILABLE);
        }
        ArrivalPrediction prediction = overview.routes().stream()
            .filter(route -> route.routeId().equals(routeId))
            .filter(route -> route.targetVisit() != null && route.targetVisit().stopSequence() == stopSequence)
            .map(RouteArrivals::arrivals)
            .flatMap(List::stream)
            .filter(candidate -> vehicleId != null && vehicleId.equals(candidate.source().vehicleId()))
            .findFirst()
            .orElse(null);
        if (prediction == null) {
            return Eta.unavailable(EtaReason.VEHICLE_NOT_MATCHED);
        }
        if (!prediction.available()) {
            return Eta.unavailable(EtaReason.PREDICTION_ELAPSED);
        }
        return Eta.available(prediction.expectedArrivalAt(), prediction.remainingSeconds());
    }

    private BoardLoad loadBoard(String routeId) {
        try {
            BoardOverview overview = boards.getBoard(new RouteId(routeId));
            return new BoardLoad(overview.board(), null, null);
        } catch (ApiException exception) {
            if (exception.code() == ErrorCode.ROUTE_NOT_FOUND) {
                return new BoardLoad(null, CardStatus.UNAVAILABLE, CardReason.TARGET_NOT_IN_CURRENT_ROUTE);
            }
            if (exception.code() == ErrorCode.MODEL_OUT_OF_SCOPE) {
                return new BoardLoad(null, CardStatus.PREPARING, CardReason.MODEL_NOT_READY);
            }
            if (exception.code() == ErrorCode.NO_RECENT_OBSERVATION) {
                return new BoardLoad(null, CardStatus.UNAVAILABLE, CardReason.OBSERVATION_UNAVAILABLE);
            }
            return new BoardLoad(null, CardStatus.ERROR, CardReason.BOARD_UNAVAILABLE);
        } catch (RuntimeException exception) {
            log.warn("즐겨찾기 판정보드 조회에 실패했습니다. routeId={}", routeId, exception);
            return new BoardLoad(null, CardStatus.ERROR, CardReason.BOARD_UNAVAILABLE);
        }
    }

    private static Card unavailable(Favorite favorite, CardStatus status, CardReason reason) {
        return new Card(favorite.id().value(), favorite.target(), status, reason, null);
    }

    private record BoardLoad(Board board, CardStatus status, CardReason reason) {
    }
}
