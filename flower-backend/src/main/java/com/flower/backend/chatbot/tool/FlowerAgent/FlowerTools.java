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

    // flower_book 테이블에서 꽃 설명과 출처만 조회합니다.
    @Tool(description = "Look up a flower's description and source from the flower_book database.")
    public String lookupFlowerDescriptionSource(
            // 꽃 이름 또는 학명 검색어입니다.
            @ToolParam(description = "Flower name or scientific name to look up.") String query) {
        actionContext.markSearchInvoked();
        actionContext.incrementToolCount("flower.lookupDescriptionSource");

        String sanitized = flowerToolService.sanitizeQuery(query);
        log.info("[Tool:flower.lookupDescriptionSource] 검색어={}", sanitized);
        return flowerToolService.formatFlowerDescriptionSourceForAnswer(sanitized);
    }

    // flower_book 테이블에서 재배 팁과 출처만 조회합니다.
    @Tool(description = "Look up a flower's grow tips and source from the flower_book database.")
    public String lookupFlowerGrowTipsSource(
            // 꽃 이름 또는 학명 검색어입니다.
            @ToolParam(description = "Flower name or scientific name to look up.") String query) {
        actionContext.markSearchInvoked();
        actionContext.incrementToolCount("flower.lookupGrowTipsSource");

        String sanitized = flowerToolService.sanitizeQuery(query);
        log.info("[Tool:flower.lookupGrowTipsSource] 검색어={}", sanitized);
        return flowerToolService.formatFlowerGrowTipsSourceForAnswer(sanitized);
    }

    // 월별 꽃 도감 데이터와 승인 꽃 명소를 조합해 추천합니다.
    @Tool(description = "Recommend seasonal flowers for a given month using FLOWER's flower book and approved flower spots.")
    public String recommendSeasonalFlowers(
            // 추천 기준 월입니다. 비어 있거나 범위를 벗어나면 현재 월을 사용합니다.
            @ToolParam(description = "Month number from 1 to 12. Uses the current month when omitted or invalid.") Integer month) {
        actionContext.markSearchInvoked();
        actionContext.incrementToolCount("flower.recommendSeasonalFlowers");

        log.info("[Tool:flower.recommendSeasonalFlowers] month={}", month);
        return flowerToolService.formatSeasonalFlowersForAnswer(month);
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
