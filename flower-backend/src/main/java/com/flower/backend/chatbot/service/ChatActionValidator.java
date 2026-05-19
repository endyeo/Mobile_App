package com.flower.backend.chatbot.service;

import com.flower.backend.chatbot.dto.ChatAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class ChatActionValidator {

    ValidationResult validateAndComplete(
            List<ChatAction> plannedActions,
            List<RouteIntent> intents,
            String keyword
    ) {
        List<ChatAction> actions = new ArrayList<>();

        for (ChatAction action : plannedActions == null ? List.<ChatAction>of() : plannedActions) {
            ChatAction normalized = normalize(action, intents, keyword);
            if (normalized == null) {
                continue;
            }
            if (isDuplicateMapNavigation(actions, normalized)) {
                continue;
            }
            actions.add(normalized);
        }

        return new ValidationResult(actions);
    }

    private ChatAction normalize(ChatAction action, List<RouteIntent> intents, String keyword) {
        if (action == null || action.getType() == null) {
            return null;
        }

        String type = action.getType().toUpperCase(Locale.ROOT);
        String target = action.getTarget() == null ? "" : action.getTarget().toUpperCase(Locale.ROOT);
        Map<String, Object> params = action.getParams() == null ? Map.of() : action.getParams();

        if ("PREPARE_DRAFT".equals(type) && "COMMUNITY".equals(target)) {
            return ChatAction.builder().type("NAVIGATE").target("COMMUNITY_COMPOSE").params(null).build();
        }
        if ("NAVIGATE".equals(type) && isAllowedNavigationTarget(target)) {
            if ("COMMUNITY_COMPOSE".equals(target)) {
                return ChatAction.builder().type(type).target(target).params(null).build();
            }
            return ChatAction.builder().type(type).target(target).params(params).build();
        }
        if ("MAP_SET_SEARCH_QUERY".equals(type) && "MAP".equals(target)) {
            Object queryValue = params.get("query");
            String query = sanitize(queryValue == null ? keyword : queryValue.toString());
            if (needsMapSearch(intents, query)) {
                return ChatAction.builder()
                        .type(type)
                        .target(target)
                        .params(Map.of("query", query))
                        .build();
            }
        }
        if (("MAP_SHOW_FLOWER".equals(type) || "MAP_OPEN_FLOWER_PREVIEW".equals(type)) && "MAP".equals(target)) {
            Object flowerId = params.get("flowerId");
            if (flowerId instanceof Number || (flowerId instanceof String text && text.matches("\\d+"))) {
                return ChatAction.builder().type(type).target(target).params(Map.of("flowerId", flowerId)).build();
            }
        }
        return null;
    }

    private boolean isAllowedNavigationTarget(String target) {
        return List.of("MAP", "COMMUNITY", "COMMUNITY_COMPOSE", "WALK", "FLOWER_BOOK", "SAVED")
                .contains(target);
    }

    private boolean needsMapSearch(List<RouteIntent> intents, String keyword) {
        return intents.contains(RouteIntent.MAP)
                && (intents.contains(RouteIntent.FLOWER)
                || intents.contains(RouteIntent.FLOWER_GROW)
                || intents.contains(RouteIntent.FESTIVAL))
                && keyword != null
                && !keyword.isBlank();
    }

    private String sanitize(String keyword) {
        if (keyword == null) {
            return "";
        }
        return keyword.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean isDuplicateMapNavigation(List<ChatAction> actions, ChatAction action) {
        return "NAVIGATE".equals(action.getType())
                && "MAP".equals(action.getTarget())
                && actions.stream().anyMatch(existing ->
                "NAVIGATE".equals(existing.getType()) && "MAP".equals(existing.getTarget()));
    }

    record ValidationResult(List<ChatAction> actions) {
    }
}
