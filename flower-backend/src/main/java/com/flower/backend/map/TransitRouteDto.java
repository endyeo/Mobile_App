package com.flower.backend.map;

import java.util.List;

public final class TransitRouteDto {

    private TransitRouteDto() {
    }

    public record TransitRouteRequest(
            double startLat,
            double startLng,
            double endLat,
            double endLng
    ) {
    }

    public record RouteRequest(
            double startLat,
            double startLng,
            double endLat,
            double endLng,
            String mode
    ) {
    }

    public record RouteResponse(
            String mode,
            Summary summary,
            List<Leg> legs
    ) {
    }

    public record TransitRouteResponse(
            Summary summary,
            List<Leg> legs
    ) {
    }

    public record Summary(
            Integer totalTimeSec,
            Integer totalDistanceM,
            Integer totalWalkTimeSec,
            Integer totalWalkDistanceM,
            Integer transferCount,
            Integer totalFare
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
