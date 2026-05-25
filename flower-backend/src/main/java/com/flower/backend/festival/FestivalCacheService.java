package com.flower.backend.festival;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * TourAPI의 꽃 축제를 주 1회 가져와 festivals 테이블에 upsert.
 *
 * - 스케줄: 매주 일요일 03:00 (Asia/Seoul)
 * - 부트스트랩: 앱 시작 직후 캐시가 비어있으면 한 번 즉시 갱신
 * - 범위: 오늘 ~ 향후 3개월 사이 시작 또는 진행 중인 축제
 * - 필터: 제목/주소에 꽃 관련 키워드 포함된 것
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FestivalCacheService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TOUR_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String BASE_URL = "https://apis.data.go.kr/B551011/KorService2";

    /** 제목·주소에 들어있어야 꽃 축제로 간주하는 키워드 */
    private static final List<String> FLOWER_KEYWORDS = Arrays.asList(
            "꽃", "벚꽃", "진달래", "매화", "수국", "장미", "개나리", "철쭉",
            "국화", "해바라기", "코스모스", "동백", "유채", "튤립", "라벤더",
            "flower"
    );

    /** primary 결과가 비면 키워드 순회 폴백 */
    private static final List<String> PRIORITY_KEYWORDS = Arrays.asList(
            "꽃", "벚꽃", "매화", "유채", "장미", "국화"
    );

    private final FestivalRepository festivalRepository;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${tour.api-key:}")
    private String tourApiKey;

    /** 앱 시작 직후 캐시가 비어있으면 즉시 한 번 갱신 (비동기) */
    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void bootstrapIfEmpty() {
        try {
            long count = festivalRepository.count();
            if (count == 0) {
                log.info("[Festival] 캐시 비어있음 → 부트스트랩 시작");
                refreshCache();
            } else {
                log.info("[Festival] 캐시 {}건 — 부트스트랩 스킵", count);
            }
        } catch (Exception e) {
            log.warn("[Festival] 부트스트랩 실패: {}", e.getMessage());
        }
    }

    /** 매주 일요일 03:00 KST에 캐시 갱신 */
    @Scheduled(cron = "0 0 3 * * SUN", zone = "Asia/Seoul")
    @Transactional
    public void scheduledRefresh() {
        log.info("[Festival] 주간 캐시 갱신 시작");
        try {
            refreshCache();
        } catch (Exception e) {
            log.error("[Festival] 주간 캐시 갱신 실패", e);
        }
    }

    /** 외부 API에서 새 축제 목록 받아와 DB에 upsert + 종료된 캐시 정리 */
    @Transactional
    public synchronized int refreshCache() {
        if (tourApiKey == null || tourApiKey.isBlank()) {
            log.warn("[Festival] TOUR_API_KEY 미설정 → 캐시 갱신 스킵");
            return 0;
        }

        LocalDate today = LocalDate.now(KST);
        List<Festival> fresh = fetchFreshFestivals(today);

        Set<String> existingIds = new HashSet<>(festivalRepository.findAllContentIds());
        int inserted = 0;
        int updated = 0;
        for (Festival f : fresh) {
            if (existingIds.contains(f.getContentId())) {
                Optional<Festival> existing = festivalRepository.findByContentId(f.getContentId());
                existing.ifPresent(ex -> {
                    ex.updateFrom(f);
                    festivalRepository.save(ex);
                });
                updated++;
            } else {
                festivalRepository.save(f);
                inserted++;
            }
        }

        int deleted = festivalRepository.deletePastFestivals(today.format(TOUR_DATE));
        log.info("[Festival] 캐시 갱신 완료 — 신규 {}건 / 갱신 {}건 / 만료 삭제 {}건", inserted, updated, deleted);
        return inserted + updated;
    }

    private List<Festival> fetchFreshFestivals(LocalDate today) {
        // 1차: searchFestival2 (오늘부터 — eventStartDate 파라미터는 "이후 시작" 의미라 진행중 축제 포함하려면 충분히 과거부터)
        List<Festival> all = new ArrayList<>();
        try {
            all.addAll(fetchSearchFestival2(today.minusMonths(3)));
        } catch (Exception e) {
            log.warn("[Festival] searchFestival2 실패: {}", e.getMessage());
        }

        // 2차 폴백: 키워드 검색
        if (all.isEmpty()) {
            for (String keyword : PRIORITY_KEYWORDS) {
                try {
                    all.addAll(fetchSearchKeyword2(keyword));
                } catch (Exception e) {
                    log.warn("[Festival] searchKeyword2({}) 실패: {}", keyword, e.getMessage());
                }
            }
        }

        return all.stream()
                .filter(f -> isFlowerFestival(f.getTitle()) || isFlowerFestival(f.getAddr1()))
                .filter(f -> isOngoingOrFuture(f, today))
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toMap(
                                Festival::getContentId, f -> f, (a, b) -> a),
                        m -> new ArrayList<>(m.values())));
    }

    private List<Festival> fetchSearchFestival2(LocalDate eventStart) throws Exception {
        String uri = UriComponentsBuilder.fromHttpUrl(BASE_URL + "/searchFestival2")
                .queryParam("serviceKey", tourApiKey)
                .queryParam("numOfRows", 300)
                .queryParam("pageNo", 1)
                .queryParam("MobileOS", "ETC")
                .queryParam("MobileApp", "FlowerApp")
                .queryParam("_type", "json")
                .queryParam("eventStartDate", eventStart.format(TOUR_DATE))
                .build(false)
                .toUriString();
        return parseList(restTemplate.getForObject(uri, String.class));
    }

    private List<Festival> fetchSearchKeyword2(String keyword) throws Exception {
        String uri = UriComponentsBuilder.fromHttpUrl(BASE_URL + "/searchKeyword2")
                .queryParam("serviceKey", tourApiKey)
                .queryParam("numOfRows", 100)
                .queryParam("pageNo", 1)
                .queryParam("MobileOS", "ETC")
                .queryParam("MobileApp", "FlowerApp")
                .queryParam("_type", "json")
                .queryParam("keyword", keyword)
                .queryParam("contentTypeId", "15")
                .build(false)
                .toUriString();
        return parseList(restTemplate.getForObject(uri, String.class));
    }

    private List<Festival> parseList(String json) throws Exception {
        if (json == null || json.isBlank()) return List.of();
        JsonNode root = mapper.readTree(json);
        JsonNode items = root.path("response").path("body").path("items").path("item");
        List<Festival> result = new ArrayList<>();
        if (items.isArray()) {
            items.forEach(it -> result.add(toFestival(it)));
        } else if (items.isObject()) {
            result.add(toFestival(items));
        }
        return result.stream().filter(f -> f.getContentId() != null && !f.getContentId().isBlank()).toList();
    }

    private Festival toFestival(JsonNode it) {
        return Festival.builder()
                .contentId(asText(it, "contentid"))
                .title(safe(asText(it, "title")))
                .addr1(asText(it, "addr1"))
                .addr2(asText(it, "addr2"))
                .mapX(asDouble(it, "mapx"))
                .mapY(asDouble(it, "mapy"))
                .firstImage(normalizeImageUrl(asText(it, "firstimage")))
                .firstImage2(normalizeImageUrl(asText(it, "firstimage2")))
                .tel(asText(it, "tel"))
                .eventStartDate(asText(it, "eventstartdate"))
                .eventEndDate(asText(it, "eventenddate"))
                .build();
    }

    private static String asText(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText("");
    }

    private static Double asDouble(JsonNode node, String field) {
        String s = asText(node, field);
        if (s == null || s.isBlank()) return null;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String normalizeImageUrl(String url) {
        if (url == null) return null;
        String trimmed = url.trim();
        if (trimmed.startsWith("http://")) return "https://" + trimmed.substring(7);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isFlowerFestival(String text) {
        if (text == null) return false;
        String normalized = text.toLowerCase(Locale.ROOT);
        return FLOWER_KEYWORDS.stream().anyMatch(k -> normalized.contains(k.toLowerCase(Locale.ROOT)));
    }

    /** 종료일이 오늘 이후이거나 시작일이 향후 3개월 이내인 축제만 */
    private static boolean isOngoingOrFuture(Festival f, LocalDate today) {
        LocalDate end = f.parsedEndDate();
        LocalDate start = f.parsedStartDate();
        LocalDate horizon = today.plusMonths(3);
        if (end != null && end.isBefore(today)) return false;
        if (start != null && start.isAfter(horizon)) return false;
        // 시작·종료 모두 없으면 보수적으로 제외
        return end != null || start != null;
    }
}
