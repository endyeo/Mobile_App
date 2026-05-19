package com.flower.backend.chatbot.tool.FlowerAgent;

import com.flower.backend.chatbot.dto.ToolResult;
import com.flower.backend.flower.Flower;
import com.flower.backend.flower.FlowerBook;
import com.flower.backend.flower.FlowerBookRepository;
import com.flower.backend.flower.repository.FlowerRepository;
import java.time.LocalDate;
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
    private static final int SEASONAL_RECOMMEND_LIMIT = 5;
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
                .summary("'" + displayQuery(query) + "' 꽃 명소 검색 결과 " + flowers.size() + "건을 찾았습니다.")
                .data(Map.of("items", items))
                .build();
    }

    // 꽃 정보 질문에서 사용할 description/source 조회 결과입니다.
    public ToolResult lookupFlowerDescriptionSourceResult(String query) {
        return lookupFlowerDescriptionSourceResult(query, false);
    }

    public ToolResult lookupFlowerDescriptionSourceResult(String query, boolean allowCandidateExpansion) {
        return getBasicInfoResult(query, allowCandidateExpansion);
    }

    public ToolResult getBasicInfoResult(String query, boolean allowCandidateExpansion) {
        BookLookup<FlowerBookRepository.DescriptionSourceView> lookup =
                searchFlowerDescriptionSource(query, allowCandidateExpansion);
        List<Map<String, Object>> items = lookup.items().stream()
                .map(this::toDescriptionSourceItem)
                .toList();

        return ToolResult.builder()
                .tool("flower.getBasicInfo")
                .status("SUCCESS")
                .summary("'" + displayQuery(query) + "' 꽃 기본 정보 조회 결과 "
                        + lookup.items().size() + "건을 찾았습니다.")
                .data(bookLookupData(items, lookup))
                .build();
    }

    public ToolResult getMeaningAndBloomResult(String query, boolean allowCandidateExpansion) {
        BookLookup<FlowerBookRepository.DescriptionSourceView> lookup =
                searchFlowerDescriptionSource(query, allowCandidateExpansion);
        List<Map<String, Object>> items = lookup.items().stream()
                .map(this::toMeaningAndBloomItem)
                .toList();

        return ToolResult.builder()
                .tool("flower.getMeaningAndBloom")
                .status("SUCCESS")
                .summary("'" + displayQuery(query) + "' 꽃말/개화 정보 조회 결과 "
                        + lookup.items().size() + "건을 찾았습니다.")
                .data(bookLookupData(items, lookup))
                .build();
    }

    // 키우기, 재배, 관리 질문에서 사용할 growTips/source 조회 결과입니다.
    public ToolResult lookupFlowerGrowTipsSourceResult(String query) {
        return lookupFlowerGrowTipsSourceResult(query, false);
    }

    public ToolResult lookupFlowerGrowTipsSourceResult(String query, boolean allowCandidateExpansion) {
        return getGrowGuideResult(query, allowCandidateExpansion);
    }

    public ToolResult getGrowGuideResult(String query, boolean allowCandidateExpansion) {
        BookLookup<FlowerBookRepository.GrowTipsSourceView> lookup =
                searchFlowerGrowTipsSource(query, allowCandidateExpansion);
        List<Map<String, Object>> items = lookup.items().stream()
                .map(this::toGrowTipsSourceItem)
                .toList();

        return ToolResult.builder()
                .tool("flower.getGrowGuide")
                .status("SUCCESS")
                .summary("'" + displayQuery(query) + "' 꽃 재배 가이드 조회 결과 "
                        + lookup.items().size() + "건을 찾았습니다.")
                .data(bookLookupData(items, lookup))
                .build();
    }

    // 월별 꽃 도감 데이터와 승인 꽃 명소를 조합한 추천 결과입니다.
    public ToolResult recommendSeasonalFlowersResult(Integer month) {
        return recommendByMonthResult(month);
    }

    public ToolResult recommendByMonthResult(Integer month) {
        int normalizedMonth = normalizeMonth(month);
        List<FlowerBook> flowers = flowerBookRepository.findByBloomMonthOrderByBloomDay(normalizedMonth)
                .stream()
                .limit(SEASONAL_RECOMMEND_LIMIT)
                .toList();
        List<Map<String, Object>> items = flowers.stream()
                .map(this::toSeasonalRecommendationItem)
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("month", normalizedMonth);
        data.put("items", items);
        data.put("source", "flower_book monthly bloom data");

        return ToolResult.builder()
                .tool("flower.recommendByMonth")
                .status("SUCCESS")
                .summary(normalizedMonth + "월 꽃 추천 결과 " + flowers.size() + "건을 찾았습니다.")
                .data(data)
                .build();
    }

    public ToolResult inferCandidatesResult(String description) {
        String sanitized = sanitizeQuery(description);
        List<String> candidates = inferFlowerCandidates(sanitized);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String candidate : candidates.stream().limit(SEASONAL_RECOMMEND_LIMIT).toList()) {
            List<FlowerBookRepository.DescriptionSourceView> matches = flowerBookRepository.findDescriptionSourceByKeyword(
                    candidate,
                    PageRequest.of(0, 1)
            );
            if (matches.isEmpty()) {
                rows.add(candidateItem(candidate, sanitized, null));
            } else {
                rows.add(candidateItem(candidate, sanitized, matches.get(0)));
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("description", sanitized);
        data.put("candidates", rows);
        data.put("queryExpanded", true);
        data.put("candidateKeywords", candidates);

        return ToolResult.builder()
                .tool("flower.inferCandidates")
                .status("SUCCESS")
                .summary("'" + displayQuery(description) + "' 설명에서 꽃 후보 " + rows.size() + "건을 추정했습니다.")
                .data(data)
                .build();
    }

    // Spring AI 도구 직접 호출 시 사용할 꽃 명소 검색 텍스트입니다.
    public String formatFlowerSpotsForAnswer(String query) {
        List<Flower> flowers = searchFlowerSpots(query);
        String displayQuery = displayQuery(query);
        if (flowers.isEmpty()) {
            return "'" + displayQuery + "' 꽃 명소 검색 결과 승인된 기록이 없습니다.";
        }

        StringBuilder result = new StringBuilder();
        result.append("'").append(displayQuery).append("' 승인된 꽃 명소:\n");
        for (Flower flower : flowers) {
            result.append("- id=").append(flower.getId())
                    .append(", 이름=").append(nullToDash(flower.getName()))
                    .append(", 품종=").append(nullToDash(flower.getSpecies()))
                    .append(", 상태=").append(flower.getStatus() == null ? "-" : flower.getStatus().name())
                    .append(", 주소=").append(nullToDash(flower.getAddress()))
                    .append(", 개화=").append(nullToDash(flower.getBloomStart()))
                    .append(" ~ ").append(nullToDash(flower.getBloomEnd()));
            if (flower.getDescription() != null && !flower.getDescription().isBlank()) {
                result.append(", 설명=").append(flower.getDescription());
            }
            result.append("\n");
        }
        return result.toString();
    }

    // Spring AI 도구 직접 호출 시 사용할 꽃 설명/출처 텍스트입니다.
    public String formatFlowerDescriptionSourceForAnswer(String query) {
        BookLookup<FlowerBookRepository.DescriptionSourceView> lookup = searchFlowerDescriptionSource(query, false);
        if (lookup.items().isEmpty()) {
            return "'" + displayQuery(query) + "' 꽃 설명 조회 결과가 없습니다.";
        }

        StringBuilder result = new StringBuilder();
        result.append("'").append(displayQuery(query)).append("' 꽃 설명:\n");
        appendExpandedCandidateNote(result, lookup);
        for (FlowerBookRepository.DescriptionSourceView flower : lookup.items()) {
            result.append("- 이름=").append(nullToDash(flower.getName()))
                    .append(", 학명=").append(nullToDash(flower.getScientificName()))
                    .append(", 꽃말=").append(nullToDash(flower.getFlowerLanguage()))
                    .append(", 개화=").append(bloomDate(flower.getBloomMonth(), flower.getBloomDay()))
                    .append(", 설명=").append(nullToDash(flower.getDescription()))
                    .append(", 출처=").append(nullToDash(flower.getSource()))
                    .append("\n");
        }
        return result.toString();
    }

    // Spring AI 도구 직접 호출 시 사용할 꽃말/개화 정보 텍스트입니다.
    public String formatMeaningAndBloomForAnswer(String query) {
        BookLookup<FlowerBookRepository.DescriptionSourceView> lookup = searchFlowerDescriptionSource(query, false);
        if (lookup.items().isEmpty()) {
            return "'" + displayQuery(query) + "' 꽃말/개화 정보 조회 결과가 없습니다.";
        }

        StringBuilder result = new StringBuilder();
        result.append("'").append(displayQuery(query)).append("' 꽃말/개화 정보:\n");
        appendExpandedCandidateNote(result, lookup);
        for (FlowerBookRepository.DescriptionSourceView flower : lookup.items()) {
            result.append("- 이름=").append(nullToDash(flower.getName()))
                    .append(", 꽃말=").append(nullToDash(flower.getFlowerLanguage()))
                    .append(", 개화=").append(bloomDate(flower.getBloomMonth(), flower.getBloomDay()))
                    .append(", 출처=").append(nullToDash(flower.getSource()))
                    .append("\n");
        }
        return result.toString();
    }

    // Spring AI 도구 직접 호출 시 사용할 재배 팁/출처 텍스트입니다.
    public String formatFlowerGrowTipsSourceForAnswer(String query) {
        BookLookup<FlowerBookRepository.GrowTipsSourceView> lookup = searchFlowerGrowTipsSource(query, false);
        if (lookup.items().isEmpty()) {
            return "'" + displayQuery(query) + "' 꽃 재배 팁 조회 결과가 없습니다.";
        }

        StringBuilder result = new StringBuilder();
        result.append("'").append(displayQuery(query)).append("' 꽃 재배 팁:\n");
        appendExpandedCandidateNote(result, lookup);
        for (FlowerBookRepository.GrowTipsSourceView flower : lookup.items()) {
            result.append("- 이름=").append(nullToDash(flower.getName()))
                    .append(", 학명=").append(nullToDash(flower.getScientificName()))
                    .append(", 재배 팁=").append(nullToDash(flower.getGrowTips()))
                    .append(", 출처=").append(nullToDash(flower.getSource()))
                    .append("\n");
        }
        return result.toString();
    }

    // Spring AI 도구 직접 호출 시 사용할 월별 꽃 추천 텍스트입니다.
    public String formatSeasonalFlowersForAnswer(Integer month) {
        ToolResult result = recommendSeasonalFlowersResult(month);
        List<?> items = result.getData() == null ? List.of() : (List<?>) result.getData().getOrDefault("items", List.of());
        if (items.isEmpty()) {
            return result.getData().get("month") + "월 꽃 추천 결과가 없습니다.";
        }

        StringBuilder text = new StringBuilder();
        text.append(result.getData().get("month")).append("월 추천 꽃:\n");
        for (Object item : items) {
            if (item instanceof Map<?, ?> row) {
                text.append("- 이름=").append(row.get("name"))
                        .append(", 개화=").append(row.get("bloomDate"))
                        .append(", 꽃말=").append(nullToDash((String) row.get("flowerLanguage")))
                        .append(", 설명=").append(nullToDash((String) row.get("shortDescription")));
                if (row.get("representativeSpotName") != null) {
                    text.append(", 대표 명소=").append(row.get("representativeSpotName"))
                            .append(", 주소=").append(row.get("address"));
                }
                text.append(", 출처=").append(row.get("source")).append("\n");
            }
        }
        return text.toString();
    }

    // Spring AI 도구 직접 호출 시 사용할 후보 추정 텍스트입니다.
    public String formatCandidateInferenceForAnswer(String description) {
        ToolResult result = inferCandidatesResult(description);
        List<?> candidates = result.getData() == null
                ? List.of()
                : (List<?>) result.getData().getOrDefault("candidates", List.of());
        if (candidates.isEmpty()) {
            return "'" + displayQuery(description) + "' 설명으로 추정한 꽃 후보가 없습니다.";
        }

        StringBuilder text = new StringBuilder();
        text.append("'").append(displayQuery(description)).append("' 설명으로 추정한 꽃 후보입니다. 확정 식별은 아닙니다:\n");
        for (Object item : candidates) {
            if (item instanceof Map<?, ?> row) {
                text.append("- 후보=").append(row.get("name"))
                        .append(", 이유=").append(row.get("reason"));
                if (row.get("description") != null) {
                    text.append(", 설명=").append(row.get("description"));
                }
                if (row.get("source") != null) {
                    text.append(", 출처=").append(row.get("source"));
                }
                text.append("\n");
            }
        }
        return text.toString();
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

    private Map<String, Object> toSeasonalRecommendationItem(FlowerBook flower) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("flowerBookId", flower.getId());
        item.put("name", nullToDash(flower.getName()));
        item.put("bloomMonth", flower.getBloomMonth());
        item.put("bloomDay", flower.getBloomDay());
        item.put("bloomDate", bloomDate(flower.getBloomMonth(), flower.getBloomDay()));
        item.put("flowerLanguage", nullToDash(flower.getFlowerLanguage()));
        item.put("shortDescription", truncate(nullToDash(flower.getDescription()), 120));
        item.put("source", nullToDash(flower.getSource()));

        List<Flower> spots = searchFlowerSpots(flower.getName());
        item.put("spotCount", spots.size());
        if (!spots.isEmpty()) {
            Flower representative = spots.get(0);
            item.put("flowerId", representative.getId());
            item.put("representativeSpotName", nullToDash(representative.getName()));
            item.put("address", nullToDash(representative.getAddress()));
            item.put("lat", representative.getLat());
            item.put("lng", representative.getLng());
        }
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
            result.append("모호한 꽃 설명에서 추정한 후보 검색어: ")
                    .append(String.join(", ", lookup.candidateKeywords()))
                    .append("\n");
        }
    }

    private Map<String, Object> toDescriptionSourceItem(FlowerBookRepository.DescriptionSourceView flower) {
        Map<String, Object> item = baseFlowerBookItem(flower);
        item.put("description", nullToDash(flower.getDescription()));
        item.put("flowerLanguage", nullToDash(flower.getFlowerLanguage()));
        item.put("bloomMonth", flower.getBloomMonth());
        item.put("bloomDay", flower.getBloomDay());
        item.put("bloomDate", bloomDate(flower.getBloomMonth(), flower.getBloomDay()));
        item.put("imageUrl", nullToDash(flower.getImageUrl()));
        item.put("source", nullToDash(flower.getSource()));
        return item;
    }

    private Map<String, Object> toMeaningAndBloomItem(FlowerBookRepository.DescriptionSourceView flower) {
        Map<String, Object> item = baseFlowerBookItem(flower);
        item.put("flowerLanguage", nullToDash(flower.getFlowerLanguage()));
        item.put("bloomMonth", flower.getBloomMonth());
        item.put("bloomDay", flower.getBloomDay());
        item.put("bloomDate", bloomDate(flower.getBloomMonth(), flower.getBloomDay()));
        item.put("source", nullToDash(flower.getSource()));
        return item;
    }

    private Map<String, Object> candidateItem(
            String candidate,
            String description,
            FlowerBookRepository.DescriptionSourceView flower
    ) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", flower == null ? candidate : nullToDash(flower.getName()));
        item.put("reason", candidateReason(candidate, description));
        item.put("matchedFields", List.of("description"));
        item.put("confidenceHint", "색상/표현 기반 후보입니다. 사진이나 상세 특징 없이는 확정할 수 없습니다.");
        if (flower != null) {
            item.put("flowerBookId", flower.getId());
            item.put("scientificName", nullToDash(flower.getScientificName()));
            item.put("description", nullToDash(flower.getDescription()));
            item.put("flowerLanguage", nullToDash(flower.getFlowerLanguage()));
            item.put("bloomDate", bloomDate(flower.getBloomMonth(), flower.getBloomDay()));
            item.put("source", nullToDash(flower.getSource()));
        }
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

    private int normalizeMonth(Integer month) {
        if (month == null || month < 1 || month > 12) {
            return LocalDate.now().getMonthValue();
        }
        return month;
    }

    private String bloomDate(Integer month, Integer day) {
        if (month == null) {
            return "-";
        }
        if (day == null) {
            return month + "월";
        }
        return month + "월 " + day + "일";
    }

    private String displayQuery(String query) {
        String sanitized = sanitizeQuery(query);
        return sanitized.isBlank() ? "전체" : sanitized;
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength).trim() + "...";
    }

    private String candidateReason(String candidate, String description) {
        String lower = description == null ? "" : description.toLowerCase();
        if (containsAny(lower, "분홍", "핑크", "pink")) {
            return "분홍색 꽃 설명과 자주 연결되는 후보입니다.";
        }
        if (containsAny(lower, "하얀", "흰", "흰색", "white")) {
            return "흰색 꽃 설명과 자주 연결되는 후보입니다.";
        }
        if (containsAny(lower, "노란", "노랑", "yellow")) {
            return "노란색 꽃 설명과 자주 연결되는 후보입니다.";
        }
        if (containsAny(lower, "보라", "purple", "violet")) {
            return "보라색 꽃 설명과 자주 연결되는 후보입니다.";
        }
        return "이름을 특정하기 어려운 설명에서 비교할 만한 대표 후보입니다.";
    }

    private record BookLookup<T>(List<T> items, List<String> candidateKeywords) {
        private static <T> BookLookup<T> empty() {
            return new BookLookup<>(List.of(), List.of());
        }
    }
}
