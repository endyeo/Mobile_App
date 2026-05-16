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
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RestTemplate restTemplate;

    @Value("${tmap.transit.app-key:}")
    private String tmapTransitAppKey;

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
            return points;
        }

        Set<String> deduped = new LinkedHashSet<>();
        List<TransitRouteDto.Point> points = new ArrayList<>();
        appendLineStringPoints(legNode.path("passShape").path("linestring").asText(""), deduped, points);
        return points;
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
            default -> "#6B7280";
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
}
