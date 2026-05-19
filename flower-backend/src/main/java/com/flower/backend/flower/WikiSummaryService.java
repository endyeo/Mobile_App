package com.flower.backend.flower;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

@Slf4j
@Service
public class WikiSummaryService {

    private static final String WIKI_API = "https://ko.wikipedia.org/api/rest_v1/page/summary/";
    private static final String USER_AGENT = "OurT-FlowerApp/1.0 (https://ourt.kro.kr)";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    public Optional<WikiSummary> fetch(String title) {
        if (title == null || title.isBlank()) return Optional.empty();
        try {
            String encoded = URLEncoder.encode(title.trim(), StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(WIKI_API + encoded))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .timeout(TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) return Optional.empty();
            JsonNode root = MAPPER.readTree(response.body());
            String wikiTitle = root.path("title").asText("");
            String extract = root.path("extract").asText("");
            String thumbnail = root.path("thumbnail").path("source").asText(null);

            if (extract.isBlank()) return Optional.empty();
            return Optional.of(new WikiSummary(wikiTitle, extract, thumbnail));
        } catch (Exception e) {
            log.warn("위키피디아 조회 실패: title={}, error={}", title, e.getMessage());
            return Optional.empty();
        }
    }

    public record WikiSummary(String title, String extract, String thumbnailUrl) {}
}
