package com.flower.backend.chatbot.tool.FlowerAgent;

import com.flower.backend.chatbot.dto.ToolResult;
import com.flower.backend.flower.Flower;
import com.flower.backend.flower.FlowerBookRepository;
import com.flower.backend.flower.repository.FlowerRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FlowerToolService {

    private static final int DEFAULT_LIMIT = 5;
    private static final int BOOK_INFO_LIMIT = 3;
    private final FlowerRepository flowerRepository;
    private final FlowerBookRepository flowerBookRepository;

    // 꽃 명소 검색용 도구 데이터입니다. 지도/장소 추천 맥락에서만 사용합니다.
    public List<Flower> searchFlowerSpots(String query) {
        String sanitized = sanitizeQuery(query);
        List<Flower> results = sanitized.isBlank()
                ? flowerRepository.findApproved()
                : flowerRepository.searchApprovedByKeyword(sanitized);
        return results.stream().limit(DEFAULT_LIMIT).toList();
    }

    // 승인된 꽃 명소 검색 결과를 답변 AI와 Flutter가 읽을 수 있는 ToolResult로 변환합니다.
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

    // 꽃 정보 질문에서 사용할 description/source 조회 결과입니다.
    public ToolResult lookupFlowerDescriptionSourceResult(String query) {
        return lookupFlowerDescriptionSourceResult(query, false);
    }

    public ToolResult lookupFlowerDescriptionSourceResult(String query, boolean allowCandidateExpansion) {
        BookLookup<FlowerBookRepository.DescriptionSourceView> lookup =
                searchFlowerDescriptionSource(query, allowCandidateExpansion);
        List<Map<String, Object>> items = lookup.items().stream()
                .map(this::toDescriptionSourceItem)
                .toList();

        return ToolResult.builder()
                .tool("flower.lookupDescriptionSource")
                .status("SUCCESS")
                .summary("'" + displayQuery(query) + "' flower description lookup returned "
                        + lookup.items().size() + " result(s).")
                .data(bookLookupData(items, lookup))
                .build();
    }

    // 키우기, 재배, 관리 질문에서 사용할 growTips/source 조회 결과입니다.
    public ToolResult lookupFlowerGrowTipsSourceResult(String query) {
        return lookupFlowerGrowTipsSourceResult(query, false);
    }

    public ToolResult lookupFlowerGrowTipsSourceResult(String query, boolean allowCandidateExpansion) {
        BookLookup<FlowerBookRepository.GrowTipsSourceView> lookup =
                searchFlowerGrowTipsSource(query, allowCandidateExpansion);
        List<Map<String, Object>> items = lookup.items().stream()
                .map(this::toGrowTipsSourceItem)
                .toList();

        return ToolResult.builder()
                .tool("flower.lookupGrowTipsSource")
                .status("SUCCESS")
                .summary("'" + displayQuery(query) + "' flower grow tips lookup returned "
                        + lookup.items().size() + " result(s).")
                .data(bookLookupData(items, lookup))
                .build();
    }

    // Spring AI 도구 직접 호출 시 사용할 꽃 명소 검색 텍스트입니다.
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

    // Spring AI 도구 직접 호출 시 사용할 꽃 설명/출처 텍스트입니다.
    public String formatFlowerDescriptionSourceForAnswer(String query) {
        BookLookup<FlowerBookRepository.DescriptionSourceView> lookup = searchFlowerDescriptionSource(query, false);
        if (lookup.items().isEmpty()) {
            return "'" + displayQuery(query) + "' flower description lookup returned no records.";
        }

        StringBuilder result = new StringBuilder();
        result.append("'").append(displayQuery(query)).append("' flower descriptions:\n");
        appendExpandedCandidateNote(result, lookup);
        for (FlowerBookRepository.DescriptionSourceView flower : lookup.items()) {
            result.append("- name=").append(nullToDash(flower.getName()))
                    .append(", scientificName=").append(nullToDash(flower.getScientificName()))
                    .append(", description=").append(nullToDash(flower.getDescription()))
                    .append(", source=").append(nullToDash(flower.getSource()))
                    .append("\n");
        }
        return result.toString();
    }

    // Spring AI 도구 직접 호출 시 사용할 재배 팁/출처 텍스트입니다.
    public String formatFlowerGrowTipsSourceForAnswer(String query) {
        BookLookup<FlowerBookRepository.GrowTipsSourceView> lookup = searchFlowerGrowTipsSource(query, false);
        if (lookup.items().isEmpty()) {
            return "'" + displayQuery(query) + "' flower grow tips lookup returned no records.";
        }

        StringBuilder result = new StringBuilder();
        result.append("'").append(displayQuery(query)).append("' flower grow tips:\n");
        appendExpandedCandidateNote(result, lookup);
        for (FlowerBookRepository.GrowTipsSourceView flower : lookup.items()) {
            result.append("- name=").append(nullToDash(flower.getName()))
                    .append(", scientificName=").append(nullToDash(flower.getScientificName()))
                    .append(", growTips=").append(nullToDash(flower.getGrowTips()))
                    .append(", source=").append(nullToDash(flower.getSource()))
                    .append("\n");
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

    // flower_book 테이블 검색은 꽃 이름, 학명, 카테고리명을 기준으로 수행합니다.
    private BookLookup<FlowerBookRepository.DescriptionSourceView> searchFlowerDescriptionSource(
            String query,
            boolean allowCandidateExpansion
    ) {
        String sanitized = sanitizeQuery(query);
        if (sanitized.isBlank()) {
            return BookLookup.empty();
        }
        List<FlowerBookRepository.DescriptionSourceView> direct = flowerBookRepository.findDescriptionSourceByKeyword(
                sanitized,
                PageRequest.of(0, BOOK_INFO_LIMIT)
        );
        if (!direct.isEmpty()) {
            return new BookLookup<>(direct, List.of());
        }
        if (!allowCandidateExpansion) {
            return BookLookup.empty();
        }
        List<String> candidates = inferFlowerCandidates(sanitized);
        return new BookLookup<>(searchDescriptionCandidates(candidates), candidates);
    }

    // flower_book 테이블에서 재배 팁 답변에 필요한 grow_tips/source 컬럼만 조회합니다.
    private BookLookup<FlowerBookRepository.GrowTipsSourceView> searchFlowerGrowTipsSource(
            String query,
            boolean allowCandidateExpansion
    ) {
        String sanitized = sanitizeQuery(query);
        if (sanitized.isBlank()) {
            return BookLookup.empty();
        }
        List<FlowerBookRepository.GrowTipsSourceView> direct = flowerBookRepository.findGrowTipsSourceByKeyword(
                sanitized,
                PageRequest.of(0, BOOK_INFO_LIMIT)
        );
        if (!direct.isEmpty()) {
            return new BookLookup<>(direct, List.of());
        }
        if (!allowCandidateExpansion) {
            return BookLookup.empty();
        }
        List<String> candidates = inferFlowerCandidates(sanitized);
        return new BookLookup<>(searchGrowTipsCandidates(candidates), candidates);
    }

    private List<FlowerBookRepository.DescriptionSourceView> searchDescriptionCandidates(List<String> candidates) {
        List<FlowerBookRepository.DescriptionSourceView> results = new ArrayList<>();
        Set<Long> seenIds = new LinkedHashSet<>();
        for (String candidate : candidates) {
            for (FlowerBookRepository.DescriptionSourceView flower : flowerBookRepository.findDescriptionSourceByKeyword(
                    candidate,
                    PageRequest.of(0, 1)
            )) {
                if (seenIds.add(flower.getId())) {
                    results.add(flower);
                }
                if (results.size() >= BOOK_INFO_LIMIT) {
                    return results;
                }
            }
        }
        return results;
    }

    private List<FlowerBookRepository.GrowTipsSourceView> searchGrowTipsCandidates(List<String> candidates) {
        List<FlowerBookRepository.GrowTipsSourceView> results = new ArrayList<>();
        Set<Long> seenIds = new LinkedHashSet<>();
        for (String candidate : candidates) {
            for (FlowerBookRepository.GrowTipsSourceView flower : flowerBookRepository.findGrowTipsSourceByKeyword(
                    candidate,
                    PageRequest.of(0, 1)
            )) {
                if (seenIds.add(flower.getId())) {
                    results.add(flower);
                }
                if (results.size() >= BOOK_INFO_LIMIT) {
                    return results;
                }
            }
        }
        return results;
    }

    private List<String> inferFlowerCandidates(String query) {
        String lower = query.toLowerCase();
        if (containsAny(lower, "빨간", "빨강", "붉은", "붉", "red")) {
            return List.of("장미", "동백", "튤립", "맨드라미", "칸나");
        }
        if (containsAny(lower, "분홍", "핑크", "pink")) {
            return List.of("벚꽃", "진달래", "작약", "장미", "베고니아");
        }
        if (containsAny(lower, "노란", "노랑", "노란색", "yellow")) {
            return List.of("개나리", "해바라기", "수선화", "민들레", "금잔화");
        }
        if (containsAny(lower, "하얀", "흰", "흰색", "white")) {
            return List.of("목련", "백합", "매화", "수선화");
        }
        if (containsAny(lower, "보라", "자주", "purple", "violet")) {
            return List.of("라벤더", "라일락", "제비꽃", "도라지", "히아신스");
        }
        if (containsAny(lower, "파란", "파랑", "파란색", "blue")) {
            return List.of("히아신스", "수국", "도라지");
        }
        if (containsAny(lower, "이름", "모르", "무슨 꽃", "어떤 꽃")) {
            return List.of("장미", "튤립", "벚꽃", "동백", "수국");
        }
        return List.of();
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> bookLookupData(List<Map<String, Object>> items, BookLookup<?> lookup) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("queryExpanded", !lookup.candidateKeywords().isEmpty());
        if (!lookup.candidateKeywords().isEmpty()) {
            data.put("candidateKeywords", lookup.candidateKeywords());
        }
        return data;
    }

    private void appendExpandedCandidateNote(StringBuilder result, BookLookup<?> lookup) {
        if (!lookup.candidateKeywords().isEmpty()) {
            result.append("Candidate keywords inferred from a vague flower description: ")
                    .append(String.join(", ", lookup.candidateKeywords()))
                    .append("\n");
        }
    }

    private Map<String, Object> toDescriptionSourceItem(FlowerBookRepository.DescriptionSourceView flower) {
        Map<String, Object> item = baseFlowerBookItem(flower);
        item.put("description", nullToDash(flower.getDescription()));
        item.put("source", nullToDash(flower.getSource()));
        return item;
    }

    private Map<String, Object> toGrowTipsSourceItem(FlowerBookRepository.GrowTipsSourceView flower) {
        Map<String, Object> item = baseFlowerBookItem(flower);
        item.put("growTips", nullToDash(flower.getGrowTips()));
        item.put("source", nullToDash(flower.getSource()));
        return item;
    }

    private Map<String, Object> baseFlowerBookItem(FlowerBookRepository.DescriptionSourceView flower) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("flowerBookId", flower.getId());
        item.put("dataNo", nullToDash(flower.getDataNo()));
        item.put("name", nullToDash(flower.getName()));
        item.put("scientificName", nullToDash(flower.getScientificName()));
        return item;
    }

    private Map<String, Object> baseFlowerBookItem(FlowerBookRepository.GrowTipsSourceView flower) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("flowerBookId", flower.getId());
        item.put("dataNo", nullToDash(flower.getDataNo()));
        item.put("name", nullToDash(flower.getName()));
        item.put("scientificName", nullToDash(flower.getScientificName()));
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

    private record BookLookup<T>(List<T> items, List<String> candidateKeywords) {
        private static <T> BookLookup<T> empty() {
            return new BookLookup<>(List.of(), List.of());
        }
    }
}
