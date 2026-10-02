package com.gustler.backend.routecatalog.domain;

public record UpstreamRouteStop(
    int stopOrder,
    String stopId,
    String name,
    StopCoordinates coordinates
) {
    public UpstreamRouteStop(int stopOrder, String stopId, String name) {
        this(stopOrder, stopId, name, null);
    }
}
