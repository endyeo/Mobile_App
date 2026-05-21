package com.flower.backend.map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TransitRouteService {

    private static final String TRANSIT_ROUTE_URL = "https://apis.openapi.sk.com/transit/routes";
    private static final String PEDESTRIAN_ROUTE_URL = "https://apis.openapi.sk.com/tmap/routes/pedestrian?version=1";
    private static final String CAR_ROUTE_URL = "https://apis.openapi.sk.com/tmap/routes?version=1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RestTemplate restTemplate;

    @Value("${tmap.transit.app-key:}")
    private String tmapTransitAppKey;

    public TransitRouteDto.RouteResponse getRoute(TransitRouteDto.RouteRequest request) {
        String mode = normalizeRequestMode(request.mode());
        return switch (mode) {
            case "transit" -> {
                TransitRouteDto.TransitRouteResponse transitRoute = getTransitRoute(
                        new TransitRouteDto.TransitRouteRequest(
                                request.startLat(),
                                request.startLng(),
                                request.endLat(),
                                request.endLng()
                        )
                );
                yield new TransitRouteDto.RouteResponse("transit", transitRoute.summary(), transitRoute.legs());
            }
            case "car" -> fetchTmapGeoJsonRoute(request, "car", CAR_ROUTE_URL);
            default -> fetchTmapGeoJsonRoute(request, "walk", PEDESTRIAN_ROUTE_URL);
        };
    }

    public TransitRouteDto.TransitRouteResponse getTransitRoute(TransitRouteDto.TransitRouteRequest request) {
        if (tmapTransitAppKey == null || tmapTransitAppKey.isBlank()) {
            throw new IllegalStateException("TMAP transit app key is not configured.");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("appKey", tmapTransitAppKey);

        Map<String, Object> payload = Map.of(
                "startX", Double.toString(request.startLng()),
                "startY", Double.toString(request.startLat()),
                "endX", Double.toString(request.endLng()),
                "endY", Double.toString(request.endLat()),
                "count", 1,
                "lang", 0,
                "format", "json"
        );

        // 응답을 바이트로 받아 UTF-8로 명시 변환 (인코딩 오류 방지)
        ResponseEntity<byte[]> response = restTemplate.exchange(
                TRANSIT_ROUTE_URL,
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                byte[].class
        );

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("TMAP transit request failed: " + response.getStatusCode());
        }

        String body = new String(response.getBody(), StandardCharsets.UTF_8);
        return parseTransitRoute(body);
    }

    private TransitRouteDto.RouteResponse fetchTmapGeoJsonRoute(
            TransitRouteDto.RouteRequest request,
            String mode,
            String url
    ) {
        if (tmapTransitAppKey == null || tmapTransitAppKey.isBlank()) {
            throw new IllegalStateException("TMAP route app key is not configured.");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("appKey", tmapTransitAppKey);

        Map<String, Object> payload = "car".equals(mode)
                ? Map.ofEntries(
                        Map.entry("startX", request.startLng()),
                        Map.entry("startY", request.startLat()),
                        Map.entry("endX", request.endLng()),
                        Map.entry("endY", request.endLat()),
                        Map.entry("reqCoordType", "WGS84GEO"),
                        Map.entry("resCoordType", "WGS84GEO"),
                        Map.entry("sort", "index"),
                        Map.entry("carType", 0),
                        Map.entry("endRpFlag", "G")
                )
                : Map.ofEntries(
                        Map.entry("startX", request.startLng()),
                        Map.entry("startY", request.startLat()),
                        Map.entry("endX", request.endLng()),
                        Map.entry("endY", request.endLat()),
                        Map.entry("reqCoordType", "WGS84GEO"),
                        Map.entry("resCoordType", "WGS84GEO"),
                        Map.entry("sort", "index"),
                        Map.entry("startName", "현재 위치"),
                        Map.entry("endName", "도착지"),
                        Map.entry("searchOption", "0")
                );

        ResponseEntity<byte[]> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                new HttpEntity<>(payload, headers),
                byte[].class
        );

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("TMAP route request failed: " + response.getStatusCode());
        }

        String body = new String(response.getBody(), StandardCharsets.UTF_8);
        return parseTmapGeoJsonRoute(body, mode);
    }

    private TransitRouteDto.RouteResponse parseTmapGeoJsonRoute(String body, String mode) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            JsonNode features = root.path("features");
            if (!features.isArray() || features.isEmpty()) {
                throw new IllegalStateException("TMAP route result is empty.");
            }

            List<TransitRouteDto.Point> polyline = new ArrayList<>();
            List<String> instructions = new ArrayList<>();
            int totalDistanceM = 0;
            int totalTimeSec = 0;

            for (JsonNode feature : features) {
                JsonNode properties = feature.path("properties");
                if (totalDistanceM == 0) {
                    totalDistanceM = properties.path("totalDistance").asInt(0);
                }
                if (totalTimeSec == 0) {
                    totalTimeSec = properties.path("totalTime").asInt(0);
                }

                String description = properties.path("description").asText("").trim();
                if (!description.isBlank()) {
                    instructions.add(description);
                }

                JsonNode geometry = feature.path("geometry");
                if ("LineString".equalsIgnoreCase(geometry.path("type").asText(""))) {
                    appendGeoJsonLineStringPoints(geometry.path("coordinates"), polyline);
                }
            }

            if (polyline.size() < 2) {
                throw new IllegalStateException("TMAP route line is empty.");
            }

            if (totalDistanceM == 0) {
                totalDistanceM = (int) Math.round(sumPolylineDistance(polyline));
            }

            TransitRouteDto.Summary summary = new TransitRouteDto.Summary(
                    totalTimeSec,
                    totalDistanceM,
                    "walk".equals(mode) ? totalTimeSec : null,
                    "walk".equals(mode) ? totalDistanceM : null,
                    null,
                    null
            );
            TransitRouteDto.Leg leg = new TransitRouteDto.Leg(
                    "car".equals(mode) ? "CAR" : "WALK",
                    "",
                    "car".equals(mode) ? "#6B7280" : "#111827",
                    "현재 위치",
                    "도착지",
                    totalDistanceM,
                    totalTimeSec,
                    0,
                    instructions,
                    polyline,
                    List.of()
            );

            return new TransitRouteDto.RouteResponse(mode, summary, List.of(leg));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse TMAP route response.", exception);
        }
    }

    private TransitRouteDto.TransitRouteResponse parseTransitRoute(String body) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(body);
            JsonNode itinerary = root.path("metaData").path("plan").path("itineraries").path(0);
            if (itinerary.isMissingNode() || itinerary.isNull()) {
                throw new IllegalStateException("TMAP transit route result is empty.");
            }

            TransitRouteDto.Summary summary = new TransitRouteDto.Summary(
                    itinerary.path("totalTime").asInt(),
                    itinerary.path("totalDistance").asInt(),
                    itinerary.path("totalWalkTime").asInt(),
                    itinerary.path("totalWalkDistance").asInt(),
                    itinerary.path("transferCount").asInt(),
                    itinerary.path("fare").path("regular").path("totalFare").asInt()
            );

            List<TransitRouteDto.Leg> legs = new ArrayList<>();
            for (JsonNode legNode : itinerary.path("legs")) {
                legs.add(parseLeg(legNode));
            }

            return new TransitRouteDto.TransitRouteResponse(summary, legs);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse TMAP transit response.", exception);
        }
    }

    private TransitRouteDto.Leg parseLeg(JsonNode legNode) {
        String mode = legNode.path("mode").asText("");
        String route = firstNonBlank(
                legNode.path("route").asText(""),
                legNode.path("lane").path(0).path("route").asText("")
        );
        String routeColor = firstNonBlank(
                legNode.path("routeColor").asText(""),
                legNode.path("lane").path(0).path("routeColor").asText(""),
                defaultColorForMode(mode)
        );

        List<String> instructions = buildInstructions(mode, legNode, route);
        List<TransitRouteDto.Point> polyline = parseLegPolyline(mode, legNode);
        List<TransitRouteDto.Station> stations = parseStations(legNode.path("passStopList").path("stations"));

        return new TransitRouteDto.Leg(
                mode,
                route,
                routeColor,
                legNode.path("start").path("name").asText(""),
                legNode.path("end").path("name").asText(""),
                legNode.path("distance").asInt(),
                legNode.path("sectionTime").asInt(),
                stations.size(),
                instructions,
                polyline,
                stations
        );
    }

    private List<TransitRouteDto.Point> parseLegPolyline(String mode, JsonNode legNode) {
        if ("WALK".equalsIgnoreCase(mode)) {
            Set<String> deduped = new LinkedHashSet<>();
            List<TransitRouteDto.Point> points = new ArrayList<>();
            for (JsonNode stepNode : legNode.path("steps")) {
                appendLineStringPoints(stepNode.path("linestring").asText(""), deduped, points);
            }
            if (points.size() < 2) {
                appendLegEndpointPoints(legNode, deduped, points);
            }
            return points;
        }

        Set<String> deduped = new LinkedHashSet<>();
        List<TransitRouteDto.Point> points = new ArrayList<>();
        appendLineStringPoints(legNode.path("passShape").path("linestring").asText(""), deduped, points);
        if (points.size() < 2) {
            appendLegEndpointPoints(legNode, deduped, points);
        }
        return points;
    }

    private void appendLegEndpointPoints(
            JsonNode legNode,
            Set<String> deduped,
            List<TransitRouteDto.Point> points
    ) {
        appendNamedPoint(legNode.path("start"), deduped, points);
        appendNamedPoint(legNode.path("end"), deduped, points);
    }

    private void appendNamedPoint(
            JsonNode node,
            Set<String> deduped,
            List<TransitRouteDto.Point> points
    ) {
        double lat = firstCoordinate(node, "lat", "y");
        double lng = firstCoordinate(node, "lon", "lng", "x");
        if (!Double.isFinite(lat) || !Double.isFinite(lng) || lat == 0 || lng == 0) {
            return;
        }

        String key = String.format(Locale.US, "%.7f,%.7f", lat, lng);
        if (deduped.add(key)) {
            points.add(new TransitRouteDto.Point(lat, lng));
        }
    }

    private double firstCoordinate(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (value.isNumber()) {
                return value.asDouble();
            }
            if (value.isTextual()) {
                try {
                    return Double.parseDouble(value.asText());
                } catch (NumberFormatException ignored) {
                    // Try the next candidate field.
                }
            }
        }
        return Double.NaN;
    }

    private void appendLineStringPoints(
            String lineString,
            Set<String> deduped,
            List<TransitRouteDto.Point> points
    ) {
        if (lineString == null || lineString.isBlank()) {
            return;
        }

        String[] pairs = lineString.trim().split("\\s+");
        for (String pair : pairs) {
            String[] values = pair.split(",");
            if (values.length < 2) {
                continue;
            }

            try {
                double lng = Double.parseDouble(values[0]);
                double lat = Double.parseDouble(values[1]);
                String key = String.format(Locale.US, "%.7f,%.7f", lat, lng);
                if (deduped.add(key)) {
                    points.add(new TransitRouteDto.Point(lat, lng));
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed points and keep the rest of the route.
            }
        }
    }

    private void appendGeoJsonLineStringPoints(JsonNode coordinatesNode, List<TransitRouteDto.Point> points) {
        if (!coordinatesNode.isArray()) {
            return;
        }

        for (JsonNode coordinate : coordinatesNode) {
            if (!coordinate.isArray() || coordinate.size() < 2) {
                continue;
            }

            double lng = coordinate.path(0).asDouble();
            double lat = coordinate.path(1).asDouble();
            points.add(new TransitRouteDto.Point(lat, lng));
        }
    }

    private double sumPolylineDistance(List<TransitRouteDto.Point> points) {
        double distance = 0;
        for (int index = 1; index < points.size(); index++) {
            TransitRouteDto.Point previous = points.get(index - 1);
            TransitRouteDto.Point current = points.get(index);
            distance += distanceMeters(previous.lat(), previous.lng(), current.lat(), current.lng());
        }
        return distance;
    }

    private double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double earthRadius = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2)
                * Math.sin(dLng / 2);
        return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private List<TransitRouteDto.Station> parseStations(JsonNode stationsNode) {
        List<TransitRouteDto.Station> stations = new ArrayList<>();
        if (stationsNode == null || stationsNode.isMissingNode() || stationsNode.isNull()) {
            return stations;
        }

        if (stationsNode.isArray()) {
            for (JsonNode stationNode : stationsNode) {
                stations.add(parseStation(stationNode));
            }
            return stations;
        }

        stations.add(parseStation(stationsNode));
        return stations;
    }

    private TransitRouteDto.Station parseStation(JsonNode stationNode) {
        return new TransitRouteDto.Station(
                stationNode.path("stationName").asText(""),
                stationNode.path("lat").asDouble(),
                stationNode.path("lon").asDouble()
        );
    }

    private List<String> buildInstructions(String mode, JsonNode legNode, String route) {
        List<String> instructions = new ArrayList<>();
        if ("WALK".equalsIgnoreCase(mode)) {
            for (JsonNode stepNode : legNode.path("steps")) {
                String description = stepNode.path("description").asText("").trim();
                if (!description.isBlank()) {
                    instructions.add(description);
                }
            }
            return instructions;
        }

        String startName = legNode.path("start").path("name").asText("");
        String endName = legNode.path("end").path("name").asText("");
        int stationCount = parseStations(legNode.path("passStopList").path("stations")).size();
        String routeName = route == null || route.isBlank() ? movementLabel(mode) : route;
        StringBuilder builder = new StringBuilder(routeName);
        if (!startName.isBlank() || !endName.isBlank()) {
            builder.append(" ");
            if (!startName.isBlank()) {
                builder.append(startName);
            }
            if (!endName.isBlank()) {
                builder.append(" -> ").append(endName);
            }
        }
        if (stationCount > 0) {
            builder.append(" (").append(stationCount).append(" stations)");
        }
        instructions.add(builder.toString().trim());
        return instructions;
    }

    private String movementLabel(String mode) {
        return switch (mode) {
            case "BUS" -> "Bus";
            case "SUBWAY" -> "Subway";
            case "EXPRESSBUS" -> "Express bus";
            case "TRAIN" -> "Train";
            case "AIRPLANE" -> "Airplane";
            case "FERRY" -> "Ferry";
            default -> "Walk";
        };
    }

    private String defaultColorForMode(String mode) {
        return switch (mode) {
            case "BUS" -> "#2E8B57";
            case "SUBWAY" -> "#3B82F6";
            case "EXPRESSBUS", "TRAIN", "AIRPLANE", "FERRY" -> "#8B5CF6";
            default -> "#111827";
        };
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }

    private String normalizeRequestMode(String mode) {
        String normalized = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        if ("car".equals(normalized) || "transit".equals(normalized) || "walk".equals(normalized)) {
            return normalized;
        }
        return "walk";
    }
}
