package com.flower.backend.map;

import java.util.List;

public final class TransitRouteDto {

    private TransitRouteDto() {
    }

    public record TransitRouteRequest(
            double startLat,
            double startLng,
            double endLat,
            double endLng,
            String mode
    ) {
    }

    public record TransitRouteResponse(
            String mode,
            Summary summary,
            List<Leg> legs
    ) {
    }

    public record Summary(
            int totalTimeSec,
            int totalDistanceM,
            int totalWalkTimeSec,
            int totalWalkDistanceM,
            int transferCount,
            int totalFare
    ) {
    }

    public record Leg(
            String mode,
            String route,
            String routeColor,
            String startName,
            String endName,
            int distanceM,
            int durationSec,
            int stationCount,
            List<String> instructions,
            List<Point> polyline,
            List<Station> stations
    ) {
    }

    public record Point(
            double lat,
            double lng
    ) {
    }

    public record Station(
            String name,
            double lat,
            double lng
    ) {
    }
}
