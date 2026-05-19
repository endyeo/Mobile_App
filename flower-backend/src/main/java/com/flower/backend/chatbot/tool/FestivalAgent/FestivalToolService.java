package com.flower.backend.chatbot.tool.FestivalAgent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flower.backend.chatbot.dto.ChatMessageRequest;
import com.flower.backend.chatbot.dto.ToolResult;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Service
@RequiredArgsConstructor
public class FestivalToolService {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final String BASE_URL = "https://apis.data.go.kr/B551011/KorService2";
    private static final DateTimeFormatter TOUR_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final List<String> FLOWER_KEYWORDS = List.of(
            "꽃", "벚꽃", "진달래", "매화", "수국", "장미", "개나리", "철쭉", "국화",
            "해바라기", "코스모스", "동백", "유채", "flower"
    );

    private final RestTemplate restTemplate;

    @Value("${tour.api-key:${TOUR_API_KEY:}}")
    private String tourApiKey;

    public ToolResult searchFlowerFestivalsResult(
            String keyword,
            ChatMessageRequest.LocationContext location,
            boolean nearby
    ) {
        String sanitized = sanitizeKeyword(keyword);
        if (sanitized.isBlank()) {
            sanitized = "꽃";
        }

        if (tourApiKey == null || tourApiKey.isBlank()) {
            return ToolResult.builder()
                    .tool("festival.searchFlowerFestivals")
                    .status("ERROR")
                    .summary("Tour API 키가 없어 축제 정보를 조회할 수 없습니다.")
                    .error("TOUR_API_KEY is not configured.")
                    .data(Map.of("items", List.of(), "source", "Tour API"))
                    .build();
        }

        try {
            List<FestivalItem> items = fetchByKeyword(sanitized);
            if (items.isEmpty() && !"꽃".equals(sanitized)) {
                items = fetchByKeyword("꽃");
            }
            if (items.isEmpty()) {
                items = fetchUpcomingFestivals();
            }

            List<FestivalItem> filtered = dedupe(items).stream()
                    .filter(FestivalItem::hasLocation)
                    .filter(FestivalItem::isFlowerFestival)
                    .filter(festival -> !festival.isPast(LocalDate.now()))
                    .toList();

            if (nearby && location != null && location.getLat() != null && location.getLng() != null) {
                filtered = filtered.stream()
                        .sorted(Comparator.comparingDouble(festival ->
                                festival.distanceMeters(location.getLat(), location.getLng())))
                        .toList();
            } else {
                filtered = filtered.stream()
                        .sorted(Comparator.comparing(FestivalItem::eventStartDate, Comparator.nullsLast(String::compareTo)))
                        .toList();
            }

            List<Map<String, Object>> rows = filtered.stream()
                    .limit(5)
                    .map(festival -> festival.toItem(location))
                    .toList();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("items", rows);
            data.put("source", "Tour API");
            data.put("keyword", sanitized);
            data.put("nearby", nearby);
            if (location != null && location.getLat() != null && location.getLng() != null) {
                data.put("lat", location.getLat());
                data.put("lng", location.getLng());
            }

            return ToolResult.builder()
                    .tool("festival.searchFlowerFestivals")
                    .status("SUCCESS")
                    .summary("'" + sanitized + "' 꽃 축제 검색 결과 " + rows.size() + "건을 찾았습니다.")
                    .data(data)
                    .build();
        } catch (Exception e) {
            log.warn("[Tool:festival] Tour API 축제 조회 실패: {}", e.getMessage());
            return ToolResult.builder()
                    .tool("festival.searchFlowerFestivals")
                    .status("ERROR")
                    .summary("축제 정보 조회에 실패했습니다.")
                    .error("Tour API 축제 조회 중 오류가 발생했습니다.")
                    .data(Map.of("items", List.of(), "source", "Tour API", "keyword", sanitized))
                    .build();
        }
    }

    private List<FestivalItem> fetchByKeyword(String keyword) throws Exception {
        String uri = UriComponentsBuilder.fromHttpUrl(BASE_URL + "/searchKeyword2")
                .queryParam("serviceKey", tourApiKey)
                .queryParam("numOfRows", 30)
                .queryParam("pageNo", 1)
                .queryParam("MobileOS", "ETC")
                .queryParam("MobileApp", "FlowerApp")
                .queryParam("_type", "json")
                .queryParam("keyword", keyword)
                .queryParam("contentTypeId", "15")
                .build(false)
                .toUriString();
        return parseFestivalList(restTemplate.getForObject(uri, String.class));
    }

    private List<FestivalItem> fetchUpcomingFestivals() throws Exception {
        String eventStartDate = LocalDate.now().minusMonths(3).format(TOUR_DATE);
        String uri = UriComponentsBuilder.fromHttpUrl(BASE_URL + "/searchFestival2")
                .queryParam("serviceKey", tourApiKey)
                .queryParam("numOfRows", 60)
                .queryParam("pageNo", 1)
                .queryParam("MobileOS", "ETC")
                .queryParam("MobileApp", "FlowerApp")
                .queryParam("_type", "json")
                .queryParam("eventStartDate", eventStartDate)
                .queryParam("contentTypeId", "15")
                .build(false)
                .toUriString();
        return parseFestivalList(restTemplate.getForObject(uri, String.class));
    }

    private List<FestivalItem> parseFestivalList(String responseBody) throws Exception {
        if (responseBody == null || responseBody.isBlank()) {
            return List.of();
        }
        JsonNode root = JSON_MAPPER.readTree(responseBody);
        JsonNode items = root.path("response").path("body").path("items").path("item");
        if (items.isMissingNode() || items.isNull()) {
            return List.of();
        }

        List<FestivalItem> results = new ArrayList<>();
        if (items.isArray()) {
            for (JsonNode item : items) {
                results.add(FestivalItem.from(item));
            }
        } else if (items.isObject()) {
            results.add(FestivalItem.from(items));
        }
        return results;
    }

    private List<FestivalItem> dedupe(List<FestivalItem> festivals) {
        Map<String, FestivalItem> deduped = new LinkedHashMap<>();
        for (FestivalItem festival : festivals) {
            String key = !festival.contentId().isBlank()
                    ? festival.contentId()
                    : festival.title() + "_" + festival.mapX() + "_" + festival.mapY();
            deduped.putIfAbsent(key, festival);
        }
        return new ArrayList<>(deduped.values());
    }

    private String sanitizeKeyword(String keyword) {
        if (keyword == null) {
            return "";
        }
        String sanitized = keyword.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return sanitized.length() > 50 ? sanitized.substring(0, 50) : sanitized;
    }

    private record FestivalItem(
            String contentId,
            String title,
            String address,
            String imageUrl,
            String tel,
            String eventStartDate,
            String eventEndDate,
            double mapX,
            double mapY
    ) {
        static FestivalItem from(JsonNode item) {
            return new FestivalItem(
                    text(item, "contentid"),
                    text(item, "title"),
                    joinAddress(text(item, "addr1"), text(item, "addr2")),
                    normalizeImageUrl(text(item, "firstimage2").isBlank()
                            ? text(item, "firstimage")
                            : text(item, "firstimage2")),
                    text(item, "tel"),
                    text(item, "eventstartdate"),
                    text(item, "eventenddate"),
                    doubleValue(item, "mapx"),
                    doubleValue(item, "mapy")
            );
        }

        boolean hasLocation() {
            return mapX != 0 && mapY != 0;
        }

        boolean isFlowerFestival() {
            String normalized = title.toLowerCase(Locale.ROOT);
            return FLOWER_KEYWORDS.stream()
                    .anyMatch(keyword -> normalized.contains(keyword.toLowerCase(Locale.ROOT)));
        }

        boolean isPast(LocalDate today) {
            LocalDate endDate = parseDate(eventEndDate);
            return endDate != null && endDate.isBefore(today);
        }

        Map<String, Object> toItem(ChatMessageRequest.LocationContext location) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("contentId", contentId);
            item.put("title", title);
            item.put("name", title);
            item.put("address", address);
            item.put("period", period());
            item.put("eventStartDate", eventStartDate);
            item.put("eventEndDate", eventEndDate);
            item.put("tel", tel);
            item.put("imageUrl", imageUrl);
            item.put("lat", mapY);
            item.put("lng", mapX);
            item.put("source", "Tour API");
            if (location != null && location.getLat() != null && location.getLng() != null) {
                item.put("distanceKm", Math.round(distanceMeters(location.getLat(), location.getLng()) / 100.0) / 10.0);
            }
            return item;
        }

        String period() {
            String start = formatDate(eventStartDate);
            String end = formatDate(eventEndDate);
            if (start.isBlank()) {
                return "";
            }
            return end.isBlank() ? start : start + " - " + end;
        }

        double distanceMeters(double latitude, double longitude) {
            double earthRadius = 6371000;
            double dLat = Math.toRadians(mapY - latitude);
            double dLng = Math.toRadians(mapX - longitude);
            double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                    + Math.cos(Math.toRadians(latitude))
                    * Math.cos(Math.toRadians(mapY))
                    * Math.sin(dLng / 2) * Math.sin(dLng / 2);
            return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        }

        private static String text(JsonNode node, String field) {
            return node.path(field).asText("").trim();
        }

        private static double doubleValue(JsonNode node, String field) {
            try {
                return Double.parseDouble(text(node, field));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private static String joinAddress(String addr1, String addr2) {
            if (addr1.isBlank()) {
                return addr2;
            }
            if (addr2.isBlank()) {
                return addr1;
            }
            return addr1 + " " + addr2;
        }

        private static String normalizeImageUrl(String value) {
            if (value.startsWith("http://")) {
                return "https://" + value.substring(7);
            }
            return value;
        }

        private static LocalDate parseDate(String value) {
            if (value.length() != 8) {
                return null;
            }
            try {
                return LocalDate.parse(value, TOUR_DATE);
            } catch (DateTimeParseException e) {
                return null;
            }
        }

        private static String formatDate(String value) {
            if (value.length() != 8) {
                return value;
            }
            return value.substring(0, 4) + "." + value.substring(4, 6) + "." + value.substring(6, 8);
        }
    }
}
