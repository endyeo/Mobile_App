package com.flower.backend.chatbot.tool.FlowerAgent;

import com.flower.backend.chatbot.dto.ToolResult;
import com.flower.backend.flower.Flower;
import com.flower.backend.flower.repository.FlowerRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FlowerToolService {

    private static final int DEFAULT_LIMIT = 5;
    private final FlowerRepository flowerRepository;

    public List<Flower> searchFlowerSpots(String query) {
        String sanitized = sanitizeQuery(query);
        List<Flower> results = sanitized.isBlank()
                ? flowerRepository.findApproved()
                : flowerRepository.searchApprovedByKeyword(sanitized);
        return results.stream().limit(DEFAULT_LIMIT).toList();
    }

    public ToolResult searchFlowerSpotsResult(String query) {
        List<Flower> flowers = searchFlowerSpots(query);
        List<Map<String, Object>> items = flowers.stream()
                .map(this::toItem)
                .toList();

        return ToolResult.builder()
                .tool("flower.searchFlowerSpots")
                .status("SUCCESS")
                .summary("'" + displayQuery(query) + "' flower spot search returned " + flowers.size() + " result(s).")
                .data(Map.of("items", items))
                .build();
    }

    public String formatFlowerSpotsForAnswer(String query) {
        List<Flower> flowers = searchFlowerSpots(query);
        String displayQuery = displayQuery(query);
        if (flowers.isEmpty()) {
            return "'" + displayQuery + "' flower spot search returned no approved records.";
        }

        StringBuilder result = new StringBuilder();
        result.append("'").append(displayQuery).append("' approved flower spots:\n");
        for (Flower flower : flowers) {
            result.append("- id=").append(flower.getId())
                    .append(", name=").append(nullToDash(flower.getName()))
                    .append(", species=").append(nullToDash(flower.getSpecies()))
                    .append(", status=").append(flower.getStatus() == null ? "-" : flower.getStatus().name())
                    .append(", address=").append(nullToDash(flower.getAddress()))
                    .append(", bloom=").append(nullToDash(flower.getBloomStart()))
                    .append(" ~ ").append(nullToDash(flower.getBloomEnd()));
            if (flower.getDescription() != null && !flower.getDescription().isBlank()) {
                result.append(", description=").append(flower.getDescription());
            }
            result.append("\n");
        }
        return result.toString();
    }

    public Map<String, Object> toItem(Flower flower) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("flowerId", flower.getId());
        item.put("name", nullToDash(flower.getName()));
        item.put("species", nullToDash(flower.getSpecies()));
        item.put("status", flower.getStatus() == null ? "-" : flower.getStatus().name());
        item.put("address", nullToDash(flower.getAddress()));
        item.put("bloomStart", nullToDash(flower.getBloomStart()));
        item.put("bloomEnd", nullToDash(flower.getBloomEnd()));
        item.put("description", nullToDash(flower.getDescription()));
        item.put("lat", flower.getLat());
        item.put("lng", flower.getLng());
        return item;
    }

    public String sanitizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        String sanitized = query.trim();
        return sanitized.length() > 100 ? sanitized.substring(0, 100) : sanitized;
    }

    private String displayQuery(String query) {
        String sanitized = sanitizeQuery(query);
        return sanitized.isBlank() ? "all" : sanitized;
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
