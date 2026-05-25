package com.flower.backend.plantid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flower.backend.flower.FlowerDto;
import com.flower.backend.flower.FlowerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlantIdService {

    private static final double CONFIDENCE_THRESHOLD = 0.30;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String WIKI_USER_AGENT = "OurT-FlowerApp/1.0 (https://ourt.kro.kr; gkak1211@gmail.com)";

    /** 학명 → 한글/영문 표시명 캐시 (반복 호출 시 위키 트래픽 절감) */
    private final Map<String, String> nameCache = new ConcurrentHashMap<>();

    private final FlowerService flowerService;

    @Value("${plantid.api-key:}")
    private String apiKey;

    @Value("${plantid.api-url:https://api.plant.id/v3/identification}")
    private String apiUrl;

    public PlantIdResult identify(byte[] imageBytes) {
        if (apiKey.isBlank()) {
            log.warn("[PlantId] API 키 미설정 → 기타 반환");
            return PlantIdResult.fallback();
        }

        try {
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);

            com.fasterxml.jackson.databind.node.ObjectNode bodyNode = MAPPER.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode imagesArray = bodyNode.putArray("images");
            imagesArray.add("data:image/jpeg;base64," + base64Image);
            // similar_images=false 는 v3에서 invalid modifier 로 거부됨.
            // 기본값이 이미 false이므로 필드 자체를 보내지 않음.
            String body = MAPPER.writeValueAsString(bodyNode);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Api-Key", apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200 && response.statusCode() != 201) {
                log.warn("[PlantId] API 오류 {}: {}", response.statusCode(), response.body());
                return PlantIdResult.fallback();
            }

            return parseResponse(response.body());
        } catch (Exception e) {
            log.error("[PlantId] 호출 실패", e);
            return PlantIdResult.fallback();
        }
    }

    private PlantIdResult parseResponse(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);

            // 1단계: 식물인지 판정
            JsonNode isPlant = root.path("result").path("is_plant");
            boolean recognizedAsPlant = true;
            if (!isPlant.isMissingNode()) {
                double isPlantProb = isPlant.path("probability").asDouble(0.0);
                recognizedAsPlant = isPlantProb >= 0.5;
            }
            if (!recognizedAsPlant) {
                return PlantIdResult.notPlant();
            }

            // 2단계: 종 판정
            JsonNode suggestions = root.path("result").path("classification").path("suggestions");
            if (suggestions.isEmpty()) {
                // 식물은 맞지만 후보 없음 → 식물(미상)
                return PlantIdResult.unidentifiedPlant(0.0);
            }

            JsonNode best = suggestions.get(0);
            double confidence = best.path("probability").asDouble(0.0);
            if (confidence < CONFIDENCE_THRESHOLD) {
                // 식물 맞지만 종 확신 부족 → 식물(미상)
                return PlantIdResult.unidentifiedPlant(confidence);
            }

            String scientificName = best.path("name").asText("");
            String displayName = resolveDisplayName(scientificName);

            return new PlantIdResult(displayName, (float) confidence, true);
        } catch (Exception e) {
            log.warn("[PlantId] 응답 파싱 실패: {}", e.getMessage());
            return PlantIdResult.notPlant();
        }
    }

    /**
     * 학명을 사용자에게 보여줄 이름으로 변환:
     * 1) 도감 학명 완전 일치 → 한글명
     * 2) 도감 속(Genus, 학명 첫 단어) 일치 → 한글명
     * 3) Wikipedia 한국어 interwiki → 한글명
     * 4) Wikipedia 영어 페이지 제목 → 영문 common name (학명 그대로일 수도 있음)
     * 5) 모두 실패 → 학명 그대로
     */
    private String resolveDisplayName(String scientificName) {
        if (scientificName == null || scientificName.isBlank()) return scientificName;

        String cached = nameCache.get(scientificName);
        if (cached != null) return cached;

        String resolved = resolveDisplayNameUncached(scientificName);
        nameCache.put(scientificName, resolved);
        return resolved;
    }

    private String resolveDisplayNameUncached(String scientificName) {
        // 1) FlowerService에 위임 — 도감 학명/속 매칭 + 한국어 위키 자동 등록까지 한 번에 처리
        //    (도감에 없으면 ko.wikipedia.org에서 학명으로 페이지 조회 → flower_book 자동 INSERT)
        try {
            FlowerDto.MatchResult match = flowerService.matchByScientificName(scientificName, 1.0);
            if (match.isMatched() && match.getFlowerName() != null && !match.getFlowerName().isBlank()) {
                log.debug("[PlantId] FlowerService 매칭: {} → {}", scientificName, match.getFlowerName());
                return match.getFlowerName();
            }
        } catch (Exception e) {
            log.warn("[PlantId] FlowerService 매칭 실패, 위키 폴백 사용: {}", e.getMessage());
        }

        // 2) Wikipedia 영문 페이지의 ko interwiki 폴백 — 한국어 위키엔 없지만 영문판에서 한국어 링크 가진 종 포착
        Optional<String> koWiki = lookupWikipediaKoreanName(scientificName);
        if (koWiki.isPresent()) {
            log.debug("[PlantId] 위키 한국어 interwiki: {} → {}", scientificName, koWiki.get());
            return koWiki.get();
        }

        // 4) Wikipedia 영어 페이지 제목 (영문 common name 가능성)
        Optional<String> enWiki = lookupWikipediaEnglishTitle(scientificName);
        if (enWiki.isPresent() && !enWiki.get().equalsIgnoreCase(scientificName)) {
            log.debug("[PlantId] 위키 영문명: {} → {}", scientificName, enWiki.get());
            return enWiki.get();
        }

        // 5) 학명 그대로
        log.debug("[PlantId] 매핑 실패, 학명 반환: {}", scientificName);
        return scientificName;
    }

    /** 영어 위키에서 학명 페이지를 찾고 한국어 interwiki가 있으면 그 한글명을 반환. */
    private Optional<String> lookupWikipediaKoreanName(String scientificName) {
        try {
            String url = "https://en.wikipedia.org/w/api.php" +
                    "?action=query&format=json&redirects=1" +
                    "&prop=langlinks&lllang=ko&lllimit=1" +
                    "&titles=" + URLEncoder.encode(scientificName, StandardCharsets.UTF_8);
            JsonNode root = fetchJson(url);
            if (root == null) return Optional.empty();

            JsonNode pages = root.path("query").path("pages");
            if (!pages.isObject()) return Optional.empty();
            Iterator<JsonNode> it = pages.elements();
            while (it.hasNext()) {
                JsonNode page = it.next();
                if (page.has("missing")) continue;
                JsonNode langlinks = page.path("langlinks");
                if (langlinks.isArray() && langlinks.size() > 0) {
                    String koTitle = langlinks.get(0).path("*").asText("");
                    if (!koTitle.isBlank()) return Optional.of(koTitle);
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("[PlantId] Wikipedia 한국어 조회 실패: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 영어 위키에서 학명으로 검색한 후 redirect 따라간 최종 페이지 제목 반환 (영문 common name 가능). */
    private Optional<String> lookupWikipediaEnglishTitle(String scientificName) {
        try {
            String url = "https://en.wikipedia.org/w/api.php" +
                    "?action=query&format=json&redirects=1" +
                    "&titles=" + URLEncoder.encode(scientificName, StandardCharsets.UTF_8);
            JsonNode root = fetchJson(url);
            if (root == null) return Optional.empty();

            JsonNode pages = root.path("query").path("pages");
            if (!pages.isObject()) return Optional.empty();
            Iterator<JsonNode> it = pages.elements();
            while (it.hasNext()) {
                JsonNode page = it.next();
                if (page.has("missing")) continue;
                String title = page.path("title").asText("");
                if (!title.isBlank()) return Optional.of(title);
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("[PlantId] Wikipedia 영문 조회 실패: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private JsonNode fetchJson(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", WIKI_USER_AGENT)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.debug("[PlantId] Wikipedia {} {}", response.statusCode(), url);
            return null;
        }
        return MAPPER.readTree(response.body());
    }

    public record PlantIdResult(String plantName, float confidence, boolean isPlant) {
        /** 식물이 아니라고 판정됐을 때 (is_plant < 0.5 또는 API 실패) */
        public static PlantIdResult notPlant() {
            return new PlantIdResult("기타", 0f, false);
        }

        /** 식물 맞지만 종을 확신하지 못한 경우 (is_plant ≥ 0.5, confidence < 0.3 또는 suggestions 비어있음) */
        public static PlantIdResult unidentifiedPlant(double confidence) {
            return new PlantIdResult("기타(식물)", (float) confidence, true);
        }

        /** 하위 호환: 기존 코드가 fallback()을 호출하던 자리 — 식물 아닌 것으로 처리 */
        public static PlantIdResult fallback() {
            return notPlant();
        }
    }
}
