package com.gustler.backend.collector;

import com.gustler.backend.collector.GbisRawResponse.NotReceived;
import com.gustler.backend.collector.GbisRawResponse.PortalRejected;
import com.gustler.backend.collector.GbisRawResponse.Received;
import com.gustler.backend.collector.GbisRouteResult.Failed;
import com.gustler.backend.collector.GbisRouteResult.Success;
import com.gustler.backend.collector.dto.BusRouteInfoResponse;
import com.gustler.backend.collector.dto.BusRouteInfoResponse.RouteInfoItem;
import com.gustler.backend.collector.dto.BusRouteStationResponse;
import com.gustler.backend.collector.dto.BusRouteStationResponse.RouteStationItem;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 노선 하나의 기본 정보와 경유 정류소를 상류에서 읽는다.
 *
 * <p>상류가 둘을 따로 준다. 표시명 · 기점 · 종점 · 첫차 · 막차는 노선정보에 있고, 정류소 목록과
 * <b>회차 순번</b>은 노선정류소 조회에 있다. 그래서 한 노선을 읽는 데 호출을 두 번 쓴다.
 */
@Component
public class GbisRouteSource {

    private static final Logger log = LoggerFactory.getLogger(GbisRouteSource.class);

    /** 한 노선을 읽는 데 드는 상류 호출 수. 장부에서 이만큼 자리를 잡고 부른다. */
    public static final int UPSTREAM_CALLS_PER_READ = 2;

    private static final String ROUTE_INFO_PATH = "/busrouteservice/v2/getBusRouteInfoItemv2";
    private static final String ROUTE_STATION_PATH = "/busrouteservice/v2/getBusRouteStationListv2";

    private static final int RESULT_CODE_SUCCESS = 0;

    private final GbisApiCaller caller;
    private final ObjectMapper objectMapper;

    public GbisRouteSource(
        GbisApiCaller caller,
        ObjectMapper objectMapper
    ) {
        this.caller = caller;
        this.objectMapper = objectMapper;
    }

    public GbisRouteResult read(
        final String routeId
    ) {
        RouteInfoItem routeInfo = readRouteInfo(routeId);
        if (routeInfo == null) {
            return new Failed("노선 %s 의 기본 정보를 읽지 못했다".formatted(routeId));
        }

        List<RouteStationItem> stations = readStations(routeId);
        if (stations == null || stations.isEmpty()) {
            return new Failed("노선 %s 의 경유 정류소를 읽지 못했다".formatted(routeId));
        }

        return toSuccess(routeId, routeInfo, stations);
    }

    private GbisRouteResult toSuccess(
        String routeId,
        RouteInfoItem routeInfo,
        List<RouteStationItem> stations
    ) {
        try {
            return new Success(new UpstreamRoute(
                routeId,
                routeInfo.displayName(),
                routeInfo.startStopName(),
                routeInfo.endStopName(),
                RouteStops.from(turnSequenceOf(stations), toUpstreamStops(stations)),
                timetableOf(routeInfo)));
        } catch (final IllegalArgumentException e) {
            // 순번이 겹치거나 회차 순번이 정류소 목록에 없는 응답. 판본으로 열면 뜻이 없는 노선이 된다.
            return new Failed("노선 %s 의 정류소 목록이 성립하지 않는다: %s".formatted(routeId, e.getMessage()));
        }
    }

    /**
     * 회차 순번은 정류소 목록이 준다. 노선정보 응답에는 없다.
     *
     * <p>모든 정류소 행에 같은 {@code turnSeq} 가 실려 오고, 회차하는 정류소만 {@code turnYn}
     * 이 Y 다. 둘이 어긋나거나 값이 여럿이면 회차 없음으로 본다. 잘못 고른 순번으로 판본을 열면
     * 정류소 절반의 방향이 뒤집힌 채 그럴듯하게 돌아간다.
     */
    private Integer turnSequenceOf(
        List<RouteStationItem> stations
    ) {
        Set<Integer> declared = stations.stream()
            .map(RouteStationItem::turnSequence)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (declared.size() != 1) {
            return null;
        }

        Integer turnSequence = declared.iterator().next();
        List<Integer> marked = stations.stream()
            .filter(RouteStationItem::isTurnPoint)
            .map(RouteStationItem::stopOrder)
            .toList();
        if (marked.size() == 1 && !marked.getFirst().equals(turnSequence)) {
            log.warn("회차 순번과 회차 표시가 어긋난다. 회차 없음으로 본다. turnSeq={} turnYn 순번={}",
                turnSequence, marked.getFirst());
            return null;
        }
        return turnSequence;
    }

    private List<UpstreamRouteStop> toUpstreamStops(
        List<RouteStationItem> stations
    ) {
        return stations.stream()
            .map(station -> new UpstreamRouteStop(station.stopOrder(), station.stopId(), station.name()))
            .toList();
    }

    private RouteTimetable timetableOf(
        RouteInfoItem routeInfo
    ) {
        return new RouteTimetable(
            routeInfo.upFirstDepartureTime(),
            routeInfo.upLastDepartureTime(),
            routeInfo.downFirstDepartureTime(),
            routeInfo.downLastDepartureTime());
    }

    private RouteInfoItem readRouteInfo(
        String routeId
    ) {
        BusRouteInfoResponse response = read(ROUTE_INFO_PATH, routeId, BusRouteInfoResponse.class);
        if (response == null
            || response.response() == null
            || response.response().header() == null
            || response.response().header().resultCode() != RESULT_CODE_SUCCESS
            || response.response().body() == null) {
            return null;
        }
        return response.response().body().routeInfo();
    }

    private List<RouteStationItem> readStations(
        String routeId
    ) {
        BusRouteStationResponse response = read(ROUTE_STATION_PATH, routeId, BusRouteStationResponse.class);
        if (response == null
            || response.response() == null
            || response.response().header() == null
            || response.response().header().resultCode() != RESULT_CODE_SUCCESS
            || response.response().body() == null) {
            return null;
        }
        return response.response().body().stations();
    }

    /** 응답이 안 왔거나 포털이 막았거나 읽지 못하면 비운다. 부른 쪽이 그 노선을 건너뛴다. */
    private <T> T read(
        String path,
        String routeId,
        Class<T> responseType
    ) {
        return switch (caller.get(path, routeId)) {
            case NotReceived ignored -> null;
            case PortalRejected ignored -> null;
            case Received received -> parse(received.body(), responseType);
        };
    }

    private <T> T parse(
        String body,
        Class<T> responseType
    ) {
        try {
            return objectMapper.readValue(body, responseType);
        } catch (final JacksonException e) {
            return null;
        }
    }
}
