package com.flower.backend.plantid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

@Slf4j
@Service
public class PlantIdService {

    private static final double CONFIDENCE_THRESHOLD = 0.40;
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
            bodyNode.put("similar_images", false);
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

            // is_plant 체크
            JsonNode isPlant = root.path("result").path("is_plant");
            if (!isPlant.isMissingNode()) {
                double isPlantProb = isPlant.path("probability").asDouble(0.0);
                if (isPlantProb < 0.5) return PlantIdResult.fallback();
            }

            // 최고 신뢰도 분류 가져오기
            JsonNode suggestions = root.path("result").path("classification").path("suggestions");
            if (suggestions.isEmpty()) return PlantIdResult.fallback();

            JsonNode best = suggestions.get(0);
            double confidence = best.path("probability").asDouble(0.0);
            if (confidence < CONFIDENCE_THRESHOLD) return PlantIdResult.fallback();

            String name = best.path("name").asText("");
            // 한국어 일반명 시도
            JsonNode details = best.path("details");
            JsonNode commonNames = details.path("common_names");
            String koreanName = findKoreanName(commonNames, name);

            return new PlantIdResult(koreanName, (float) confidence, true);
        } catch (Exception e) {
            log.warn("[PlantId] 응답 파싱 실패: {}", e.getMessage());
            return PlantIdResult.fallback();
        }
    }

    private String findKoreanName(JsonNode commonNames, String fallback) {
        if (!commonNames.isArray()) return fallback;
        for (JsonNode n : commonNames) {
            String name = n.asText();
            // 한글 포함 여부 확인
            if (name.matches(".*[가-힣].*")) return name;
        }
        // 한국어 없으면 첫 번째 일반명 or 학명
        return commonNames.isEmpty() ? fallback : commonNames.get(0).asText(fallback);
    }

    public record PlantIdResult(String plantName, float confidence, boolean isPlant) {
        public static PlantIdResult fallback() {
            return new PlantIdResult("기타", 0f, false);
        }
    }
}
