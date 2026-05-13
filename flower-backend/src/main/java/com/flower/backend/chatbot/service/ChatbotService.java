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
        AgentExecution execution = routeAndExecute(message, request.getContext());
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

    public void clearSession(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) sessions.remove(sessionId);
    }

    private AgentExecution routeAndExecute(String message, ChatMessageRequest.LocationContext location) {
        AgentPlan plan = createAgentPlan(message);
        List<RouteIntent> intents = mergeRouteIntents(plan.intents(), detectIntents(message));
        boolean flowerBookRequested = intents.contains(RouteIntent.FLOWER) && wantsFlowerBook(message);
        if (flowerBookRequested && !wantsMap(message)) {
            intents = intents.stream()
                    .filter(intent -> intent != RouteIntent.MAP)
                    .toList();
            plan = new AgentPlan(
                    intents,
                    "",
                    List.of(ChatAction.builder().type("NAVIGATE").target("FLOWER_BOOK").params(Map.of()).build()),
                    plan.source()
            );
        }
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
                plan.source() + " selected the required search tools and internal client follow-ups."));

        ChatActionValidator.ValidationResult validationResult =
                chatActionValidator.validateAndComplete(plan.actions(), intents, keyword);
        actions.addAll(validationResult.actions());
        for (ChatAction action : actions) {
            steps.add(stepTrace(step++, agentFor(action), toolFor(action), "READY",
                    "Planned client-side follow-up from " + plan.source() + "."));
        }

        if (intents.contains(RouteIntent.FLOWER) && !flowerBookRequested) {
            flowerResults = searchFlowers(keyword);
            toolResults.add(flowerToolResult(keyword, flowerResults));
            steps.add(stepTrace(step++, "FlowerAgent", "searchFlowerSpots", "SUCCESS",
                    "Checked " + flowerResults.size() + " approved flower spot candidates."));
        }

        if (intents.contains(RouteIntent.COMMUNITY)) {
            ToolResult communityResult = communityTools.searchPosts(keyword);
            toolResults.add(communityResult);
            steps.add(stepTrace(step++, "RouterAgent", "searchCommunityPosts", "SUCCESS",
                    communityResult.getSummary()));
        }

        if (intents.contains(RouteIntent.MAP) && !flowerResults.isEmpty() && actions.stream().noneMatch(this::isMapFlowerAction)) {
            Long flowerId = flowerResults.get(0).getId();
            actions.add(ChatAction.builder()
                    .type("MAP_SHOW_FLOWER")
                    .target("MAP")
                    .params(Map.of("flowerId", flowerId))
                    .build());
            steps.add(stepTrace(step++, "MapAgent", "showFlowerOnMap", "READY",
                    "Selected a representative flower location for the map context."));
        }

        if (toolResults.isEmpty() && actions.isEmpty()) {
            String fallbackContext = buildDefaultContext(keyword);
            toolResults.add(ToolResult.builder()
                    .tool("buildDefaultFlowerContext")
                    .status("SUCCESS")
                    .summary("Built default flower and community context.")
                    .data(Map.of("context", fallbackContext))
                    .build());
            steps.add(stepTrace(step, "RouterAgent", "buildDefaultContext", "SUCCESS",
                    "Checked default data for a general answer."));
        }

        AgentRunTrace trace = AgentRunTrace.builder()
                .mode("SPRING_AI_ROUTER_PLANNED_LIGHTWEIGHT_AGENTIC_RAG")
                .route(route)
                .specialist("RouterAgent")
                .steps(steps)
                .build();
        return new AgentExecution(actions, toolResults, trace);
    }

    private List<RouteIntent> detectIntents(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        List<RouteIntent> intents = new ArrayList<>();
        if (containsAny(lower, "map", "지도", "위치", "길", "근처", "주변", "near", "nearby", "route")) {
            intents.add(RouteIntent.MAP);
        }
        if (containsAny(lower, "flower", "flowers", "꽃", "개화", "벚꽃", "진달래", "튤립", "추천", "명소")) {
            intents.add(RouteIntent.FLOWER);
        }
        if (containsAny(lower, "community", "커뮤니티", "게시글", "후기", "글", "post")) {
            intents.add(RouteIntent.COMMUNITY);
        }
        if (containsAny(lower, "walk", "산책", "만보기", "걸음", "포인트", "point")) {
            intents.add(RouteIntent.WALK);
        }
        if (containsAny(lower, "quest", "퀘스트", "미션", "인증")) {
            intents.add(RouteIntent.QUEST);
        }
        if (containsAny(lower, "shop", "상점", "구매", "상품", "아이템")) {
            intents.add(RouteIntent.SHOP);
        }
        if (containsAny(lower, "\uC9C0\uB3C4", "\uC704\uCE58", "\uADFC\uCC98", "\uC8FC\uBCC0", "\uAE38")) {
            intents.add(RouteIntent.MAP);
        }
        if (containsAny(lower, "\uAF43", "\uAC1C\uD654", "\uBC9A\uAF43", "\uC9C4\uB2EC\uB798", "\uD280\uB9BD",
                "\uCD94\uCC9C", "\uBA85\uC18C", "\uB3C4\uAC10", "cherry blossom", "azalea", "tulip")) {
            intents.add(RouteIntent.FLOWER);
        }
        return intents.stream().distinct().toList();
    }

    private boolean wantsWriting(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return containsAny(lower, "write", "draft", "작성", "써줘", "초안", "올려줘");
    }

    private boolean wantsFlowerBook(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return containsAny(lower,
                "flower book", "flower guide",
                "\uAF43\uB3C4\uAC10", "\uAF43 \uB3C4\uAC10", "\uB3C4\uAC10");
    }

    private boolean wantsMap(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return containsAny(lower,
                "map", "near", "nearby", "route",
                "\uC9C0\uB3C4", "\uC704\uCE58", "\uADFC\uCC98", "\uC8FC\uBCC0", "\uAE38");
    }

    private boolean wantsFlowerDetail(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return containsAny(lower, "detail", "details", "\uC0C1\uC138");
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
                log.warn("AI plan JSON generation failed. Falling back to local planner: {}", e.getMessage());
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
                  "intents": ["GENERAL" | "MAP" | "FLOWER" | "COMMUNITY" | "WALK" | "QUEST" | "SHOP"],
                  "searchKeyword": "optional keyword, empty string if the user only wants screen navigation",
                  "actions": [
                    {"type":"NAVIGATE","target":"MAP|COMMUNITY|WALK|FLOWER_BOOK|SAVED|QUEST|SHOP","params":{}},
                    {"type":"MAP_SET_SEARCH_QUERY","target":"MAP","params":{"query":"flower name only"}},
                    {"type":"PREPARE_DRAFT","target":"COMMUNITY","params":{"topic":"optional"}}
                  ]
                }
                Rules:
                - For greetings, thanks, small talk, capability questions, and general conversation, return intents ["GENERAL"], searchKeyword "", and actions [].
                - If there is no appropriate app-control action to run, return actions [].
                - Do not create any NAVIGATE action unless the user explicitly asks to open, show, move to, or view a screen.
                - Do not create NAVIGATE MAP unless the user explicitly asks for a map, location, nearby places, route, directions, or path.
                - If the user only asks to open or view the map, use NAVIGATE MAP only and searchKeyword must be "".
                - Use MAP_SET_SEARCH_QUERY only when the user names a flower or flower place to find on the map.
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
        List<RouteIntent> intents = detectIntents(message);
        String keyword = sanitizePlannerKeyword(extractKeyword(message));
        List<ChatAction> actions = new ArrayList<>();

        if (intents.contains(RouteIntent.MAP)) {
            actions.add(ChatAction.builder().type("NAVIGATE").target("MAP").params(Map.of()).build());
            if (shouldApplyMapSearch(intents, keyword)) {
                actions.add(ChatAction.builder()
                        .type("MAP_SET_SEARCH_QUERY")
                        .target("MAP")
                        .params(Map.of("query", keyword))
                        .build());
            }
        }
        if (intents.contains(RouteIntent.FLOWER) && wantsFlowerBook(message)) {
            actions.add(ChatAction.builder().type("NAVIGATE").target("FLOWER_BOOK").params(Map.of()).build());
        }
        if (intents.contains(RouteIntent.COMMUNITY)) {
            actions.add(wantsWriting(message)
                    ? communityTools.prepareDraft(keyword)
                    : communityTools.openCommunity(keyword));
        }
        if (intents.contains(RouteIntent.WALK)) {
            actions.add(ChatAction.builder().type("NAVIGATE").target("WALK").build());
        }
        if (intents.contains(RouteIntent.QUEST)) {
            actions.add(ChatAction.builder().type("NAVIGATE").target("QUEST").build());
        }
        if (intents.contains(RouteIntent.SHOP)) {
            actions.add(ChatAction.builder().type("NAVIGATE").target("SHOP").build());
        }

        return new AgentPlan(intents, keyword, actions, "FallbackPlanner");
    }

    private List<RouteIntent> mergeRouteIntents(List<RouteIntent> plannedIntents, List<RouteIntent> detectedIntents) {
        List<RouteIntent> merged = new ArrayList<>();
        if (plannedIntents != null) {
            merged.addAll(plannedIntents);
        }
        if (detectedIntents != null) {
            merged.addAll(detectedIntents);
        }
        return merged.stream().distinct().toList();
    }

    private boolean isMapFlowerAction(ChatAction action) {
        return "MAP_SHOW_FLOWER".equals(action.getType()) || "MAP_OPEN_FLOWER_PREVIEW".equals(action.getType());
    }

    private String agentFor(ChatAction action) {
        return action.getTarget() == null ? "RouterAgent" : action.getTarget() + "Agent";
    }

    private String toolFor(ChatAction action) {
        return action.getType() == null ? "unknownAction" : action.getType();
    }

    private String sanitizePlannerKeyword(String keyword) {
        if (keyword == null) {
            return "";
        }
        return keyword.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean shouldApplyMapSearch(List<RouteIntent> intents, String keyword) {
        return intents.contains(RouteIntent.MAP)
                && intents.contains(RouteIntent.FLOWER)
                && keyword != null
                && !keyword.isBlank();
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
                .summary("'" + displayKeyword(keyword) + "' flower spot search returned " + flowers.size() + " result(s).")
                .data(Map.of("items", rows))
                .build();
    }

    private String buildDefaultContext(String keyword) {
        List<Flower> flowers = searchFlowers(keyword);
        ToolResult communityResult = communityTools.searchPosts(keyword);
        return formatFlowers(flowers) + "\n\n" + formatCommunityToolResult(communityResult);
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
            userPrompt.append("\nUser message:\n")
                    .append(message)
                    .append("\n\nTool results and app actions:\n")
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
            log.warn("Spring AI chatbot call failed. Falling back to local response: {}", e.getMessage());
            return fallbackReply(message, localContext, action);
        }
    }

    private String systemPrompt(ChatAction action) {
        String actionInstruction = action == null
                ? "No internal app action is required unless the tool results include one."
                : "An internal app action is prepared for the client: " + action.getType() + " / " + action.getTarget()
                + ". You may show it as Korean read-only test information, but do not mention buttons or shortcuts.";

        return """
                You are FLOWER's in-app lightweight Agentic RAG chatbot.
                Answer in Korean when the user writes Korean; answer in English when the user writes English.
                Use only the provided tool results as factual ground truth.
                Do not invent exact bloom dates, locations, post content, purchases, or completed writes.
                Flower tools provide flower spot data and flower book navigation only; map actions are handled by map tools.
                If a write-like task is draft-only, clearly say it is prepared as a draft.
                Internal client follow-ups may be shown as Korean read-only test information. Never tell the user that a shortcut button or navigation button was prepared.
                """ + "\n" + actionInstruction;
    }

    private String fallbackReply(String message, String localContext, ChatAction action) {
        StringBuilder reply = new StringBuilder();
        reply.append("OpenAI API key is not configured, so I used local tool results.\n\n");
        reply.append(localContext);
        if (action != null && "PREPARE_DRAFT".equals(action.getType())) {
            reply.append("\n\nPrepared a draft only. No write action was executed.");
        } else if (message != null && !message.isBlank()) {
            reply.append("\n\nSet OPENAI_API_KEY to enable a natural Spring AI answer.");
        }
        return reply.toString();
    }

    private String formatFlowers(List<Flower> flowers) {
        StringBuilder context = new StringBuilder("Flower data:\n");
        if (flowers.isEmpty()) {
            context.append("- No matching flower records.\n");
            return context.toString();
        }
        for (Flower flower : flowers) {
            context.append("- ")
                    .append(nullToDash(flower.getName()))
                    .append(" / species: ").append(nullToDash(flower.getSpecies()))
                    .append(" / status: ").append(nullToDash(flower.getStatus() == null ? null : flower.getStatus().name()))
                    .append(" / address: ").append(nullToDash(flower.getAddress()))
                    .append(" / bloom: ").append(nullToDash(flower.getBloomStart()))
                    .append(" ~ ").append(nullToDash(flower.getBloomEnd()))
                    .append("\n");
        }
        return context.toString();
    }

    private String formatCommunityToolResult(ToolResult result) {
        StringBuilder context = new StringBuilder("Community posts:\n");
        if (result.getData() == null || !(result.getData().get("items") instanceof List<?> items) || items.isEmpty()) {
            context.append("- No matching community posts.\n");
            return context.toString();
        }
        for (Object item : items) {
            if (item instanceof Map<?, ?> post) {
                context.append("- [")
                        .append(valueOrDash(post.get("nickname")))
                        .append("] ")
                        .append(valueOrDash(post.get("content")))
                        .append(" (likes: ").append(valueOrZero(post.get("likes"))).append(")\n");
            }
        }
        return context.toString();
    }

    private Object valueOrDash(Object value) {
        return value == null ? "-" : value;
    }

    private Object valueOrZero(Object value) {
        return value == null ? 0 : value;
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
                "지도", "위치", "추천", "알려줘", "보여줘", "어디", "근처", "주변", "길", "안내",
                "볼래요", "볼래", "보기", "보고싶", "보고 싶",
                "에서", "으로", "로", "을", "를", "은", "는", "이", "가",
                "커뮤니티", "게시글", "후기", "글", "작성", "써줘", "초안",
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
        return keyword == null || keyword.isBlank() ? "all" : keyword;
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

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
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
            context.append("Agent run route: ").append(trace.getRoute()).append("\n");
            context.append("Steps:\n");
            for (AgentStepTrace step : trace.getSteps()) {
                context.append("- ")
                        .append(step.getAgent())
                        .append(".")
                        .append(step.getTool())
                        .append(": ")
                        .append(step.getMessage())
                        .append("\n");
            }
            context.append("\nTool results:\n");
            for (ToolResult result : toolResults) {
                context.append("- ")
                        .append(result.getTool())
                        .append(" / ")
                        .append(result.getStatus())
                        .append(": ")
                        .append(result.getSummary())
                        .append("\n");
                if (result.getData() != null && result.getData().get("context") != null) {
                    context.append(result.getData().get("context")).append("\n");
                } else if (result.getData() != null) {
                    context.append(result.getData()).append("\n");
                }
            }
            if (!actions.isEmpty()) {
                context.append("\n테스트용 내부 액션:\n");
                for (ChatAction action : actions) {
                    context.append("- ")
                            .append(koreanActionLabel(action))
                            .append("\n");
                }
            }
            return context.toString();
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
                case "PREPARE_DRAFT" -> "커뮤니티 초안 준비";
                default -> type + " / " + target;
            };
            return params.isEmpty() ? base : base + " " + params;
        }

        private String targetLabel(String target) {
            return switch (target) {
                case "MAP" -> "지도";
                case "COMMUNITY" -> "커뮤니티";
                case "WALK" -> "산책";
                case "FLOWER_BOOK", "FLOWER" -> "꽃 도감";
                case "SAVED" -> "저장됨";
                case "QUEST" -> "퀘스트";
                case "SHOP" -> "상점";
                default -> target.isBlank() ? "대상" : target;
            };
        }
    }

    private record ChatTurn(String role, String content) {
    }
}
