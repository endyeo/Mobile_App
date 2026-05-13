package com.flower.backend.chatbot.tool.MapAgent;

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
public class MapNavigationTools {

    private final ChatbotActionContext actionContext;

    @Tool(description = "Prepare an internal client follow-up that opens the map screen.")
    public String openMapScreen() {
        actionContext.incrementToolCount("openMapScreen");

        ChatAction action = ChatAction.builder()
                .type("NAVIGATE")
                .target("MAP")
                .params(Map.of())
                .build();
        actionContext.addAction(action);

        log.info("[Tool:openMapScreen] map navigation follow-up created");
        return "Map screen follow-up prepared.";
    }

    @Tool(description = "Prepare an internal client follow-up that applies a search query to the map screen.")
    public String setMapSearchQuery(
            @ToolParam(description = "Search query to apply in the map search field.") String query
    ) {
        actionContext.incrementToolCount("setMapSearchQuery");

        String sanitized = sanitizeQuery(query);
        ChatAction action = ChatAction.builder()
                .type("MAP_SET_SEARCH_QUERY")
                .target("MAP")
                .params(Map.of("query", sanitized))
                .build();
        actionContext.addAction(action);

        log.info("[Tool:setMapSearchQuery] query={}", sanitized);
        return "Map search query follow-up prepared.";
    }

    @Tool(description = "Prepare an internal client follow-up that highlights a flower location on the map.")
    public String showFlowerOnMap(
            @ToolParam(description = "Flower id to highlight on the map.") Long flowerId
    ) {
        actionContext.incrementToolCount("showFlowerOnMap");

        ChatAction action = ChatAction.builder()
                .type("MAP_SHOW_FLOWER")
                .target("MAP")
                .params(Map.of("flowerId", flowerId))
                .build();
        actionContext.addAction(action);

        log.info("[Tool:showFlowerOnMap] flowerId={}", flowerId);
        return "Map flower highlight follow-up prepared.";
    }

    @Tool(description = "Prepare an internal client follow-up that opens a flower preview in the map screen.")
    public String openFlowerMapPreview(
            @ToolParam(description = "Flower id to preview in the map screen.") Long flowerId
    ) {
        actionContext.incrementToolCount("openFlowerMapPreview");

        ChatAction action = ChatAction.builder()
                .type("MAP_OPEN_FLOWER_PREVIEW")
                .target("MAP")
                .params(Map.of("flowerId", flowerId))
                .build();
        actionContext.addAction(action);

        log.info("[Tool:openFlowerMapPreview] flowerId={}", flowerId);
        return "Map flower preview follow-up prepared.";
    }

    private String sanitizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        String trimmed = query.trim();
        return trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
    }
}
