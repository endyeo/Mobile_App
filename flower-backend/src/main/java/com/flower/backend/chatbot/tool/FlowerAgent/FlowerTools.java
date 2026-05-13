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

    @Tool(description = "Search FLOWER's approved flower spot database by flower name, species, address, or description.")
    public String searchFlowerSpots(
            @ToolParam(description = "Search keyword. Blank means return representative approved flower spots.") String query
    ) {
        actionContext.markSearchInvoked();
        actionContext.incrementToolCount("flower.searchFlowerSpots");

        String sanitized = flowerToolService.sanitizeQuery(query);
        log.info("[Tool:flower.searchFlowerSpots] query={}", sanitized);
        return flowerToolService.formatFlowerSpotsForAnswer(sanitized);
    }

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

    @Tool(description = "Prepare an internal client follow-up that opens FLOWER's flower book with a selected flower id.")
    public String openFlowerDetail(
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
