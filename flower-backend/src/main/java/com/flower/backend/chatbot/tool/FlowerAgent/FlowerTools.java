package com.flower.backend.chatbot.tool.FlowerAgent;

import com.flower.backend.chatbot.dto.ChatAction;
import com.flower.backend.chatbot.tool.ChatbotActionContext;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FlowerTools {

    private final FlowerToolService flowerToolService;
    private final ChatbotActionContext actionContext;

    // 승인된 꽃 명소 데이터를 꽃 이름, 품종, 주소, 설명 기준으로 검색합니다.
    @Tool(description = "Search FLOWER's approved flower spot database by flower name, species, address, or description.")
    public String searchFlowerSpots(
            // 꽃 명소 검색어입니다. 비어 있으면 대표 승인 꽃 명소를 반환합니다.
            @ToolParam(description = "Search keyword. Blank means return representative approved flower spots.") String query) {
        actionContext.markSearchInvoked();
        actionContext.incrementToolCount("flower.searchFlowerSpots");

        String sanitized = flowerToolService.sanitizeQuery(query);
        log.info("[Tool:flower.searchFlowerSpots] 검색어={}", sanitized);
        return flowerToolService.formatFlowerSpotsForAnswer(sanitized);
    }

    // flower_book 테이블에서 꽃 기본 정보를 조회합니다.
    @Tool(description = "Get a flower's basic information from flower_book: name, scientific name, description, source, image, and bloom/meaning fields when available.")
    public String getBasicInfo(
            // 꽃 이름 또는 학명 검색어입니다.
            @ToolParam(description = "Flower name or scientific name to look up.") String query) {
        actionContext.markSearchInvoked();
        actionContext.incrementToolCount("flower.getBasicInfo");

        String sanitized = flowerToolService.sanitizeQuery(query);
        log.info("[Tool:flower.getBasicInfo] 검색어={}", sanitized);
        return flowerToolService.formatFlowerDescriptionSourceForAnswer(sanitized);
    }

    // flower_book 테이블에서 꽃말과 개화 정보를 조회합니다.
    @Tool(description = "Get a flower's meaning and bloom timing from flower_book. Do not infer missing meaning or bloom dates.")
    public String getMeaningAndBloom(
            // 꽃 이름 또는 학명 검색어입니다.
            @ToolParam(description = "Flower name or scientific name to look up.") String query) {
        actionContext.markSearchInvoked();
        actionContext.incrementToolCount("flower.getMeaningAndBloom");

        String sanitized = flowerToolService.sanitizeQuery(query);
        log.info("[Tool:flower.getMeaningAndBloom] 검색어={}", sanitized);
        return flowerToolService.formatMeaningAndBloomForAnswer(sanitized);
    }

    // flower_book 테이블에서 재배 가이드를 조회합니다.
    @Tool(description = "Get a flower's grow guide from flower_book: grow tips, watering, sunlight, soil, management when present. Do not add generic gardening advice.")
    public String getGrowGuide(
            // 꽃 이름 또는 학명 검색어입니다.
            @ToolParam(description = "Flower name or scientific name to look up.") String query) {
        actionContext.markSearchInvoked();
        actionContext.incrementToolCount("flower.getGrowGuide");

        String sanitized = flowerToolService.sanitizeQuery(query);
        log.info("[Tool:flower.getGrowGuide] 검색어={}", sanitized);
        return flowerToolService.formatFlowerGrowTipsSourceForAnswer(sanitized);
    }

    // 월별 꽃 도감 데이터로 추천합니다.
    @Tool(description = "Recommend 3 to 5 flowers for a given month using flower_book bloom data. Place data is optional and secondary.")
    public String recommendByMonth(
            // 추천 기준 월입니다. 비어 있거나 범위를 벗어나면 현재 월을 사용합니다.
            @ToolParam(description = "Month number from 1 to 12. Uses the current month when omitted or invalid.") Integer month) {
        actionContext.markSearchInvoked();
        actionContext.incrementToolCount("flower.recommendByMonth");

        log.info("[Tool:flower.recommendByMonth] month={}", month);
        return flowerToolService.formatSeasonalFlowersForAnswer(month);
    }

    // 색상/모양/이름 모름 설명에서 꽃 후보를 추정합니다.
    @Tool(description = "Infer possible flower candidates from color, shape, or unknown-name descriptions. Return candidates only; do not identify with certainty.")
    public String inferCandidates(
            // 사용자가 입력한 색상/모양/이름 모름 설명입니다.
            @ToolParam(description = "User's flower description, such as color or shape clues.") String description) {
        actionContext.markSearchInvoked();
        actionContext.incrementToolCount("flower.inferCandidates");

        String sanitized = flowerToolService.sanitizeQuery(description);
        log.info("[Tool:flower.inferCandidates] 설명={}", sanitized);
        return flowerToolService.formatCandidateInferenceForAnswer(sanitized);
    }

    // 이전 도구명 호환용입니다.
    @Tool(description = "Compatibility wrapper for flower.getBasicInfo.")
    public String lookupFlowerDescriptionSource(
            @ToolParam(description = "Flower name or scientific name to look up.") String query) {
        actionContext.incrementToolCount("flower.lookupDescriptionSource");
        return getBasicInfo(query);
    }

    // 이전 도구명 호환용입니다.
    @Tool(description = "Compatibility wrapper for flower.getGrowGuide.")
    public String lookupFlowerGrowTipsSource(
            @ToolParam(description = "Flower name or scientific name to look up.") String query) {
        actionContext.incrementToolCount("flower.lookupGrowTipsSource");
        return getGrowGuide(query);
    }

    // 이전 도구명 호환용입니다.
    @Tool(description = "Compatibility wrapper for flower.recommendByMonth.")
    public String recommendSeasonalFlowers(
            @ToolParam(description = "Month number from 1 to 12. Uses the current month when omitted or invalid.") Integer month) {
        actionContext.incrementToolCount("flower.recommendSeasonalFlowers");
        return recommendByMonth(month);
    }

    // 꽃 도감 화면을 여는 앱 내부 액션을 준비합니다.
    @Tool(description = "Prepare an internal client follow-up that opens FLOWER's flower book screen.")
    public String openFlowerBook() {
        actionContext.incrementToolCount("flower.openFlowerBook");

        ChatAction action = ChatAction.builder()
                .type("NAVIGATE")
                .target("FLOWER_BOOK")
                .params(Map.of())
                .build();
        actionContext.addAction(action);

        log.info("[Tool:flower.openFlowerBook] 꽃 도감 화면 이동 후속 액션 생성");
        return "꽃 도감 화면 후속 액션을 준비했습니다.";
    }

    // 특정 꽃 ID를 전달해 꽃 도감 화면을 여는 앱 내부 액션을 준비합니다.
    @Tool(description = "Prepare an internal client follow-up that opens FLOWER's flower book with a selected flower id.")
    public String openFlowerDetail(
            // 꽃 도감 화면에 전달할 꽃 ID입니다.
            @ToolParam(description = "Flower id to pass to the flower book screen.") Long flowerId) {
        actionContext.incrementToolCount("flower.openFlowerDetail");

        ChatAction action = ChatAction.builder()
                .type("NAVIGATE")
                .target("FLOWER_BOOK")
                .params(Map.of("flowerId", flowerId))
                .build();
        actionContext.addAction(action);

        log.info("[Tool:flower.openFlowerDetail] flowerId={}", flowerId);
        return "꽃 상세 화면 전달 후속 액션을 준비했습니다.";
    }
}
