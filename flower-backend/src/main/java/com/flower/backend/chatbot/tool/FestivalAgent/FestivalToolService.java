package com.flower.backend.chatbot.tool.FestivalAgent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flower.backend.chatbot.dto.ChatMessageRequest;
import com.flower.backend.chatbot.dto.ToolResult;
import com.flower.backend.festival.Festival;
import com.flower.backend.festival.FestivalRepository;
import java.time.Duration;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Service
public class FestivalToolService {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final String BASE_URL = "https://apis.data.go.kr/B551011/KorService2";
    private static final DateTimeFormatter TOUR_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(3);
    private static final long TOTAL_TIME_BUDGET_MS = 9_000L;
    private static final long MIN_FALLBACK_REMAINING_MS = 4_000L;
    private static final long MIN_DETAIL_INTRO_REMAINING_MS = 2_500L;
    private static final int DETAIL_INTRO_MAX_ATTEMPTS = 5;
    private static final int SEARCH_FESTIVAL_MAX_PAGES = 3;
    private static final int SEARCH_FESTIVAL_ROWS_PER_PAGE = 60;
    private static final List<String> FLOWER_KEYWORDS = List.of(
            "꽃", "벚꽃", "진달래", "매화", "수국", "장미", "개나리", "철쭉", "국화",
            "해바라기", "코스모스", "동백", "유채", "flower"
    );
    private static final List<String> PRIORITY_FESTIVAL_KEYWORDS = List.of("꽃", "벚꽃", "매화", "유채", "장미", "국화");
    private static final List<String> GENERIC_FESTIVAL_KEYWORDS = List.of("꽃", "축제", "행사", "페스티벌");

    private final RestTemplate restTemplate;
    private final FestivalRepository festivalRepository;

    @Value("${tour.api-key:${TOUR_API_KEY:}}")
    private String tourApiKey;

    @Autowired
    public FestivalToolService(FestivalRepository festivalRepository) {
        this(createTimeoutRestTemplate(), festivalRepository);
    }

    FestivalToolService(RestTemplate restTemplate) {
        this(restTemplate, null);
    }

    FestivalToolService(RestTemplate restTemplate, FestivalRepository festivalRepository) {
        this.restTemplate = restTemplate;
        this.festivalRepository = festivalRepository;
    }

    private static RestTemplate createTimeoutRestTemplate() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        requestFactory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return new RestTemplate(requestFactory);
    }

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
        if (festivalRepository != null) {
            return searchFlowerFestivalsFromDbResult(sanitized, location, nearby, effectiveDateFilter, dateRange, today);
        }
        long startedAt = System.currentTimeMillis();
        List<String> attemptedEndpoints = new ArrayList<>();

        if (tourApiKey == null || tourApiKey.isBlank()) {
            Map<String, Object> data = diagnosticData(
                    sanitized,
                    nearby,
                    location,
                    effectiveDateFilter,
                    0
            );
            return ToolResult.builder()
                    .tool("festival.searchFlowerFestivals")
                    .status("ERROR")
                    .summary("축제 정보 조회 설정이 없어 축제 정보를 조회할 수 없습니다.")
                    .error("FESTIVAL_SOURCE_NOT_CONFIGURED")
                    .data(data)
                    .build();
        }

        try {
            FetchResult primaryFetch = fetchUpcomingFestivalPages(primaryFestivalStartDate(dateRange, today), attemptedEndpoints, startedAt);
            List<FestivalItem> rawItems = primaryFetch.items();
            FestivalSelection selection = selectFestivals(rawItems, dateRange, today, location, nearby);
            boolean apiTimedOut = primaryFetch.timedOut();
            DetailIntroStats detailIntroStats = DetailIntroStats.empty();

            if (selection.items().isEmpty() && shouldUseKeywordFallback(sanitized)) {
                long remainingMs = remainingMs(startedAt);
                if (remainingMs >= MIN_FALLBACK_REMAINING_MS) {
                    FetchResult fallbackFetch = fetchPriorityKeywordFestivals(attemptedEndpoints, startedAt);
                    rawItems = mergeAndDedupe(rawItems, fallbackFetch.items());
                    DetailIntroEnrichment enrichment = enrichFestivalDates(rawItems, attemptedEndpoints, startedAt);
                    rawItems = enrichment.items();
                    detailIntroStats = enrichment.stats();
                    selection = selectFestivals(rawItems, dateRange, today, location, nearby);
                    apiTimedOut = apiTimedOut || fallbackFetch.timedOut();
                }
            }
            if (selection.excludedUnknownDateCount() > 0
                    && detailIntroStats.detailIntroAttemptedCount() == 0
                    && remainingMs(startedAt) >= MIN_DETAIL_INTRO_REMAINING_MS) {
                DetailIntroEnrichment enrichment = enrichFestivalDates(rawItems, attemptedEndpoints, startedAt);
                rawItems = enrichment.items();
                detailIntroStats = detailIntroStats.plus(enrichment.stats());
                selection = selectFestivals(rawItems, dateRange, today, location, nearby);
            }

            List<Map<String, Object>> rows = selection.items().stream()
                    .filter(FestivalItem::hasUsableDate)
                    .limit(5)
                    .map(festival -> festival.toItem(location))
                    .toList();

            Map<String, Object> data = diagnosticData(
                    sanitized,
                    nearby,
                    location,
                    effectiveDateFilter,
                    selection.excludedPastCount()
            );
            data.put("items", rows);

            if (rows.isEmpty() && apiTimedOut) {
                return ToolResult.builder()
                        .tool("festival.searchFlowerFestivals")
                        .status("ERROR")
                        .summary("축제 정보 조회가 지연되어 결과를 가져오지 못했습니다.")
                        .error("축제 정보 조회가 지연되어 결과를 가져오지 못했습니다.")
                        .data(data)
                        .build();
            }

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
                    0
            );
            return ToolResult.builder()
                    .tool("festival.searchFlowerFestivals")
                    .status("ERROR")
                    .summary("축제 정보 조회에 실패했습니다.")
                    .error("축제 정보 조회 중 오류가 발생했습니다.")
                    .data(data)
                    .build();
        }
    }

    private ToolResult searchFlowerFestivalsFromDbResult(
            String keyword,
            ChatMessageRequest.LocationContext location,
            boolean nearby,
            String dateFilter,
            DateRange dateRange,
            LocalDate today
    ) {
        try {
            String effectiveKeyword = shouldApplyKeywordFilter(keyword) ? keyword : "";
            List<FestivalItem> rawItems = festivalRepository.searchChatbotCandidates(
                            dateRange.start().format(TOUR_DATE),
                            dateRange.end() == null ? null : dateRange.end().format(TOUR_DATE),
                            effectiveKeyword,
                            PageRequest.of(0, 100))
                    .stream()
                    .map(FestivalItem::from)
                    .toList();
            FestivalSelection selection = selectFestivals(rawItems, dateRange, today, location, nearby);
            List<Map<String, Object>> rows = selection.items().stream()
                    .filter(FestivalItem::hasUsableDate)
                    .limit(5)
                    .map(festival -> festival.toItem(location))
                    .toList();

            Map<String, Object> data = diagnosticData(
                    keyword,
                    nearby,
                    location,
                    dateFilter,
                    selection.excludedPastCount()
            );
            data.put("items", rows);

            return ToolResult.builder()
                    .tool("festival.searchFlowerFestivals")
                    .status("SUCCESS")
                    .summary(dateRange.label() + " 기준 '" + keyword + "' DB 축제 검색 결과 "
                            + rows.size() + "건을 찾았습니다.")
                    .data(data)
                    .build();
        } catch (Exception e) {
            log.warn("[Tool:festival] DB 축제 조회 실패: {}", e.getMessage());
            return ToolResult.builder()
                    .tool("festival.searchFlowerFestivals")
                    .status("ERROR")
                    .summary("축제 정보 조회에 실패했습니다.")
                    .error("축제 DB 조회 중 오류가 발생했습니다.")
                    .data(diagnosticData(keyword, nearby, location, dateFilter, 0))
                    .build();
        }
    }

    private FetchResult fetchByKeyword(String keyword, List<String> attemptedEndpoints) throws Exception {
        attemptedEndpoints.add("searchKeyword2:" + keyword);
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
        return new FetchResult(parseFestivalList(restTemplate.getForObject(uri, String.class)), false, false);
    }

    private FetchResult fetchPriorityKeywordFestivals(List<String> attemptedEndpoints, long startedAt) {
        List<FestivalItem> merged = new ArrayList<>();
        boolean apiTimedOut = false;
        boolean fallbackLimited = false;
        for (String keyword : PRIORITY_FESTIVAL_KEYWORDS) {
            if (remainingMs(startedAt) < MIN_FALLBACK_REMAINING_MS) {
                fallbackLimited = true;
                break;
            }
            try {
                FetchResult fetchResult = fetchByKeyword(keyword, attemptedEndpoints);
                merged.addAll(fetchResult.items());
                apiTimedOut = apiTimedOut || fetchResult.timedOut();
            } catch (Exception e) {
                apiTimedOut = apiTimedOut || isTimeoutException(e);
                log.warn("[Tool:festival] Tour API 키워드 축제 조회 실패(keyword={}): {}", keyword, e.getMessage());
            }
        }
        return new FetchResult(merged, apiTimedOut, fallbackLimited);
    }

    private LocalDate primaryFestivalStartDate(DateRange dateRange, LocalDate today) {
        if ("this_week".equals(dateRange.filter()) || "this_month".equals(dateRange.filter())) {
            return dateRange.start();
        }
        return today;
    }

    private FetchResult fetchUpcomingFestivalPages(
            LocalDate eventStartDateValue,
            List<String> attemptedEndpoints,
            long startedAt
    ) {
        List<FestivalItem> merged = new ArrayList<>();
        boolean apiTimedOut = false;
        boolean limited = false;
        for (int pageNo = 1; pageNo <= SEARCH_FESTIVAL_MAX_PAGES; pageNo++) {
            if (remainingMs(startedAt) < MIN_FALLBACK_REMAINING_MS) {
                limited = true;
                break;
            }
            try {
                FetchResult pageFetch = fetchUpcomingFestivals(eventStartDateValue, pageNo, attemptedEndpoints);
                merged.addAll(pageFetch.items());
            } catch (Exception e) {
                apiTimedOut = apiTimedOut || isTimeoutException(e);
                log.warn("[Tool:festival] Tour API 축제 목록 조회 실패(page={}): {}", pageNo, e.getMessage());
            }
        }
        return new FetchResult(dedupe(merged), apiTimedOut, limited);
    }

    private FetchResult fetchUpcomingFestivals(
            LocalDate eventStartDateValue,
            int pageNo,
            List<String> attemptedEndpoints
    ) throws Exception {
        String eventStartDate = eventStartDateValue.format(TOUR_DATE);
        attemptedEndpoints.add("searchFestival2:" + pageNo);
        String uri = UriComponentsBuilder.fromHttpUrl(BASE_URL + "/searchFestival2")
                .queryParam("serviceKey", tourApiKey)
                .queryParam("numOfRows", SEARCH_FESTIVAL_ROWS_PER_PAGE)
                .queryParam("pageNo", pageNo)
                .queryParam("MobileOS", "ETC")
                .queryParam("MobileApp", "FlowerApp")
                .queryParam("_type", "json")
                .queryParam("eventStartDate", eventStartDate)
                .build(false)
                .toUriString();
        return new FetchResult(parseFestivalList(restTemplate.getForObject(uri, String.class)), false, false);
    }

    private DetailIntroEnrichment enrichFestivalDates(
            List<FestivalItem> rawItems,
            List<String> attemptedEndpoints,
            long startedAt
    ) {
        List<FestivalItem> deduped = dedupe(rawItems);
        Map<String, FestivalItem> enrichedByKey = new LinkedHashMap<>();
        int attempted = 0;
        int enriched = 0;
        int failed = 0;
        boolean limited = false;

        for (FestivalItem festival : deduped) {
            String key = festivalKey(festival);
            FestivalItem current = festival;
            if (festival.hasLocation() && festival.isFlowerFestival() && !festival.hasUsableDate()) {
                if (attempted >= DETAIL_INTRO_MAX_ATTEMPTS || remainingMs(startedAt) < MIN_DETAIL_INTRO_REMAINING_MS) {
                    limited = true;
                } else {
                    attempted++;
                    try {
                        attemptedEndpoints.add("detailIntro2:" + festival.contentId());
                        current = fetchFestivalDetailIntro(festival);
                        if (current.hasUsableDate()) {
                            enriched++;
                        } else {
                            failed++;
                        }
                    } catch (Exception e) {
                        failed++;
                        log.warn("[Tool:festival] detailIntro2 보강 실패(contentId={}): {}", festival.contentId(), e.getMessage());
                    }
                }
            }
            enrichedByKey.putIfAbsent(key, current);
        }

        return new DetailIntroEnrichment(
                new ArrayList<>(enrichedByKey.values()),
                new DetailIntroStats(attempted, enriched, failed, limited)
        );
    }

    private FestivalItem fetchFestivalDetailIntro(FestivalItem festival) throws Exception {
        if (festival.contentId().isBlank()) {
            return festival;
        }
        String uri = UriComponentsBuilder.fromHttpUrl(BASE_URL + "/detailIntro2")
                .queryParam("serviceKey", tourApiKey)
                .queryParam("contentId", festival.contentId())
                .queryParam("contentTypeId", "15")
                .queryParam("MobileOS", "ETC")
                .queryParam("MobileApp", "FlowerApp")
                .queryParam("_type", "json")
                .build(false)
                .toUriString();
        return mergeFestivalDates(festival, restTemplate.getForObject(uri, String.class));
    }

    private FestivalItem mergeFestivalDates(FestivalItem festival, String responseBody) throws Exception {
        if (responseBody == null || responseBody.isBlank()) {
            return festival;
        }
        JsonNode root = JSON_MAPPER.readTree(responseBody);
        JsonNode item = root.path("response").path("body").path("items").path("item");
        if (item.isArray()) {
            item = item.size() > 0 ? item.get(0) : null;
        }
        if (item == null || item.isMissingNode() || item.isNull()) {
            return festival;
        }
        String eventStartDate = FestivalItem.text(item, "eventstartdate");
        String eventEndDate = FestivalItem.text(item, "eventenddate");
        return festival.withDates(eventStartDate, eventEndDate);
    }

    static String normalizeDateFilter(String dateFilter) {
        String normalized = dateFilter == null ? "" : dateFilter.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "today", "this_week", "this_month", "upcoming" -> normalized;
            default -> "upcoming";
        };
    }

    private FestivalSelection selectFestivals(
            List<FestivalItem> rawItems,
            DateRange dateRange,
            LocalDate today,
            ChatMessageRequest.LocationContext location,
            boolean nearby
    ) {
        int excludedPastCount = 0;
        int excludedDateCount = 0;
        int excludedUnknownDateCount = 0;
        int flowerFilteredCount = 0;
        List<FestivalItem> filtered = new ArrayList<>();

        for (FestivalItem festival : dedupe(rawItems)) {
            if (!festival.hasLocation() || !festival.isFlowerFestival()) {
                continue;
            }
            if (festival.isPast(today)) {
                excludedPastCount++;
                continue;
            }
            flowerFilteredCount++;
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

        List<FestivalItem> sorted = sortFestivals(filtered, location, nearby);
        return new FestivalSelection(
                sorted,
                dedupe(rawItems).size(),
                flowerFilteredCount,
                excludedPastCount,
                excludedDateCount,
                excludedUnknownDateCount
        );
    }

    private List<FestivalItem> sortFestivals(
            List<FestivalItem> festivals,
            ChatMessageRequest.LocationContext location,
            boolean nearby
    ) {
        if (nearby && location != null && location.getLat() != null && location.getLng() != null) {
            return festivals.stream()
                    .sorted(Comparator.comparingDouble(festival ->
                            festival.distanceMeters(location.getLat(), location.getLng())))
                    .toList();
        }
        return festivals.stream()
                .sorted(Comparator
                        .comparing(FestivalItem::eventStartDate, Comparator.nullsLast(String::compareTo))
                        .thenComparing(FestivalItem::title))
                .toList();
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
        if (startDate == null || endDate == null) {
            return false;
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
            int excludedPastCount
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", List.of());
        data.put("source", "festival_db");
        data.put("query", keyword);
        data.put("dateFilter", dateFilter);
        data.put("excludedPastCount", excludedPastCount);
        data.put("locationUsed", nearby && location != null && location.getLat() != null && location.getLng() != null);
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

    private List<FestivalItem> mergeAndDedupe(List<FestivalItem> left, List<FestivalItem> right) {
        List<FestivalItem> merged = new ArrayList<>(left);
        merged.addAll(right);
        return dedupe(merged);
    }

    private boolean shouldUseKeywordFallback(String keyword) {
        String normalized = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return false;
        }
        return GENERIC_FESTIVAL_KEYWORDS.stream()
                .noneMatch(generic -> normalized.equals(generic.toLowerCase(Locale.ROOT)));
    }

    private boolean shouldApplyKeywordFilter(String keyword) {
        String normalized = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return false;
        }
        return GENERIC_FESTIVAL_KEYWORDS.stream()
                .noneMatch(generic -> normalized.equals(generic.toLowerCase(Locale.ROOT)));
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

    private String festivalKey(FestivalItem festival) {
        return !festival.contentId().isBlank()
                ? festival.contentId()
                : festival.title() + "_" + festival.mapX() + "_" + festival.mapY();
    }

    private long elapsedMs(long startedAt) {
        return Math.max(0, System.currentTimeMillis() - startedAt);
    }

    private long remainingMs(long startedAt) {
        return TOTAL_TIME_BUDGET_MS - elapsedMs(startedAt);
    }

    private boolean isTimeoutException(Exception e) {
        if (e instanceof ResourceAccessException) {
            return true;
        }
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.net.http.HttpTimeoutException
                    || cause instanceof java.net.ConnectException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
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

        static FestivalItem from(Festival festival) {
            return new FestivalItem(
                    emptyIfNull(festival.getContentId()),
                    emptyIfNull(festival.getTitle()),
                    joinAddress(emptyIfNull(festival.getAddr1()), emptyIfNull(festival.getAddr2())),
                    normalizeImageUrl(!emptyIfNull(festival.getFirstImage2()).isBlank()
                            ? emptyIfNull(festival.getFirstImage2())
                            : emptyIfNull(festival.getFirstImage())),
                    emptyIfNull(festival.getTel()),
                    emptyIfNull(festival.getEventStartDate()),
                    emptyIfNull(festival.getEventEndDate()),
                    festival.getMapX() == null ? 0 : festival.getMapX(),
                    festival.getMapY() == null ? 0 : festival.getMapY()
            );
        }

        boolean hasLocation() {
            return mapX != 0 && mapY != 0;
        }

        boolean isFlowerFestival() {
            String normalized = (title + " " + address).toLowerCase(Locale.ROOT);
            return FLOWER_KEYWORDS.stream()
                    .anyMatch(keyword -> normalized.contains(keyword.toLowerCase(Locale.ROOT)));
        }

        FestivalItem withDates(String startDate, String endDate) {
            String normalizedStart = !startDate.isBlank() ? startDate : eventStartDate;
            String normalizedEnd = !endDate.isBlank() ? endDate : eventEndDate;
            return new FestivalItem(
                    contentId,
                    title,
                    address,
                    imageUrl,
                    tel,
                    normalizedStart,
                    normalizedEnd,
                    mapX,
                    mapY
            );
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
            return parseDate(eventStartDate) != null && parseDate(eventEndDate) != null;
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
            item.put("source", "festival_db");
            if (location != null && location.getLat() != null && location.getLng() != null) {
                item.put("distanceKm", Math.round(distanceMeters(location.getLat(), location.getLng()) / 100.0) / 10.0);
            }
            return item;
        }

        String period() {
            String start = formatDate(eventStartDate);
            String end = formatDate(eventEndDate);
            if (start.isBlank() || end.isBlank()) {
                return "";
            }
            return start + " - " + end;
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

        private static String emptyIfNull(String value) {
            return value == null ? "" : value.trim();
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

    record FestivalSelection(
            List<FestivalItem> items,
            int rawFestivalCount,
            int flowerFilteredCount,
            int excludedPastCount,
            int excludedDateCount,
            int excludedUnknownDateCount
    ) {
    }

    record DateRange(
            String filter,
            LocalDate start,
            LocalDate end,
            String label
    ) {
    }

    record FetchResult(
            List<FestivalItem> items,
            boolean timedOut,
            boolean limited
    ) {
    }

    record DetailIntroEnrichment(
            List<FestivalItem> items,
            DetailIntroStats stats
    ) {
    }

    record DetailIntroStats(
            int detailIntroAttemptedCount,
            int detailIntroEnrichedCount,
            int detailIntroFailedCount,
            boolean detailIntroLimited
    ) {
        static DetailIntroStats empty() {
            return new DetailIntroStats(0, 0, 0, false);
        }

        DetailIntroStats plus(DetailIntroStats other) {
            if (other == null) {
                return this;
            }
            return new DetailIntroStats(
                    detailIntroAttemptedCount + other.detailIntroAttemptedCount(),
                    detailIntroEnrichedCount + other.detailIntroEnrichedCount(),
                    detailIntroFailedCount + other.detailIntroFailedCount(),
                    detailIntroLimited || other.detailIntroLimited()
            );
        }
    }
}
