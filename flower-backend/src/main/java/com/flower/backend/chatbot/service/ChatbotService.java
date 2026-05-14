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
                plan.source() + " selected the required search tools and internal client follow-ups."));

        ChatActionValidator.ValidationResult validationResult =
                chatActionValidator.validateAndComplete(plan.actions(), intents, keyword);
        actions.addAll(validationResult.actions());
        for (ChatAction action : actions) {
            steps.add(stepTrace(step++, agentFor(action), toolFor(action), "READY",
                    "Planned client-side follow-up from " + plan.source() + "."));
        }

        boolean flowerBookInfoRequested = intents.contains(RouteIntent.FLOWER)
                && !flowerBookRequested
                && wantsFlowerBookInfo(message);
        if (flowerBookInfoRequested) {
            boolean allowCandidateExpansion = wantsUnknownFlowerIdentification(message)
                    && !wantsMap(message)
                    && !wantsFlowerSpotOrEvent(message);
            if (wantsFlowerGrowTips(message)) {
                ToolResult growTipsResult = flowerToolService.lookupFlowerGrowTipsSourceResult(
                        keyword,
                        allowCandidateExpansion
                );
                toolResults.add(growTipsResult);
                steps.add(stepTrace(step++, "FlowerAgent", "lookupFlowerGrowTipsSource", "SUCCESS",
                        growTipsResult.getSummary()));
            } else {
                ToolResult descriptionResult = flowerToolService.lookupFlowerDescriptionSourceResult(
                        keyword,
                        allowCandidateExpansion
                );
                toolResults.add(descriptionResult);
                steps.add(stepTrace(step++, "FlowerAgent", "lookupFlowerDescriptionSource", "SUCCESS",
                        descriptionResult.getSummary()));
            }
        } else if (intents.contains(RouteIntent.FLOWER) && !flowerBookRequested) {
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

        AgentRunTrace trace = AgentRunTrace.builder()
                .mode("SPRING_AI_ROUTER_PLANNED_LIGHTWEIGHT_AGENTIC_RAG")
                .route(route)
                .specialist("RouterAgent")
                .steps(steps)
                .build();
        return new AgentExecution(actions, toolResults, trace);
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
                    {"type":"NAVIGATE","target":"MAP|COMMUNITY|COMMUNITY_COMPOSE|WALK|FLOWER_BOOK|SAVED|QUEST|SHOP","params":{}},
                    {"type":"MAP_SET_SEARCH_QUERY","target":"MAP","params":{"query":"flower name only"}},
                    {"type":"NAVIGATE","target":"COMMUNITY_COMPOSE","params":{}}
                  ]
                }
                Rules:
                - For greetings, thanks, small talk, capability questions, and general conversation, return intents ["GENERAL"], searchKeyword "", and actions [].
                - If there is no appropriate app-control action to run, return actions [].
                - If the user asks for flower facts, descriptions, features, care, or grow tips, use intent FLOWER, no navigation action, and searchKeyword should be the flower name.
                - If the user describes a flower but says they do not know its name, use intent FLOWER, no navigation action, and keep the descriptive phrase as searchKeyword.
                - If the user asks about a flower festival, event, spot, place, nearby area, recommendation, or map, do not treat it as flower-book identification.
                - Do not create any NAVIGATE action unless the user explicitly asks to open, show, move to, or view a screen.
                - Do not create NAVIGATE MAP unless the user explicitly asks for a map, location, nearby places, route, directions, or path.
                - If the user only asks to open or view the map, use NAVIGATE MAP only and searchKeyword must be "".
                - Use MAP_SET_SEARCH_QUERY only when the user names a flower or flower place to find on the map.
                - If the user wants to write or create a community post, use NAVIGATE COMMUNITY_COMPOSE only.
                - Do not generate community post content or drafts.
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
        return new AgentPlan(List.of(RouteIntent.GENERAL), "", List.of(), "FallbackPlanner");
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
                The user's language has higher priority than the language of tool names, field names, database values, or internal context labels.
                If the user writes Korean, all final prose, explanations, caveats, and source mentions must be written naturally in Korean.
                Do not copy English tool labels such as "description", "growTips", "source", "Tool results", or "lookup returned" into the final answer.
                Use only the provided tool results as factual ground truth.
                Do not invent exact bloom dates, locations, post content, purchases, or completed writes.
                Flower description and grow-tip answers must use flower_book tool results and include the source when available.
                If the flower query was expanded from a vague description, explain that the answer is based on likely candidates, not a confirmed identification.
                Flower tools provide flower spot data and flower book navigation only; map actions are handled by map tools.
                If a write-like task opens the community composer, clearly say the post editor is being opened without generated content or an automatic save.
                Internal client follow-ups may be shown as Korean read-only test information. Never tell the user that a shortcut button or navigation button was prepared.
                """ + "\n" + actionInstruction;
    }

    private String fallbackReply(String message, String localContext, ChatAction action) {
        StringBuilder reply = new StringBuilder();
        reply.append("OpenAI API 키가 설정되지 않아 로컬 도구 결과를 사용했습니다.\n\n");
        reply.append(localContext);
        if (action != null && "NAVIGATE".equals(action.getType()) && "COMMUNITY_COMPOSE".equals(action.getTarget())) {
            reply.append("\n\n게시글 작성 화면을 열도록 준비했습니다. 글 내용은 생성하거나 저장하지 않았습니다.");
        } else if (message != null && !message.isBlank()) {
            reply.append("\n\n자연스러운 Spring AI 답변을 사용하려면 OPENAI_API_KEY를 설정해야 합니다.");
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
                "\uC815\uBCF4", "\uC124\uBA85", "\uD2B9\uC9D5", "\uD0A4\uC6B0\uAE30",
                "\uD0A4\uC6B0\uB294", "\uC7AC\uBC30", "\uAD00\uB9AC", "\uBB3C\uC8FC\uAE30",
                "\uD587\uBE5B", "\uD1A0\uC591", "\uD301", "\uBC29\uBC95",
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
                context.append("\n테스트용 내부 액션:\n");
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
                case "searchCommunityPosts" -> "커뮤니티 글 조회";
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
                case "QUEST" -> "퀘스트";
                case "SHOP" -> "상점";
                default -> target.isBlank() ? "대상" : target;
            };
        }
    }

    private record ChatTurn(String role, String content) {
    }
}
