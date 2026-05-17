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

    // KO: 지도 화면을 여는 앱 내부 액션을 준비합니다.
    @Tool(description = "Prepare an internal client follow-up that opens the map screen.")
    public String openMapScreen() {
        actionContext.incrementToolCount("openMapScreen");

        ChatAction action = ChatAction.builder()
                .type("NAVIGATE")
                .target("MAP")
                .params(Map.of())
                .build();
        actionContext.addAction(action);

        log.info("[Tool:openMapScreen] 지도 화면 이동 후속 액션 생성");
        return "지도 화면 후속 액션을 준비했습니다.";
    }

    // KO: 지도 화면 검색창에 검색어를 적용하는 앱 내부 액션을 준비합니다.
    @Tool(description = "Prepare an internal client follow-up that applies a search query to the map screen.")
    public String setMapSearchQuery(
            // KO: 지도 검색창에 넣을 검색어입니다.
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

        log.info("[Tool:setMapSearchQuery] 검색어={}", sanitized);
        return "지도 검색어 적용 후속 액션을 준비했습니다.";
    }

    // KO: 지도에서 특정 꽃 위치를 강조하는 앱 내부 액션을 준비합니다.
    @Tool(description = "Prepare an internal client follow-up that highlights a flower location on the map.")
    public String showFlowerOnMap(
            // KO: 지도에서 강조할 꽃 ID입니다.
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
        return "지도 꽃 위치 강조 후속 액션을 준비했습니다.";
    }

    // KO: 지도 화면에서 특정 꽃 미리보기를 여는 앱 내부 액션을 준비합니다.
    @Tool(description = "Prepare an internal client follow-up that opens a flower preview in the map screen.")
    public String openFlowerMapPreview(
            // KO: 지도 화면에서 미리보기로 열 꽃 ID입니다.
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
        return "지도 꽃 미리보기 후속 액션을 준비했습니다.";
    }

    private String sanitizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        String trimmed = query.trim();
        return trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
    }
}
