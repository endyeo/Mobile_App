package com.flower.backend.chatbot.tool.FestivalAgent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flower.backend.chatbot.dto.ChatMessageRequest;
import com.flower.backend.chatbot.dto.ToolResult;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
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
    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");
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
        return searchFlowerFestivalsResult(keyword, location, nearby, "none");
    }

    public ToolResult searchFlowerFestivalsResult(
            String keyword,
            ChatMessageRequest.LocationContext location,
            boolean nearby,
            String dateFilter
    ) {
        String sanitized = sanitizeKeyword(keyword);
        if (sanitized.isBlank()) {
            sanitized = "꽃";
        }
        String effectiveDateFilter = normalizeDateFilter(dateFilter);
        LocalDate today = LocalDate.now(KOREA_ZONE);
        DateRange dateRange = resolveDateRange(effectiveDateFilter, today);

        if (tourApiKey == null || tourApiKey.isBlank()) {
            Map<String, Object> data = diagnosticData(
                    sanitized,
                    nearby,
                    location,
                    effectiveDateFilter,
                    dateRange,
                    today,
                    0,
                    0,
                    0
            );
            return ToolResult.builder()
                    .tool("festival.searchFlowerFestivals")
                    .status("ERROR")
                    .summary("Tour API 키가 없어 축제 정보를 조회할 수 없습니다.")
                    .error("TOUR_API_KEY is not configured.")
                    .data(data)
                    .build();
        }

        try {
            List<FestivalItem> items = fetchByKeyword(sanitized);
            if (items.isEmpty() && !"꽃".equals(sanitized)) {
                items = fetchByKeyword("꽃");
            }
            if (items.isEmpty()) {
                items = fetchUpcomingFestivals(dateRange.start());
            }

            int excludedPastCount = 0;
            int excludedDateCount = 0;
            int excludedUnknownDateCount = 0;
            List<FestivalItem> filtered = new ArrayList<>();
            for (FestivalItem festival : dedupe(items)) {
                if (!festival.hasLocation() || !festival.isFlowerFestival()) {
                    continue;
                }
                if (festival.isPast(today)) {
                    excludedPastCount++;
                    continue;
                }
                if (!festival.hasUsableDate()) {
                    excludedUnknownDateCount++;
                    continue;
                }
                if (!festival.matchesDateRange(dateRange, today)) {
                    excludedDateCount++;
                    continue;
                }
                filtered.add(festival);
            }

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

            Map<String, Object> data = diagnosticData(
                    sanitized,
                    nearby,
                    location,
                    effectiveDateFilter,
                    dateRange,
                    today,
                    excludedPastCount,
                    excludedDateCount,
                    excludedUnknownDateCount
            );
            data.put("items", rows);

            return ToolResult.builder()
                    .tool("festival.searchFlowerFestivals")
                    .status("SUCCESS")
                    .summary(dateRange.label() + " 기준 '" + sanitized + "' 꽃 축제 검색 결과 "
                            + rows.size() + "건을 찾았습니다.")
                    .data(data)
                    .build();
        } catch (Exception e) {
            log.warn("[Tool:festival] Tour API 축제 조회 실패: {}", e.getMessage());
            Map<String, Object> data = diagnosticData(
                    sanitized,
                    nearby,
                    location,
                    effectiveDateFilter,
                    dateRange,
                    today,
                    0,
                    0,
                    0
            );
            return ToolResult.builder()
                    .tool("festival.searchFlowerFestivals")
                    .status("ERROR")
                    .summary("축제 정보 조회에 실패했습니다.")
                    .error("Tour API 축제 조회 중 오류가 발생했습니다.")
                    .data(data)
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

    private List<FestivalItem> fetchUpcomingFestivals(LocalDate rangeStart) throws Exception {
        String eventStartDate = rangeStart.minusMonths(3).format(TOUR_DATE);
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

    static String normalizeDateFilter(String dateFilter) {
        String normalized = dateFilter == null ? "" : dateFilter.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "today", "this_week", "this_month", "upcoming" -> normalized;
            default -> "upcoming";
        };
    }

    static DateRange resolveDateRange(String dateFilter, LocalDate today) {
        String normalized = normalizeDateFilter(dateFilter);
        return switch (normalized) {
            case "today" -> new DateRange("today", today, today, "오늘");
            case "this_week" -> new DateRange(
                    "this_week",
                    today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                    today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)),
                    "이번 주"
            );
            case "this_month" -> new DateRange(
                    "this_month",
                    today.withDayOfMonth(1),
                    today.withDayOfMonth(today.lengthOfMonth()),
                    "이번 달"
            );
            default -> new DateRange("upcoming", today, null, "다가오는 일정");
        };
    }

    static boolean matchesFestivalDateRange(String eventStartDate, String eventEndDate, DateRange dateRange, LocalDate today) {
        LocalDate startDate = parseTourDate(eventStartDate);
        LocalDate endDate = parseTourDate(eventEndDate);
        if (startDate == null && endDate == null) {
            return false;
        }
        if (startDate == null) {
            startDate = endDate;
        }
        if (endDate == null) {
            endDate = startDate;
        }
        if (endDate.isBefore(today)) {
            return false;
        }
        if (dateRange.end() == null) {
            return !endDate.isBefore(dateRange.start());
        }
        return !startDate.isAfter(dateRange.end()) && !endDate.isBefore(dateRange.start());
    }

    private Map<String, Object> diagnosticData(
            String keyword,
            boolean nearby,
            ChatMessageRequest.LocationContext location,
            String dateFilter,
            DateRange dateRange,
            LocalDate today,
            int excludedPastCount,
            int excludedDateCount,
            int excludedUnknownDateCount
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", List.of());
        data.put("source", "Tour API");
        data.put("keyword", keyword);
        data.put("nearby", nearby);
        data.put("dateFilter", dateFilter);
        data.put("rangeStart", dateRange.start().toString());
        data.put("rangeEnd", dateRange.end() == null ? "" : dateRange.end().toString());
        data.put("today", today.toString());
        data.put("excludedPastCount", excludedPastCount);
        data.put("excludedDateCount", excludedDateCount);
        data.put("excludedUnknownDateCount", excludedUnknownDateCount);
        if (location != null && location.getLat() != null && location.getLng() != null) {
            data.put("lat", location.getLat());
            data.put("lng", location.getLng());
        }
        return data;
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
            LocalDate startDate = parseDate(eventStartDate);
            LocalDate endDate = parseDate(eventEndDate);
            if (endDate != null) {
                return endDate.isBefore(today);
            }
            return startDate != null && startDate.isBefore(today);
        }

        boolean hasUsableDate() {
            return parseDate(eventStartDate) != null || parseDate(eventEndDate) != null;
        }

        boolean matchesDateRange(DateRange dateRange, LocalDate today) {
            return matchesFestivalDateRange(eventStartDate, eventEndDate, dateRange, today);
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

    private static LocalDate parseTourDate(String value) {
        if (value == null || value.length() != 8) {
            return null;
        }
        try {
            return LocalDate.parse(value, TOUR_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    record DateRange(
            String filter,
            LocalDate start,
            LocalDate end,
            String label
    ) {
    }
}
