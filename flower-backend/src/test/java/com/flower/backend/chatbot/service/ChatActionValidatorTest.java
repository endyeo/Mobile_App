package com.flower.backend.chatbot.service;

import com.flower.backend.chatbot.dto.ChatAction;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatActionValidatorTest {

    private final ChatActionValidator validator = new ChatActionValidator();

    @Test
    void acceptsMapStartRouteWithModeParam() {
        ChatAction action = action(
                "MAP_START_ROUTE",
                "MAP",
                Map.of("flowerId", 7, "mode", " TRANSIT "));

        ChatActionValidator.ValidationResult result =
                validator.validateAndComplete(List.of(action), List.of(RouteIntent.MAP), "장미");

        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().get(0).getParams())
                .containsEntry("flowerId", 7)
                .containsEntry("mode", "transit");
    }

    @Test
    void rejectsMapStartRouteWithRouteModeParam() {
        ChatAction action = action(
                "MAP_START_ROUTE",
                "MAP",
                Map.of("flowerId", 7, "routeMode", "transit"));

        ChatActionValidator.ValidationResult result =
                validator.validateAndComplete(List.of(action), List.of(RouteIntent.MAP), "장미");

        assertThat(result.actions()).isEmpty();
    }

    @Test
    void normalizesCommunityDraftToComposeNavigation() {
        ChatAction action = action("PREPARE_DRAFT", "COMMUNITY", Map.of("body", "수국 후기"));

        ChatActionValidator.ValidationResult result =
                validator.validateAndComplete(List.of(action), List.of(RouteIntent.COMMUNITY), "수국");

        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(result.actions().get(0).getTarget()).isEqualTo("COMMUNITY_COMPOSE");
        assertThat(result.actions().get(0).getParams()).isNull();
    }

    @Test
    void removesDuplicateMapNavigationActions() {
        ChatAction first = action("NAVIGATE", "MAP", Map.of());
        ChatAction second = action("NAVIGATE", "MAP", Map.of());

        ChatActionValidator.ValidationResult result =
                validator.validateAndComplete(List.of(first, second), List.of(RouteIntent.MAP), "장미");

        assertThat(result.actions()).hasSize(1);
        assertThat(result.actions().get(0).getType()).isEqualTo("NAVIGATE");
        assertThat(result.actions().get(0).getTarget()).isEqualTo("MAP");
    }

    private ChatAction action(String type, String target, Map<String, Object> params) {
        return ChatAction.builder()
                .type(type)
                .target(target)
                .params(params)
                .build();
    }
}
