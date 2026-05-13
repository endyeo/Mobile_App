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

    // KO: 승인된 꽃 명소 데이터를 꽃 이름, 품종, 주소, 설명 기준으로 검색합니다.
    @Tool(description = "Search FLOWER's approved flower spot database by flower name, species, address, or description.")
    public String searchFlowerSpots(
            // KO: 꽃 명소 검색어입니다. 비어 있으면 대표 승인 꽃 명소를 반환합니다.
            @ToolParam(description = "Search keyword. Blank means return representative approved flower spots.") String query
    ) {
        actionContext.markSearchInvoked();
        actionContext.incrementToolCount("flower.searchFlowerSpots");

        String sanitized = flowerToolService.sanitizeQuery(query);
        log.info("[Tool:flower.searchFlowerSpots] query={}", sanitized);
        return flowerToolService.formatFlowerSpotsForAnswer(sanitized);
    }

    // KO: 꽃 도감 화면을 여는 앱 내부 액션을 준비합니다.
    @Tool(description = "Prepare an internal client follow-up that opens FLOWER's flower book screen.")
    public String openFlowerBook() {
        actionContext.incrementToolCount("flower.openFlowerBook");

        ChatAction action = ChatAction.builder()
                .type("NAVIGATE")
                .target("FLOWER_BOOK")
                .params(Map.of())
                .build();
        actionContext.addAction(action);

        log.info("[Tool:flower.openFlowerBook] flower book navigation follow-up created");
        return "Flower book screen follow-up prepared.";
    }

    // KO: 특정 꽃 ID를 전달해 꽃 도감 화면을 여는 앱 내부 액션을 준비합니다.
    @Tool(description = "Prepare an internal client follow-up that opens FLOWER's flower book with a selected flower id.")
    public String openFlowerDetail(
            // KO: 꽃 도감 화면에 전달할 꽃 ID입니다.
            @ToolParam(description = "Flower id to pass to the flower book screen.") Long flowerId
    ) {
        actionContext.incrementToolCount("flower.openFlowerDetail");

        ChatAction action = ChatAction.builder()
                .type("NAVIGATE")
                .target("FLOWER_BOOK")
                .params(Map.of("flowerId", flowerId))
                .build();
        actionContext.addAction(action);

        log.info("[Tool:flower.openFlowerDetail] flowerId={}", flowerId);
        return "Flower detail handoff follow-up prepared.";
    }
}
