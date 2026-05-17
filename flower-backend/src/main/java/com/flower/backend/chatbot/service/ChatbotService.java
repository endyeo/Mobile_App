package com.flower.backend.chatbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flower.backend.chatbot.dto.AgentRunTrace;
import com.flower.backend.chatbot.dto.AgentRunTrace.AgentStepTrace;
import com.flower.backend.chatbot.dto.ChatAction;
import com.flower.backend.chatbot.dto.ChatMessageRequest;
import com.flower.backend.chatbot.dto.ChatMessageResponse;
import com.flower.backend.chatbot.dto.ToolResult;
import com.flower.backend.chatbot.tool.CommunityAgent.CommunityTools;
import com.flower.backend.chatbot.tool.FlowerAgent.FlowerToolService;
import com.flower.backend.flower.Flower;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ChatbotService {

    private static final int MAX_HISTORY_MESSAGES = 12;
    private static final long SESSION_TTL_MS = 60 * 60 * 1000L; // 1시간
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final FlowerToolService flowerToolService;
    private final CommunityTools communityTools;
    private final ChatActionValidator chatActionValidator = new ChatActionValidator();
    private final ChatClient chatClient;
    private final String openAiApiKey;
    private final Map<String, SessionData> sessions = new ConcurrentHashMap<>();

    @FunctionalInterface
    public interface StreamSender {
        void send(String eventName, Object data) throws Exception;
    }

    private static class SessionData {
        final List<ChatTurn> history = new ArrayList<>();
        volatile long lastAccessTime = System.currentTimeMillis();

        void touch() { lastAccessTime = System.currentTimeMillis(); }
        boolean isExpired() { return System.currentTimeMillis() - lastAccessTime > SESSION_TTL_MS; }
    }

    public ChatbotService(
            FlowerToolService flowerToolService,
            CommunityTools communityTools,
            ObjectProvider<ChatClient.Builder> chatClientBuilderProvider,
            @Value("${chatbot.openai.api-key:}") String openAiApiKey
    ) {
        this.flowerToolService = flowerToolService;
        this.communityTools = communityTools;
        ChatClient.Builder chatClientBuilder = chatClientBuilderProvider.getIfAvailable();
        this.chatClient = chatClientBuilder == null ? null : chatClientBuilder.build();
        this.openAiApiKey = openAiApiKey == null ? "" : openAiApiKey.trim();
    }

    public ChatMessageResponse chat(ChatMessageRequest request) {
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }

        String message = request.getMessage() == null ? "" : request.getMessage().trim();
        AgentExecution execution = routeAndExecute(message, request.getContext(), null);
        ChatAction primaryAction = execution.primaryAction();
        String localContext = execution.toPromptContext();
        String reply = callSpringAi(message, sessionId, localContext, primaryAction);

        remember(sessionId, "user", message);
        remember(sessionId, "assistant", reply);

        return ChatMessageResponse.builder()
                .reply(reply)
                .action(primaryAction)
                .actions(execution.actions())
                .agentRun(execution.trace())
                .toolResults(execution.toolResults())
                .sessionId(sessionId)
                .build();
    }

    public void chatStream(ChatMessageRequest request, StreamSender sender) throws Exception {
        String sessionId = request.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }

        String message = request.getMessage() == null ? "" : request.getMessage().trim();
        sendStreamEvent(sender, "CONNECTED", Map.of("sessionId", sessionId));
        sendStatus(sender, "CONNECTED", "SSE 연결이 확인되었습니다.");

        AgentExecution execution = routeAndExecute(message, request.getContext(), sender);
        ChatAction primaryAction = execution.primaryAction();
        String localContext = execution.toPromptContext();

        sendStatus(sender, "ANSWER", "답변을 종합하고 있어요.");
        String reply = callSpringAi(message, sessionId, localContext, primaryAction);

        remember(sessionId, "user", message);
        remember(sessionId, "assistant", reply);

        ChatMessageResponse response = ChatMessageResponse.builder()
                .reply(reply)
                .action(primaryAction)
                .actions(execution.actions())
                .agentRun(execution.trace())
                .toolResults(execution.toolResults())
                .sessionId(sessionId)
                .build();

        sendStreamEvent(sender, "FINAL_ANSWER", response);
        sendStreamEvent(sender, "DONE", Map.of("reason", "completed"));
    }

    public void clearSession(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) sessions.remove(sessionId);
    }

    private AgentExecution routeAndExecute(
            String message,
            ChatMessageRequest.LocationContext location,
            StreamSender streamSender
    ) {
        sendStatus(streamSender, "CONTEXT", "AI가 맥락을 파악하고 있어요.");
        AgentPlan plan = createAgentPlan(message);
        List<RouteIntent> intents = plan.intents() == null ? List.of() : plan.intents();
        boolean flowerBookRequested = actionsContainTarget(plan.actions(), "FLOWER_BOOK");
        String route = intents.isEmpty() ? "GENERAL" : joinIntents(intents);
        List<AgentStepTrace> steps = new ArrayList<>();
        List<ToolResult> toolResults = new ArrayList<>();
        List<ChatAction> actions = new ArrayList<>();
        String keyword = plan.searchKeyword();
        if (keyword == null || keyword.isBlank()) {
            keyword = sanitizePlannerKeyword(extractKeyword(message));
        }
        List<Flower> flowerResults = List.of();
        int step = 1;

        steps.add(stepTrace(step++, "RouterAgent", "routeAndPlan", "SUCCESS",
                plan.source() + "가 필요한 검색 도구와 내부 앱 액션을 선택했습니다."));
        sendStreamEvent(streamSender, "CONTEXT_PLANNED", Map.of(
                "route", route,
                "message", "요청 맥락과 필요한 작업 계획을 확인했습니다."
        ));

        if (hasUnsupportedIntent(intents) || wantsUnsupportedFeature(message)) {
            ToolResult unsupportedResult = unsupportedToolResult(message);
            toolResults.add(unsupportedResult);
            sendStreamEvent(streamSender, "TOOL_RESULT", Map.of("toolResult", unsupportedResult));
            steps.add(stepTrace(step++, "RouterAgent", "app.unsupported", "READY",
                    "v1에서 지원하지 않는 앱 제어 요청을 차단했습니다."));
            AgentRunTrace trace = AgentRunTrace.builder()
                    .mode("SPRING_AI_ROUTER_PLANNED_LIGHTWEIGHT_AGENTIC_RAG")
                    .route(route)
                    .specialist("RouterAgent")
                    .steps(steps)
                    .build();
            return new AgentExecution(List.of(), toolResults, trace);
        }

        sendStatus(streamSender, "PLAN", "필요한 앱 도구를 고르고 있어요.");
        ChatActionValidator.ValidationResult validationResult =
                chatActionValidator.validateAndComplete(plan.actions(), intents, keyword);
        actions.addAll(validationResult.actions());

        boolean flowerIntent = hasFlowerIntent(intents);
        boolean flowerBookInfoRequested = flowerIntent
                && !flowerBookRequested
                && wantsFlowerBookInfo(message);
        if (flowerIntent && wantsSeasonalRecommendation(message)) {
            sendStatus(streamSender, "SEARCH", "이번 계절에 어울리는 꽃을 찾고 있어요.");
            ToolResult seasonalResult = flowerToolService.recommendSeasonalFlowersResult(extractRequestedMonth(message));
            toolResults.add(seasonalResult);
            sendStreamEvent(streamSender, "TOOL_RESULT", Map.of("toolResult", seasonalResult));
            steps.add(stepTrace(step++, "FlowerAgent", "flower.recommendSeasonalFlowers", "SUCCESS",
                    seasonalResult.getSummary()));
            addRepresentativeMapAction(actions, seasonalResult);
        } else if (flowerBookInfoRequested || intents.contains(RouteIntent.FLOWER_GROW)) {
            boolean allowCandidateExpansion = wantsUnknownFlowerIdentification(message)
                    && !wantsMap(message)
                    && !wantsFlowerSpotOrEvent(message);
            if (wantsFlowerGrowTips(message) || intents.contains(RouteIntent.FLOWER_GROW)) {
                sendStatus(streamSender, "SEARCH", "꽃 재배 정보를 검색하고 있어요.");
                ToolResult growTipsResult = flowerToolService.lookupFlowerGrowTipsSourceResult(
                        keyword,
                        allowCandidateExpansion
                );
                toolResults.add(growTipsResult);
                sendStreamEvent(streamSender, "TOOL_RESULT", Map.of("toolResult", growTipsResult));
                steps.add(stepTrace(step++, "FlowerAgent", "lookupFlowerGrowTipsSource", "SUCCESS",
                        growTipsResult.getSummary()));
            } else {
                sendStatus(streamSender, "SEARCH", "꽃 정보를 검색하고 있어요.");
                ToolResult descriptionResult = flowerToolService.lookupFlowerDescriptionSourceResult(
                        keyword,
                        allowCandidateExpansion
                );
                toolResults.add(descriptionResult);
                sendStreamEvent(streamSender, "TOOL_RESULT", Map.of("toolResult", descriptionResult));
                steps.add(stepTrace(step++, "FlowerAgent", "lookupFlowerDescriptionSource", "SUCCESS",
                        descriptionResult.getSummary()));
            }
        } else if (flowerIntent && !flowerBookRequested) {
            sendStatus(streamSender, "SEARCH", "꽃 정보를 검색하고 있어요.");
            flowerResults = searchFlowers(keyword);
            ToolResult flowerResult = flowerToolResult(keyword, flowerResults);
            toolResults.add(flowerResult);
            sendStreamEvent(streamSender, "TOOL_RESULT", Map.of("toolResult", flowerResult));
            steps.add(stepTrace(step++, "FlowerAgent", "searchFlowerSpots", "SUCCESS",
                    "승인된 꽃 명소 후보 " + flowerResults.size() + "개를 확인했습니다."));
            addRepresentativeMapAction(actions, flowerResult);
        }

        if (intents.contains(RouteIntent.COMMUNITY) && !actionsContainTarget(actions, "COMMUNITY_COMPOSE")) {
            sendStatus(streamSender, "SEARCH", "커뮤니티 글을 검색하고 있어요.");
            ToolResult communityResult = communityTools.searchPosts(keyword);
            toolResults.add(communityResult);
            sendStreamEvent(streamSender, "TOOL_RESULT", Map.of("toolResult", communityResult));
            steps.add(stepTrace(step++, "RouterAgent", "searchCommunityPosts", "SUCCESS",
                    communityResult.getSummary()));
        }

        for (ChatAction action : actions) {
            steps.add(stepTrace(step++, agentFor(action), toolFor(action), "READY",
                    plan.source() + "가 클라이언트 후속 액션을 계획했습니다."));
        }
        if (!actions.isEmpty()) {
            sendStatus(streamSender, "ACTION", actionStatusMessage(actions));
            sendStreamEvent(streamSender, "ACTION", Map.of(
                    "action", actions.get(0),
                    "actions", actions
            ));
        }

        AgentRunTrace trace = AgentRunTrace.builder()
                .mode("SPRING_AI_ROUTER_PLANNED_LIGHTWEIGHT_AGENTIC_RAG")
                .route(route)
                .specialist("RouterAgent")
                .steps(steps)
                .build();
        return new AgentExecution(actions, toolResults, trace);
    }

    private boolean hasFlowerIntent(List<RouteIntent> intents) {
        return intents.contains(RouteIntent.FLOWER) || intents.contains(RouteIntent.FLOWER_GROW);
    }

    private boolean hasUnsupportedIntent(List<RouteIntent> intents) {
        return intents.contains(RouteIntent.QUEST) || intents.contains(RouteIntent.SHOP);
    }

    private ToolResult unsupportedToolResult(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        String feature = containsAny(lower, "shop", "store", "buy", "purchase", "상점", "상품", "아이템", "구매", "사줘")
                ? "상점/구매"
                : "퀘스트/인증";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("context", feature + " 요청은 v1 AI 꽃 도우미에서 직접 실행하지 않습니다. 게시, 구매, 포인트 지급, 인증 같은 쓰기 작업은 사용자가 해당 화면에서 직접 해야 합니다.");
        data.put("feature", feature);
        return ToolResult.builder()
                .tool("app.unsupported")
                .status("READY")
                .summary(feature + " 요청은 v1에서 지원하지 않습니다.")
                .data(data)
                .build();
    }

    private void addRepresentativeMapAction(List<ChatAction> actions, ToolResult result) {
        if (actions.stream().noneMatch(action -> "MAP".equals(action.getTarget()))) {
            return;
        }
        Map<String, Object> data = result.getData();
        if (data == null || !(data.get("items") instanceof List<?> items) || items.isEmpty()) {
            return;
        }
        Object first = items.get(0);
        if (!(first instanceof Map<?, ?> row)) {
            return;
        }
        Object flowerId = row.get("flowerId");
        if (flowerId instanceof Number || (flowerId instanceof String text && text.matches("\\d+"))) {
            ChatAction action = ChatAction.builder()
                    .type("MAP_SHOW_FLOWER")
                    .target("MAP")
                    .params(Map.of("flowerId", flowerId))
                    .build();
            if (!containsAction(actions, action)) {
                actions.add(action);
            }
            return;
        }
        Object name = row.get("name");
        if (name != null && !name.toString().isBlank()) {
            ChatAction action = ChatAction.builder()
                    .type("MAP_SET_SEARCH_QUERY")
                    .target("MAP")
                    .params(Map.of("query", sanitizePlannerKeyword(name.toString())))
                    .build();
            if (!containsAction(actions, action)) {
                actions.add(action);
            }
        }
    }

    private boolean containsAction(List<ChatAction> actions, ChatAction candidate) {
        return actions.stream().anyMatch(action ->
                safeEquals(action.getType(), candidate.getType())
                        && safeEquals(action.getTarget(), candidate.getTarget())
                        && String.valueOf(action.getParams()).equals(String.valueOf(candidate.getParams())));
    }

    private boolean safeEquals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private boolean wantsMap(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return containsAny(lower,
                "map", "near", "nearby", "route",
                "\uC9C0\uB3C4", "\uC704\uCE58", "\uADFC\uCC98", "\uC8FC\uBCC0", "\uAE38");
    }

    private boolean wantsFlowerBookInfo(String message) {
        return wantsFlowerDescriptionInfo(message) || wantsFlowerGrowTips(message) || wantsUnknownFlowerIdentification(message);
    }

    private boolean wantsFlowerDescriptionInfo(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return containsAny(lower,
                "description", "info", "about", "what is",
                "\uC815\uBCF4", "\uC124\uBA85", "\uD2B9\uC9D5", "\uBB50\uC57C", "\uBB34\uC5C7",
                "\uC54C\uB824\uC918", "\uC5B4\uB5A4 \uAF43");
    }

    private boolean wantsFlowerGrowTips(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return containsAny(lower,
                "grow", "care", "tips", "raise",
                "\uD0A4\uC6B0\uAE30", "\uD0A4\uC6B0\uB294", "\uC7AC\uBC30", "\uAD00\uB9AC",
                "\uBB3C\uC8FC\uAE30", "\uD587\uBE5B", "\uD1A0\uC591", "\uD301", "\uBC29\uBC95");
    }

    private boolean wantsUnknownFlowerIdentification(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return containsAny(lower,
                "\uC774\uB984\uC774 \uBB50", "\uC774\uB984\uC774 \uBB34\uC5C7", "\uC774\uB984\uC744 \uBAA8\uB974",
                "\uBB50\uC9C0", "\uBB54\uC9C0", "\uBB34\uC2A8 \uAF43", "\uC5B4\uB5A4 \uAF43",
                "\uBAA8\uB974\uACA0", "\uC774\uB984 \uBAA8\uB974",
                "what flower", "which flower", "do not know the name", "don't know the name");
    }

    private boolean wantsFlowerSpotOrEvent(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return containsAny(lower,
                "\uCD95\uC81C", "\uD589\uC0AC", "\uC774\uBCA4\uD2B8", "\uBA85\uC18C", "\uC7A5\uC18C",
                "\uCD94\uCC9C", "\uADFC\uCC98", "\uC8FC\uBCC0", "\uC5B4\uB514", "\uAC00\uB294 \uAE38",
                "festival", "event", "place", "spot", "nearby", "recommend");
    }

    private boolean wantsSeasonalRecommendation(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return containsAny(lower,
                "season", "seasonal", "month", "monthly", "recommend",
                "계절", "이번 달", "이번달", "월에", "월 꽃", "월의 꽃", "봄", "여름", "가을", "겨울",
                "추천", "볼 만한", "볼만한", "피는 꽃");
    }

    private Integer extractRequestedMonth(String message) {
        String value = message == null ? "" : message;
        for (int month = 1; month <= 12; month++) {
            if (value.contains(month + "월")) {
                return month;
            }
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "spring", "봄")) {
            return 4;
        }
        if (containsAny(lower, "summer", "여름")) {
            return 7;
        }
        if (containsAny(lower, "fall", "autumn", "가을")) {
            return 10;
        }
        if (containsAny(lower, "winter", "겨울")) {
            return 1;
        }
        return LocalDate.now().getMonthValue();
    }

    private boolean wantsCommunity(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return containsAny(lower, "community", "review", "post", "커뮤니티", "후기", "게시글", "글");
    }

    private boolean wantsCommunityCompose(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return containsAny(lower, "write", "create post", "compose", "작성", "써줘", "글쓰기", "올리고");
    }

    private boolean wantsWalk(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return containsAny(lower, "walk", "step", "pedometer", "산책", "걸음", "만보기", "포인트");
    }

    private boolean wantsSaved(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return containsAny(lower, "saved", "bookmark", "bookmarks", "저장", "저장됨", "북마크");
    }

    private boolean wantsFlowerBookOpen(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return containsAny(lower, "book", "flower book", "dictionary", "도감", "꽃도감", "상세");
    }

    private boolean wantsUnsupportedFeature(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return containsAny(lower,
                "shop", "store", "buy", "purchase", "quest", "mission",
                "상점", "상품", "아이템", "구매", "사줘", "퀘스트", "미션", "인증", "포인트 지급");
    }

    private boolean mentionsFlower(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return containsAny(lower,
                "flower", "bloom", "rose", "hydrangea", "cherry blossom",
                "꽃", "개화", "꽃말", "벚꽃", "장미", "수국", "튤립", "해바라기", "라벤더");
    }

    private AgentPlan createAgentPlan(String message) {
        if (!openAiApiKey.isBlank() && chatClient != null) {
            try {
                String content = chatClient.prompt()
                        .system(planningSystemPrompt())
                        .user(message == null ? "" : message)
                        .call()
                        .content();
                AgentPlan plan = parseAgentPlan(content);
                if (!plan.intents().isEmpty() || !plan.actions().isEmpty()) {
                    return plan;
                }
            } catch (Exception e) {
                log.warn("AI 계획 JSON 생성 실패. 로컬 플래너로 전환합니다: {}", e.getMessage());
            }
        }
        return fallbackAgentPlan(message);
    }

    private String planningSystemPrompt() {
        // 이 프롬프트는 AIPlanner가 사용자 말을 JSON 계획으로만 바꾸게 하는 지시문이다.
        // GENERAL은 인사/감사/잡담/일반 질문처럼 앱 화면을 움직일 필요가 없는 대화를 뜻한다.
        // action은 실제 Flutter 앱 제어 명령이므로, 실행할 도구가 없으면 반드시 빈 배열을 반환해야 한다.
        // MAP intent는 지도 관련 의도 분류이고, NAVIGATE/MAP action은 실제 지도 화면 이동 명령이다.
        // 프롬프트는 AI의 계획 생성을 유도하고, 서버 validator는 최종 안전장치 역할을 한다.
        return """
                You are FLOWER's RouterAI and specialist planner.
                Return only valid JSON. Do not wrap it in markdown.
                Schema:
                {
                  "intents": ["GENERAL" | "MAP" | "FLOWER" | "FLOWER_GROW" | "COMMUNITY" | "WALK" | "SAVED" | "QUEST" | "SHOP"],
                  "searchKeyword": "optional keyword, empty string if the user only wants screen navigation",
                  "actions": [
                    {"type":"NAVIGATE","target":"MAP|COMMUNITY|COMMUNITY_COMPOSE|WALK|FLOWER_BOOK|SAVED","params":{}},
                    {"type":"MAP_SET_SEARCH_QUERY","target":"MAP","params":{"query":"flower name only"}},
                    {"type":"NAVIGATE","target":"COMMUNITY_COMPOSE","params":{}}
                  ]
                }
                Rules:
                - For greetings, thanks, small talk, capability questions, and general conversation, return intents ["GENERAL"], searchKeyword "", and actions [].
                - If there is no appropriate app-control action to run, return actions [].
                - If the user asks for flower facts, descriptions, features, flower language, or identification, use intent FLOWER, no navigation action, and searchKeyword should be the flower name.
                - If the user asks for flower care, growing, watering, sunlight, soil, or grow tips, use intent FLOWER_GROW, no navigation action, and searchKeyword should be the flower name.
                - If the user asks for seasonal or monthly flower recommendations, use intent FLOWER and searchKeyword "" unless a flower name is specified.
                - If the user describes a flower but says they do not know its name, use intent FLOWER, no navigation action, and keep the descriptive phrase as searchKeyword.
                - If the user asks about a flower festival, event, spot, place, nearby area, recommendation, or map, do not treat it as flower-book identification.
                - Do not create any NAVIGATE action unless the user explicitly asks to open, show, move to, or view a screen.
                - Do not create NAVIGATE MAP unless the user explicitly asks for a map, location, nearby places, route, directions, or path.
                - If the user only asks to open or view the map, use NAVIGATE MAP only and searchKeyword must be "".
                - Use MAP_SET_SEARCH_QUERY only when the user names a flower or flower place to find on the map.
                - If the user wants to write or create a community post, use NAVIGATE COMMUNITY_COMPOSE only.
                - Do not generate community post content or drafts.
                - If the user asks to open saved/bookmarked items, use NAVIGATE SAVED.
                - Shop, purchase, quest, mission, point grant, and verification actions are not supported in v1. Return intent SHOP or QUEST with actions [].
                - For Korean flower names, preserve the full name such as "벚꽃"; remove particles such as "에서".
                - Do not invent actions outside the schema.
                """;
    }

    private AgentPlan parseAgentPlan(String content) throws Exception {
        if (content == null || content.isBlank()) {
            return new AgentPlan(List.of(), "", List.of(), "AIPlanner");
        }
        JsonNode root = JSON_MAPPER.readTree(content.trim());
        List<RouteIntent> intents = new ArrayList<>();
        for (JsonNode node : root.path("intents")) {
            try {
                intents.add(RouteIntent.valueOf(node.asText("").toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // Ignore unsupported planner intents.
            }
        }

        String searchKeyword = sanitizePlannerKeyword(root.path("searchKeyword").asText(""));
        List<ChatAction> actions = new ArrayList<>();
        for (JsonNode node : root.path("actions")) {
            actions.add(ChatAction.builder()
                    .type(node.path("type").asText(""))
                    .target(node.path("target").asText(""))
                    .params(readParams(node.path("params")))
                    .build());
        }
        return new AgentPlan(intents.stream().distinct().toList(), searchKeyword, actions, "AIPlanner");
    }

    private Map<String, Object> readParams(JsonNode paramsNode) {
        if (paramsNode == null || !paramsNode.isObject()) {
            return Map.of();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        paramsNode.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value.isNumber()) {
                params.put(entry.getKey(), value.numberValue());
            } else if (value.isBoolean()) {
                params.put(entry.getKey(), value.booleanValue());
            } else if (!value.isNull()) {
                params.put(entry.getKey(), value.asText());
            }
        });
        return params;
    }

    private AgentPlan fallbackAgentPlan(String message) {
        String keyword = sanitizePlannerKeyword(extractKeyword(message));
        List<RouteIntent> intents = new ArrayList<>();
        List<ChatAction> actions = new ArrayList<>();

        if (wantsUnsupportedFeature(message)) {
            intents.add(wantsShopRequest(message) ? RouteIntent.SHOP : RouteIntent.QUEST);
            return new AgentPlan(intents, keyword, actions, "FallbackPlanner");
        }
        boolean communityIntent = wantsCommunity(message);
        boolean communityCompose = communityIntent && wantsCommunityCompose(message);
        if (wantsMap(message)) {
            intents.add(RouteIntent.MAP);
            actions.add(navigateAction("MAP", Map.of()));
        }
        if (communityIntent) {
            intents.add(RouteIntent.COMMUNITY);
            if (communityCompose) {
                actions.add(navigateAction("COMMUNITY_COMPOSE", Map.of()));
            } else {
                actions.add(navigateAction("COMMUNITY", keyword.isBlank() ? Map.of() : Map.of("query", keyword)));
            }
        }
        if (wantsWalk(message)) {
            intents.add(RouteIntent.WALK);
            actions.add(navigateAction("WALK", Map.of()));
        }
        if (wantsSaved(message)) {
            intents.add(RouteIntent.SAVED);
            actions.add(navigateAction("SAVED", Map.of()));
        }
        if (wantsFlowerGrowTips(message)) {
            intents.add(RouteIntent.FLOWER_GROW);
        } else if (!communityCompose && (!communityIntent || wantsMap(message) || wantsSeasonalRecommendation(message) || wantsFlowerBookInfo(message))
                && (mentionsFlower(message) || wantsFlowerBookInfo(message) || wantsSeasonalRecommendation(message))) {
            intents.add(RouteIntent.FLOWER);
        }
        if (wantsFlowerBookOpen(message) && !wantsMap(message)) {
            actions.add(navigateAction("FLOWER_BOOK", keyword.isBlank() ? Map.of() : Map.of("query", keyword)));
        }
        if (intents.contains(RouteIntent.MAP) && hasFlowerIntent(intents) && !wantsSeasonalRecommendation(message) && !keyword.isBlank()) {
            actions.add(ChatAction.builder()
                    .type("MAP_SET_SEARCH_QUERY")
                    .target("MAP")
                    .params(Map.of("query", keyword))
                    .build());
        }
        if (intents.isEmpty()) {
            intents.add(RouteIntent.GENERAL);
        }
        return new AgentPlan(intents.stream().distinct().toList(), keyword, actions, "FallbackPlanner");
    }

    private ChatAction navigateAction(String target, Map<String, Object> params) {
        return ChatAction.builder()
                .type("NAVIGATE")
                .target(target)
                .params(params == null || params.isEmpty() ? Map.of() : params)
                .build();
    }

    private boolean wantsShopRequest(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return containsAny(lower, "shop", "store", "buy", "purchase", "상점", "상품", "아이템", "구매", "사줘");
    }

    private String agentFor(ChatAction action) {
        return action.getTarget() == null ? "RouterAgent" : action.getTarget() + "Agent";
    }

    private String toolFor(ChatAction action) {
        if (action.getType() == null) {
            return "unknownAction";
        }
        if ("MAP_SET_SEARCH_QUERY".equals(action.getType())) {
            return "app.openMapWithFlowerQuery";
        }
        if ("NAVIGATE".equals(action.getType())) {
            return switch (action.getTarget() == null ? "" : action.getTarget()) {
                case "FLOWER_BOOK" -> "app.openFlowerBook";
                case "COMMUNITY" -> "app.openCommunityWithQuery";
                case "COMMUNITY_COMPOSE" -> "app.openCommunityComposer";
                case "MAP" -> "app.openMap";
                case "WALK" -> "app.openWalk";
                case "SAVED" -> "app.openSaved";
                default -> action.getType();
            };
        }
        return action.getType();
    }

    private String sanitizePlannerKeyword(String keyword) {
        if (keyword == null) {
            return "";
        }
        return keyword.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private List<Flower> searchFlowers(String keyword) {
        return flowerToolService.searchFlowerSpots(keyword);
    }

    private ToolResult flowerToolResult(String keyword, List<Flower> flowers) {
        List<Map<String, Object>> rows = flowers.stream()
                .map(flowerToolService::toItem)
                .toList();
        return ToolResult.builder()
                .tool("flower.searchFlowerSpots")
                .status("SUCCESS")
                .summary("'" + displayKeyword(keyword) + "' 꽃 명소 검색 결과 " + flowers.size() + "건을 찾았습니다.")
                .data(Map.of("items", rows))
                .build();
    }

    private boolean actionsContainTarget(List<ChatAction> actions, String target) {
        return actions != null && actions.stream().anyMatch(action -> target.equals(action.getTarget()));
    }

    private String callSpringAi(String message, String sessionId, String localContext, ChatAction action) {
        if (openAiApiKey.isBlank() || chatClient == null) {
            return fallbackReply(message, localContext, action);
        }

        try {
            StringBuilder userPrompt = new StringBuilder();
            SessionData sessionData = sessions.get(sessionId);
            List<ChatTurn> history = sessionData != null ? sessionData.history : List.of();
            for (ChatTurn turn : history) {
                userPrompt.append(turn.role()).append(": ").append(turn.content()).append("\n");
            }
            userPrompt.append("\n사용자 메시지:\n")
                    .append(message)
                    .append("\n\n도구 결과와 앱 액션:\n")
                    .append(localContext);

            String content = chatClient.prompt()
                    .system(systemPrompt(action))
                    .user(userPrompt.toString())
                    .call()
                    .content();

            return content == null || content.isBlank()
                    ? fallbackReply(message, localContext, action)
                    : content.trim();
        } catch (Exception e) {
            log.warn("Spring AI 챗봇 호출 실패. 로컬 응답으로 전환합니다: {}", e.getMessage());
            return fallbackReply(message, localContext, action);
        }
    }

    private String systemPrompt(ChatAction action) {
        String actionInstruction = action == null
                ? "도구 결과에 별도 액션이 포함되지 않았다면 내부 앱 액션은 필요하지 않습니다."
                : "클라이언트에 전달할 내부 앱 액션이 준비되었습니다: " + action.getType() + " / " + action.getTarget()
                + ". 이 정보는 한국어 읽기 전용 테스트 정보로만 보여줄 수 있으며, 버튼이나 바로가기는 언급하지 마세요.";

        return """
                당신은 FLOWER 앱 안에서 동작하는 경량 Agentic RAG 챗봇입니다.
                사용자가 한국어로 쓰면 한국어로 답하고, 영어로 쓰면 영어로 답하세요.
                사용자 언어는 도구 이름, 필드명, 데이터베이스 값, 내부 컨텍스트 라벨의 언어보다 우선합니다.
                사용자가 한국어로 쓴 경우 최종 답변의 본문, 설명, 주의사항, 출처 언급을 모두 자연스러운 한국어로 작성하세요.
                "description", "growTips", "source", "Tool results", "lookup returned" 같은 영어 도구 라벨을 최종 답변에 그대로 복사하지 마세요.
                사실 근거는 제공된 도구 결과만 사용하세요.
                정확한 개화일, 위치, 게시글 내용, 구매, 작성 완료 여부를 지어내지 마세요.
                꽃 설명과 재배 팁 답변은 flower_book 도구 결과를 사용하고, 출처가 있으면 포함해야 합니다.
                월별/계절 추천은 flower.recommendSeasonalFlowers 결과를 우선 사용하고, 승인 명소가 함께 있으면 보러 갈 후보로 설명하세요.
                모호한 설명에서 꽃 후보를 확장 검색한 경우, 확정 식별이 아니라 가능성 있는 후보 기반 답변임을 설명하세요.
                꽃 도구는 꽃 명소 데이터와 꽃 도감 이동만 제공합니다. 지도 액션은 지도 도구가 처리합니다.
                커뮤니티 작성처럼 쓰기 성격의 작업에서 작성 화면을 여는 경우, 내용을 생성하거나 자동 저장하지 않고 글 작성 화면만 연다고 명확히 말하세요.
                상점, 구매, 퀘스트, 미션, 인증, 포인트 지급 같은 미지원 요청은 실행하지 말고 아직 지원하지 않는다고 말하세요.
                내부 클라이언트 후속 액션은 한국어 읽기 전용 테스트 정보로 보여줄 수 있습니다. 바로가기 버튼이나 이동 버튼이 준비됐다고 말하지 마세요.
                """ + "\n" + actionInstruction;
    }

    private String fallbackReply(String message, String localContext, ChatAction action) {
        StringBuilder reply = new StringBuilder();
        reply.append("확인한 정보를 바탕으로 안내드릴게요.\n\n");
        reply.append(localContext);
        if (action != null && "NAVIGATE".equals(action.getType()) && "COMMUNITY_COMPOSE".equals(action.getTarget())) {
            reply.append("\n\n게시글 작성 화면을 열도록 준비했습니다. 글 내용은 생성하거나 저장하지 않았습니다.");
        }
        return reply.toString();
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String extractKeyword(String message) {
        String cleaned = message == null ? "" : message.trim();
        String[] noise = {
                "지도", "위치", "추천", "알려줘", "보여줘", "찾아줘", "찾아", "검색", "어디", "근처", "주변", "길", "안내",
                "볼래요", "볼래", "보기", "보고싶", "보고 싶",
                "에서", "으로", "로", "을", "를", "은", "는", "이", "가",
                "커뮤니티", "게시글", "후기", "글", "작성", "써줘", "초안", "법",
                "flowers", "flower", "nearby", "near", "map", "recommend", "show", "tell", "me", "post",
                "community", "on", "the", "a", "an", "to", "please"
        };
        for (String word : noise) {
            cleaned = cleaned.replace(word, " ");
        }
        String[] unicodeNoise = {
                "\uC9C0\uB3C4", "\uC704\uCE58", "\uCD94\uCC9C", "\uC54C\uB824\uC918",
                "\uBCF4\uC5EC\uC918", "\uC774\uB3D9", "\uC5F4\uC5B4", "\uC5B4\uB514",
                "\uADFC\uCC98", "\uC8FC\uBCC0", "\uAE38", "\uC548\uB0B4", "\uB3C4\uAC10",
                "\uBA85\uC18C", "\uBCFC\uB798\uC694", "\uBCFC\uB798", "\uBCF4\uAE30",
                "\uC815\uBCF4", "\uC124\uBA85", "\uD2B9\uC9D5", "\uD0A4\uC6B0\uAE30",
                "\uD0A4\uC6B0\uB294", "\uC7AC\uBC30", "\uAD00\uB9AC", "\uBB3C\uC8FC\uAE30",
                "\uD587\uBE5B", "\uD1A0\uC591", "\uD301", "\uBC29\uBC95",
                "\uCC3E\uC544\uC918", "\uCC3E\uC544", "\uAC80\uC0C9", "\uBC95",
                "\uC5D0\uC11C", "\uC73C\uB85C", "\uB85C", "\uC744", "\uB97C", "\uC740", "\uB294", "\uC774", "\uAC00"
        };
        for (String word : unicodeNoise) {
            cleaned = cleaned.replace(word, " ");
        }
        cleaned = cleaned.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.length() > 40 ? "" : cleaned;
    }

    private String joinIntents(List<RouteIntent> intents) {
        return intents.stream().map(Enum::name).reduce((left, right) -> left + "_" + right).orElse("GENERAL");
    }

    private String displayKeyword(String keyword) {
        return keyword == null || keyword.isBlank() ? "전체" : keyword;
    }

    private void sendStatus(StreamSender streamSender, String stage, String message) {
        sendStreamEvent(streamSender, "STATUS", Map.of(
                "stage", stage,
                "message", message
        ));
    }

    private void sendStreamEvent(StreamSender streamSender, String eventName, Object data) {
        if (streamSender == null) {
            return;
        }
        try {
            streamSender.send(eventName, data);
        } catch (Exception e) {
            throw new IllegalStateException("챗봇 스트림 이벤트 전송 실패: " + eventName, e);
        }
    }

    private String actionStatusMessage(List<ChatAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return "앱 액션을 준비하고 있어요.";
        }
        boolean mapAction = actions.stream().anyMatch(action -> "MAP".equals(action.getTarget()));
        if (mapAction) {
            return "지도 화면 이동을 준비하고 있어요.";
        }
        boolean communityAction = actions.stream().anyMatch(action ->
                action.getTarget() != null && action.getTarget().startsWith("COMMUNITY"));
        if (communityAction) {
            return "커뮤니티 화면 이동을 준비하고 있어요.";
        }
        return "앱 화면 이동을 준비하고 있어요.";
    }

    private AgentStepTrace stepTrace(int step, String agent, String tool, String status, String message) {
        return AgentStepTrace.builder()
                .step(step)
                .agent(agent)
                .tool(tool)
                .status(status)
                .message(message)
                .build();
    }

    @Scheduled(fixedDelay = 300_000) // 5분마다 만료 세션 정리
    public void cleanExpiredSessions() {
        int before = sessions.size();
        sessions.entrySet().removeIf(e -> e.getValue().isExpired());
        int removed = before - sessions.size();
        if (removed > 0) log.info("만료 세션 {}개 정리 (남은 세션: {})", removed, sessions.size());
    }

    private void remember(String sessionId, String role, String content) {
        SessionData data = sessions.computeIfAbsent(sessionId, key -> new SessionData());
        data.touch();
        data.history.add(new ChatTurn(role, content));
        while (data.history.size() > MAX_HISTORY_MESSAGES) {
            data.history.remove(0);
        }
    }

    private record AgentPlan(
            List<RouteIntent> intents,
            String searchKeyword,
            List<ChatAction> actions,
            String source
    ) {
    }

    private record AgentExecution(
            List<ChatAction> actions,
            List<ToolResult> toolResults,
            AgentRunTrace trace
    ) {
        private ChatAction primaryAction() {
            return actions.isEmpty() ? null : actions.get(0);
        }

        private String toPromptContext() {
            StringBuilder context = new StringBuilder();
            context.append("에이전트 실행 경로: ").append(trace.getRoute()).append("\n");
            context.append("실행 단계:\n");
            for (AgentStepTrace step : trace.getSteps()) {
                context.append("- ")
                        .append(step.getAgent())
                        .append(".")
                        .append(step.getTool())
                        .append("\n");
            }
            context.append("\n도구 조회 결과:\n");
            for (ToolResult result : toolResults) {
                context.append(formatToolDataForAnswer(result));
            }
            if (!actions.isEmpty()) {
                context.append("\n앱에서 실행할 작업:\n");
                for (ChatAction action : actions) {
                    context.append("- ")
                            .append(koreanActionLabel(action))
                            .append("\n");
                }
            }
            return context.toString();
        }

        private String formatToolDataForAnswer(ToolResult result) {
            Map<String, Object> data = result.getData();
            if (data == null) {
                return "- " + koreanToolLabel(result.getTool()) + ": 제공된 데이터 없음.\n";
            }
            if (data.get("context") != null) {
                return data.get("context") + "\n";
            }
            if (data.get("items") instanceof List<?> items) {
                return formatItemsForAnswer(result.getTool(), data, items);
            }
            return "- " + koreanToolLabel(result.getTool()) + ": " + data + "\n";
        }

        private String formatItemsForAnswer(String tool, Map<String, Object> data, List<?> items) {
            StringBuilder context = new StringBuilder();
            if (items.isEmpty()) {
                context.append("- ").append(koreanToolLabel(tool)).append(": 조회된 데이터 없음.\n");
                if (Boolean.TRUE.equals(data.get("queryExpanded")) && data.get("candidateKeywords") instanceof List<?> candidates) {
                    context.append("  - 사용자가 꽃 이름을 특정하지 않아 후보 꽃으로 확장 검색함: ")
                            .append(candidates)
                            .append("\n");
                }
                return context.toString();
            }
            context.append("- ").append(koreanToolLabel(tool)).append(" 데이터:\n");
            if (Boolean.TRUE.equals(data.get("queryExpanded")) && data.get("candidateKeywords") instanceof List<?> candidates) {
                context.append("  - 사용자가 꽃 이름을 특정하지 않아 후보 꽃으로 확장 검색함: ")
                        .append(candidates)
                        .append("\n");
            }
            for (Object item : items) {
                if (item instanceof Map<?, ?> row) {
                    context.append("  - ");
                    appendIfPresent(context, row, "name", "이름");
                    appendIfPresent(context, row, "scientificName", "학명");
                    appendIfPresent(context, row, "description", "설명");
                    appendIfPresent(context, row, "growTips", "재배 팁");
                    appendIfPresent(context, row, "source", "출처");
                    appendIfPresent(context, row, "bloomDate", "개화");
                    appendIfPresent(context, row, "flowerLanguage", "꽃말");
                    appendIfPresent(context, row, "spotCount", "승인 명소 수");
                    appendIfPresent(context, row, "representativeSpotName", "대표 명소");
                    appendIfPresent(context, row, "address", "주소");
                    appendIfPresent(context, row, "bloomStart", "개화 시작");
                    appendIfPresent(context, row, "bloomEnd", "개화 종료");
                    context.append("\n");
                } else {
                    context.append("  - ").append(item).append("\n");
                }
            }
            return context.toString();
        }

        private String koreanToolLabel(String tool) {
            return switch (tool) {
                case "flower.lookupDescriptionSource" -> "꽃 설명 조회";
                case "flower.lookupGrowTipsSource" -> "꽃 재배 팁 조회";
                case "flower.searchFlowerSpots" -> "꽃 명소 조회";
                case "flower.recommendSeasonalFlowers" -> "계절 꽃 추천";
                case "community.searchPosts", "searchCommunityPosts" -> "커뮤니티 글 조회";
                case "app.unsupported" -> "지원하지 않는 요청";
                default -> tool;
            };
        }

        private void appendIfPresent(StringBuilder context, Map<?, ?> row, String key, String label) {
            Object value = row.get(key);
            if (value == null || value.toString().isBlank() || "-".equals(value.toString())) {
                return;
            }
            if (context.charAt(context.length() - 1) != ' ') {
                context.append(", ");
            }
            context.append(label).append("=").append(value);
        }

        private String koreanActionLabel(ChatAction action) {
            String type = action.getType() == null ? "" : action.getType();
            String target = action.getTarget() == null ? "" : action.getTarget();
            Map<String, Object> params = action.getParams() == null ? Map.of() : action.getParams();
            String base = switch (type) {
                case "NAVIGATE" -> targetLabel(target) + " 화면 열기";
                case "MAP_SET_SEARCH_QUERY" -> "지도 검색어 적용";
                case "MAP_SHOW_FLOWER" -> "지도에서 꽃 위치 표시";
                case "MAP_OPEN_FLOWER_PREVIEW" -> "지도에서 꽃 미리보기 열기";
                case "PREPARE_DRAFT" -> "커뮤니티 글 작성 화면 열기";
                default -> type + " / " + target;
            };
            return params.isEmpty() ? base : base + " " + params;
        }

        private String targetLabel(String target) {
            return switch (target) {
                case "MAP" -> "지도";
                case "COMMUNITY" -> "커뮤니티";
                case "COMMUNITY_COMPOSE" -> "커뮤니티 글 작성";
                case "WALK" -> "산책";
                case "FLOWER_BOOK", "FLOWER" -> "꽃 도감";
                case "SAVED" -> "저장됨";
                default -> target.isBlank() ? "대상" : target;
            };
        }
    }

    private record ChatTurn(String role, String content) {
    }
}
