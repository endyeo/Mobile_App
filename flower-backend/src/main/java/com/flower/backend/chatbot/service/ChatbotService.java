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
import com.flower.backend.chatbot.tool.FestivalAgent.FestivalToolService;
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
import jakarta.annotation.PostConstruct;
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
    private final FestivalToolService festivalToolService;
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
            FestivalToolService festivalToolService,
            ObjectProvider<ChatClient.Builder> chatClientBuilderProvider,
            @Value("${chatbot.openai.api-key:}") String openAiApiKey
    ) {
        this.flowerToolService = flowerToolService;
        this.communityTools = communityTools;
        this.festivalToolService = festivalToolService;
        ChatClient.Builder chatClientBuilder = chatClientBuilderProvider.getIfAvailable();
        this.chatClient = chatClientBuilder == null ? null : chatClientBuilder.build();
        this.openAiApiKey = openAiApiKey == null ? "" : openAiApiKey.trim();
    }

    @PostConstruct
    void logPlannerConfiguration() {
        log.info("챗봇 AIPlanner 설정: openAiApiKeyConfigured={}, openAiApiKeyLength={}, chatClientConfigured={}",
                !openAiApiKey.isBlank(),
                openAiApiKey.length(),
                chatClient != null);
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
        String reply = callSpringAi(
                message,
                sessionId,
                localContext,
                primaryAction,
                execution.toolResults(),
                execution.answerDomain(),
                execution.answerTask());

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
        String reply = callSpringAi(
                message,
                sessionId,
                localContext,
                primaryAction,
                execution.toolResults(),
                execution.answerDomain(),
                execution.answerTask());

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
        String informationTask = normalizeInformationTask(plan.informationTask(), message, intents, plan.actions());
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
                plan.source() + "가 " + displayPlannerChoice(plan) + " 실행 계획을 선택했습니다."));
        sendStreamEvent(streamSender, "CONTEXT_PLANNED", Map.of(
                "route", route,
                "message", "요청 맥락과 필요한 작업 계획을 확인했습니다."
        ));

        if (isUnsupportedPlan(plan, informationTask, intents, message)) {
            ToolResult unsupportedResult = unsupportedToolResult(message, plan);
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
            AnswerStyle answerStyle = resolveAnswerStyle(plan, informationTask, toolResults, List.of());
            return new AgentExecution(List.of(), toolResults, trace, answerStyle.domain(), answerStyle.task());
        }

        sendStatus(streamSender, "PLAN", "필요한 앱 도구를 고르고 있어요.");
        ChatActionValidator.ValidationResult validationResult =
                chatActionValidator.validateAndComplete(plan.actions(), intents, keyword);
        if (!isInformationTask(informationTask) || wantsExplicitAppNavigation(message)) {
            actions.addAll(validationResult.actions());
        }

        boolean flowerIntent = hasFlowerIntent(intents);
        ToolResult festivalResult = executeFestivalTask(plan, keyword, location, streamSender);
        if (festivalResult != null) {
            toolResults.add(festivalResult);
            sendStreamEvent(streamSender, "TOOL_RESULT", Map.of("toolResult", festivalResult));
            steps.add(stepTrace(step++, "FestivalAgent", festivalResult.getTool(), "SUCCESS",
                    festivalResult.getSummary()));
        }

        ToolResult informationResult = executeInformationTask(informationTask, keyword, message, plan, location, streamSender);
        if (informationResult != null) {
            toolResults.add(informationResult);
            sendStreamEvent(streamSender, "TOOL_RESULT", Map.of("toolResult", informationResult));
            steps.add(stepTrace(step++, "FlowerAgent", informationResult.getTool(), "SUCCESS",
                    informationResult.getSummary()));
            addRepresentativeMapAction(actions, informationResult, plan);
        } else if (flowerIntent && !flowerBookRequested) {
            sendStatus(streamSender, "SEARCH", "꽃 정보를 검색하고 있어요.");
            flowerResults = searchFlowers(keyword);
            ToolResult flowerResult = flowerToolResult(keyword, flowerResults);
            toolResults.add(flowerResult);
            sendStreamEvent(streamSender, "TOOL_RESULT", Map.of("toolResult", flowerResult));
            steps.add(stepTrace(step++, "FlowerAgent", "searchFlowerSpots", "SUCCESS",
                    "승인된 꽃 명소 후보 " + flowerResults.size() + "개를 확인했습니다."));
            addRepresentativeMapAction(actions, flowerResult, plan);
        }

        ToolResult communityResult = executeCommunityTask(plan, intents, actions, keyword, streamSender);
        if (communityResult != null) {
            toolResults.add(communityResult);
            sendStreamEvent(streamSender, "TOOL_RESULT", Map.of("toolResult", communityResult));
            steps.add(stepTrace(step++, "RouterAgent", communityResult.getTool(), "SUCCESS",
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

        AnswerStyle answerStyle = resolveAnswerStyle(plan, informationTask, toolResults, actions);
        AgentRunTrace trace = AgentRunTrace.builder()
                .mode("SPRING_AI_ROUTER_PLANNED_LIGHTWEIGHT_AGENTIC_RAG")
                .route(route)
                .specialist("RouterAgent")
                .steps(steps)
                .build();
        return new AgentExecution(actions, toolResults, trace, answerStyle.domain(), answerStyle.task());
    }

    private boolean hasFlowerIntent(List<RouteIntent> intents) {
        return intents.contains(RouteIntent.FLOWER) || intents.contains(RouteIntent.FLOWER_GROW);
    }

    private ToolResult executeInformationTask(
            String informationTask,
            String keyword,
            String message,
            AgentPlan plan,
            ChatMessageRequest.LocationContext location,
            StreamSender streamSender
    ) {
        return switch (informationTask) {
            case "basic_info" -> {
                sendStatus(streamSender, "SEARCH", "꽃 기본 정보를 찾고 있어요.");
                yield flowerToolService.getBasicInfoResult(keyword, false);
            }
            case "meaning_bloom" -> {
                sendStatus(streamSender, "SEARCH", "꽃말과 개화 정보를 찾고 있어요.");
                yield flowerToolService.getMeaningAndBloomResult(keyword, false);
            }
            case "grow_guide" -> {
                sendStatus(streamSender, "SEARCH", "꽃 재배 정보를 찾고 있어요.");
                yield flowerToolService.getGrowGuideResult(keyword, false);
            }
            case "monthly_recommendation" -> {
                sendStatus(streamSender, "SEARCH", "이번 시기에 맞는 꽃을 찾고 있어요.");
                yield flowerToolService.recommendByMonthResult(extractRequestedMonth(message));
            }
            case "candidate_inference" -> {
                sendStatus(streamSender, "SEARCH", "설명과 비슷한 꽃 후보를 찾고 있어요.");
                yield flowerToolService.inferCandidatesResult(message);
            }
            case "place_search" -> {
                sendStatus(streamSender, "SEARCH", "등록된 꽃 장소를 찾고 있어요.");
                yield flowerToolService.searchFlowerSpotsResult(keyword, location, plan.nearby());
            }
            default -> null;
        };
    }

    private ToolResult executeFestivalTask(
            AgentPlan plan,
            String keyword,
            ChatMessageRequest.LocationContext location,
            StreamSender streamSender
    ) {
        if (!"festival_info".equals(plan.domain())) {
            return null;
        }
        sendStatus(streamSender, "SEARCH", "꽃 축제 정보를 찾고 있어요.");
        boolean nearby = "recommend_nearby".equals(plan.task()) || "open_festival_map".equals(plan.task());
        return festivalToolService.searchFlowerFestivalsResult(keyword, location, nearby, plan.dateFilter());
    }

    private String normalizeInformationTask(
            String plannerTask,
            String message,
            List<RouteIntent> intents,
            List<ChatAction> actions
    ) {
        String task = plannerTask == null ? "" : plannerTask.trim().toLowerCase(Locale.ROOT);
        if (List.of(
                "basic_info",
                "meaning_bloom",
                "grow_guide",
                "monthly_recommendation",
                "candidate_inference",
                "place_search",
                "app_navigation",
                "unsupported",
                "general"
        ).contains(task)) {
            return task;
        }
        return inferInformationTask(message, intents, actions);
    }

    private String inferInformationTask(String message, List<RouteIntent> intents, List<ChatAction> actions) {
        if (hasUnsupportedIntent(intents) || wantsUnsupportedFeature(message)) {
            return "unsupported";
        }
        if (wantsFlowerBookOpen(message)
                || wantsCommunity(message)
                || wantsWalk(message)
                || wantsSaved(message)) {
            return "app_navigation";
        }
        if (wantsFlowerGrowTips(message) || intents.contains(RouteIntent.FLOWER_GROW)) {
            return "grow_guide";
        }
        if (wantsUnknownFlowerIdentification(message)) {
            return "candidate_inference";
        }
        if (wantsMeaningOrBloom(message)) {
            return "meaning_bloom";
        }
        if (wantsFlowerSpotOrEvent(message)) {
            return "place_search";
        }
        if (wantsSeasonalRecommendation(message)) {
            return "monthly_recommendation";
        }
        if (wantsFlowerDescriptionInfo(message) || hasFlowerIntent(intents)) {
            return "basic_info";
        }
        if (actionsContainTarget(actions, "MAP")) {
            return "app_navigation";
        }
        return "general";
    }

    private boolean isInformationTask(String informationTask) {
        return List.of(
                "basic_info",
                "meaning_bloom",
                "grow_guide",
                "monthly_recommendation",
                "candidate_inference"
        ).contains(informationTask);
    }

    private boolean wantsExplicitAppNavigation(String message) {
        return wantsMap(message)
                || wantsFlowerBookOpen(message)
                || wantsCommunity(message)
                || wantsWalk(message)
                || wantsSaved(message);
    }

    private boolean hasUnsupportedIntent(List<RouteIntent> intents) {
        return intents.contains(RouteIntent.QUEST) || intents.contains(RouteIntent.SHOP);
    }

    private boolean isUnsupportedPlan(
            AgentPlan plan,
            String informationTask,
            List<RouteIntent> intents,
            String message
    ) {
        return "unsupported".equals(plan.domain())
                || "unsupported".equals(informationTask)
                || hasUnsupportedIntent(intents)
                || wantsUnsupportedFeature(message);
    }

    private ToolResult executeCommunityTask(
            AgentPlan plan,
            List<RouteIntent> intents,
            List<ChatAction> actions,
            String keyword,
            StreamSender streamSender
    ) {
        if (actionsContainTarget(actions, "COMMUNITY_COMPOSE")) {
            return null;
        }
        if ("community".equals(plan.domain())) {
            return switch (plan.task()) {
                case "search_posts" -> {
                    sendStatus(streamSender, "SEARCH", "커뮤니티 글을 검색하고 있어요.");
                    yield communityTools.searchPosts(keyword);
                }
                case "latest_posts" -> {
                    sendStatus(streamSender, "SEARCH", "최신 커뮤니티 글을 확인하고 있어요.");
                    yield communityTools.getLatestPosts(keyword, plan.dateFilter(), plan.month(), plan.year());
                }
                case "popular_posts" -> {
                    sendStatus(streamSender, "SEARCH", "인기 커뮤니티 글을 확인하고 있어요.");
                    yield communityTools.getPopularPosts(keyword, plan.dateFilter(), plan.month(), plan.year());
                }
                default -> null;
            };
        }
        if (plan.domain().isBlank() && intents.contains(RouteIntent.COMMUNITY)) {
            sendStatus(streamSender, "SEARCH", "커뮤니티 글을 검색하고 있어요.");
            return communityTools.searchPosts(keyword);
        }
        return null;
    }

    private String displayPlannerChoice(AgentPlan plan) {
        if (plan.domain().isBlank() && plan.task().isBlank()) {
            return "기존 route 기반";
        }
        String dateFilter = plan.dateFilter() == null
                || plan.dateFilter().isBlank()
                || "none".equals(plan.dateFilter())
                ? ""
                : ", dateFilter=" + plan.dateFilter();
        String month = plan.month() > 0 ? ", month=" + plan.month() : "";
        String year = plan.year() > 0 ? ", year=" + plan.year() : "";
        String nearby = plan.nearby() ? ", nearby=true" : "";
        String route = plan.routeRequest()
                ? ", routeRequest=true, routeMode=" + normalizeRouteMode(plan.routeMode())
                : "";
        return "domain=" + plan.domain() + ", task=" + plan.task() + dateFilter + month + year + nearby + route;
    }

    private ToolResult unsupportedToolResult(String message, AgentPlan plan) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        String feature = "community_mutation".equals(plan.task())
                ? "커뮤니티 쓰기/수정"
                : "private_or_admin".equals(plan.task())
                ? "개인정보/관리자"
                : containsAny(lower, "shop", "store", "buy", "purchase", "상점", "상품", "아이템", "구매", "사줘")
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

    private void addRepresentativeMapAction(List<ChatAction> actions, ToolResult result, AgentPlan plan) {
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
            String actionType = mapFocusActionType(result, plan);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("flowerId", flowerId);
            String routeMode = normalizeRouteMode(plan.routeMode());
            if ("MAP_START_ROUTE".equals(actionType)) {
                params.put("mode", routeMode);
            }
            ChatAction action = ChatAction.builder()
                    .type(actionType)
                    .target("MAP")
                    .params(params)
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

    private boolean wantsMeaningOrBloom(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return containsAny(lower,
                "meaning", "flower language", "bloom", "blooming", "when",
                "\uAF43\uB9D0", "\uAC1C\uD654", "\uC5B8\uC81C \uD53C", "\uD53C\uB294 \uC2DC\uAE30",
                "\uD53C\uC5B4", "\uBA87 \uC6D4", "\uC2DC\uAE30");
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
                "\uADFC\uCC98", "\uC8FC\uBCC0", "\uC5B4\uB514", "\uAC00\uB294 \uAE38",
                "festival", "event", "place", "spot", "nearby");
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
        return containsAny(lower, "community", "review", "post", "latest", "recent", "popular",
                "커뮤니티", "후기", "게시글", "글", "최신", "최근", "새로 올라온", "인기글", "인기 글");
    }

    private boolean wantsCommunityCompose(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return containsAny(lower, "write", "create post", "compose", "작성", "써줘", "글쓰기",
                "올리고", "올릴래", "올릴게", "글 올릴", "글을 올릴");
    }

    private boolean wantsLatestCommunityPosts(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return containsAny(lower, "latest", "recent", "new post",
                "최신 글", "최신글", "최근 글", "최근글", "최근 커뮤니티", "새 글", "새글",
                "새로 올라온", "오늘 올라온", "요즘 글");
    }

    private boolean wantsPopularCommunityPosts(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return containsAny(lower, "popular", "most liked", "most commented",
                "인기글", "인기 글", "좋아요 많은", "댓글 많은", "반응 좋은", "많이 본");
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
                "상점", "상품", "아이템", "구매", "사줘", "퀘스트", "미션", "인증", "포인트 지급",
                "예매", "예약", "티켓", "결제",
                "좋아요 눌러", "좋아요를 눌러", "댓글 대신", "댓글 달아", "댓글을 달아",
                "게시글 삭제", "글 삭제", "삭제해줘", "수정해줘", "대신 저장", "자동 저장",
                "내용까지 대신 저장");
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
                PlannerDecision decision = parsePlannerDecision(content, "AIPlanner");
                PlannerValidation validation = validatePlannerDecision(decision);
                if (!validation.valid()) {
                    decision = repairPlannerDecision(message, content, validation);
                    validation = validatePlannerDecision(decision);
                }
                if (validation.valid()) {
                    return toAgentPlan(decision);
                }
            } catch (Exception e) {
                log.warn("AI 계획 JSON 생성 실패. 로컬 플래너로 전환합니다: {}", e.getMessage());
            }
        }
        return fallbackAgentPlan(message);
    }

    private String planningSystemPrompt() {
        // 이 프롬프트는 AIPlanner가 사용자 말을 JSON 계획으로만 바꾸게 하는 지시문이다.
        // domain/task는 서버가 허용된 도구와 액션으로 변환하는 실행 계약이다.
        // planner는 도구명과 action명을 직접 고르지 않고 사용자 목적만 구조화한다.
        return """
                You are FLOWER's RouterAI and specialist planner.
                Return only valid JSON. Do not wrap it in markdown.
                Schema:
                {
                  "domain": "flower_info | festival_info | community | map_place | app_navigation | unsupported | general",
                  "task": "basic_info | meaning_bloom | grow_guide | monthly_recommendation | candidate_inference | search_festivals | recommend_nearby | open_festival_map | search_posts | latest_posts | popular_posts | open_community | open_composer | place_search | open_map | open_flower_book | open_walk | open_saved | shop_purchase | quest_verification | community_mutation | private_or_admin | general_chat",
                  "keyword": "optional concrete topic keyword only",
                  "date_filter": "today | this_week | this_month | month | upcoming | none",
                  "month": 0,
                  "year": 0,
                  "nearby": true,
                  "route_request": true,
                  "route_mode": "walk | car | transit | none",
                  "needs_screen": true,
                  "confidence": "high | medium | low",
                  "reason": "short Korean reason"
                }
                Rules:
                - Choose only domain and task. Do not choose tool names or action names.
                - keyword means only a concrete search topic such as a flower name, plant name, place name, or clearly named subject.
                - Request style, sorting words, time expressions, and commands are not keywords. Do not put words such as 최신, 최근, 인기글, 오늘, 이번 주, 3월, 보여줘, 알려줘, 소개해 in keyword.
                - If the user does not name a concrete topic, set keyword to an empty string.
                - Community writing takes priority over community search. If the user says "글 써줘", "글 올릴래", or "작성", choose community/open_composer even if the message contains "후기".
                - Community mutation requests such as like, comment, delete, edit, auto-save, or publish-for-me are unsupported/community_mutation.
                - "커뮤니티 열어줘" is community/open_community and must not search posts.
                - Community latest requests such as "최신 글", "최근 글", "새 글", or "새로 올라온 글" are community/latest_posts and must not use search_posts.
                - Community popular requests such as "인기글", "좋아요 많은 글", "댓글 많은 글", "반응 좋은 글", or "많이 본 글" are community/popular_posts.
                - For community/latest_posts and community/popular_posts, keyword is optional. Use keyword only when the user names a concrete topic like 장미, 수국, 벚꽃, or 라벤더. Whole-feed latest/popular requests and period-only requests must use keyword="".
                - For community latest/popular period filters: "오늘" => today, "이번 주" => this_week, "이번 달" or "이달" => this_month, "3월" => month with month=3. If the user gives no period, use none.
                - If festival, event, or 행사 is the main topic, choose festival_info instead of flower_info.
                - For festival_info, set date_filter from the user's time expression: "이번 주" => this_week, "이번 달" or "이달" => this_month, "오늘" => today, "다가오는" or no explicit period => upcoming.
                - For map_place, set nearby=true only when the user asks "근처", "주변", "near", or "nearby".
                - "꽃 지도 열어줘" is app_navigation/open_map and must not search places.
                - Flower place requests such as 명소, 위치, 지도, 가는 길, 볼 수 있는 곳 are map_place/place_search.
                - A map request with a specific flower/place keyword such as "벚꽃 지도에서 보여줘" or "수국 위치 지도 열어줘" is map_place/place_search, not app_navigation/open_map.
                - Route requests such as "가는 길", "길찾기", "까지 가는 법" are map_place/place_search with route_request=true and needs_screen=true.
                - If the user names a route mode, set route_mode to walk, car, or transit. If no mode is named, set route_mode to none.
                - Do not set route_request=true for "지도에서 보여줘" unless the user asks for route/directions.
                - Festival booking, reservation, ticket purchase, or payment requests are unsupported/private_or_admin.
                - Festival map requests such as "꽃 축제 지도에서 보여줘", "축제 지도에서 보여줘", or "행사 지도 열어줘" are festival_info/open_festival_map.
                - Flower information is more important than screen navigation unless the user explicitly asks for a screen.
                - General greetings and small talk are general/general_chat.
                - Preserve Korean flower/topic keywords such as "수국", "벚꽃", "장미", "라벤더".

                Examples:
                User: 수국 후기 찾아줘
                JSON: {"domain":"community","task":"search_posts","keyword":"수국","needs_screen":false,"confidence":"high","reason":"수국 후기 검색 요청"}
                User: 벚꽃 커뮤니티 글 보여줘
                JSON: {"domain":"community","task":"search_posts","keyword":"벚꽃","needs_screen":true,"confidence":"high","reason":"커뮤니티 글 조회 요청"}
                User: 장미 게시글 검색해줘
                JSON: {"domain":"community","task":"search_posts","keyword":"장미","needs_screen":false,"confidence":"high","reason":"게시글 검색 요청"}
                User: 라벤더 본 사람 후기 있어?
                JSON: {"domain":"community","task":"search_posts","keyword":"라벤더","needs_screen":false,"confidence":"high","reason":"후기 존재 확인"}
                User: 최신 글들은 어떤 걸 소개 해?
                JSON: {"domain":"community","task":"latest_posts","keyword":"","date_filter":"none","needs_screen":false,"confidence":"high","reason":"최신 커뮤니티 글 조회"}
                User: 최근 커뮤니티 글 보여줘
                JSON: {"domain":"community","task":"latest_posts","keyword":"","date_filter":"none","needs_screen":true,"confidence":"high","reason":"최근 커뮤니티 글 화면 조회"}
                User: 오늘 올라온 글 있어?
                JSON: {"domain":"community","task":"latest_posts","keyword":"","date_filter":"today","needs_screen":false,"confidence":"high","reason":"오늘 올라온 커뮤니티 글 조회"}
                User: 수국 최신 후기 보여줘
                JSON: {"domain":"community","task":"latest_posts","keyword":"수국","date_filter":"none","needs_screen":true,"confidence":"high","reason":"수국 최신 후기 조회"}
                User: 인기글 알려줘
                JSON: {"domain":"community","task":"popular_posts","keyword":"","date_filter":"none","needs_screen":false,"confidence":"high","reason":"인기 커뮤니티 글 조회"}
                User: 이번 주 인기글 보여줘
                JSON: {"domain":"community","task":"popular_posts","keyword":"","date_filter":"this_week","needs_screen":true,"confidence":"high","reason":"이번 주 인기글 조회"}
                User: 좋아요 많은 글 뭐 있어?
                JSON: {"domain":"community","task":"popular_posts","keyword":"","date_filter":"none","needs_screen":false,"confidence":"high","reason":"좋아요 기준 인기글 조회"}
                User: 댓글 많은 글 알려줘
                JSON: {"domain":"community","task":"popular_posts","keyword":"","date_filter":"none","needs_screen":false,"confidence":"high","reason":"댓글 기준 인기글 조회"}
                User: 3월 인기글 보여줘
                JSON: {"domain":"community","task":"popular_posts","keyword":"","date_filter":"month","month":3,"needs_screen":true,"confidence":"high","reason":"3월 인기글 조회"}
                User: 커뮤니티 열어줘
                JSON: {"domain":"community","task":"open_community","keyword":"","needs_screen":true,"confidence":"high","reason":"커뮤니티 화면 이동"}
                User: 꽃 후기 보고 싶어
                JSON: {"domain":"community","task":"search_posts","keyword":"꽃","needs_screen":true,"confidence":"high","reason":"꽃 후기 조회"}
                User: 수국 후기 글 써줘
                JSON: {"domain":"community","task":"open_composer","keyword":"수국","needs_screen":true,"confidence":"high","reason":"후기 글 작성 화면 요청"}
                User: 커뮤니티에 글 올릴래
                JSON: {"domain":"community","task":"open_composer","keyword":"","needs_screen":true,"confidence":"high","reason":"커뮤니티 글 작성 화면 요청"}
                User: 글 내용까지 대신 저장해줘
                JSON: {"domain":"unsupported","task":"community_mutation","keyword":"","needs_screen":false,"confidence":"high","reason":"자동 저장은 지원하지 않음"}
                User: 이 게시글 좋아요 눌러줘
                JSON: {"domain":"unsupported","task":"community_mutation","keyword":"","needs_screen":false,"confidence":"high","reason":"좋아요 직접 실행은 지원하지 않음"}
                User: 댓글 대신 달아줘
                JSON: {"domain":"unsupported","task":"community_mutation","keyword":"","needs_screen":false,"confidence":"high","reason":"댓글 직접 작성은 지원하지 않음"}
                User: 게시글 삭제해줘
                JSON: {"domain":"unsupported","task":"community_mutation","keyword":"","needs_screen":false,"confidence":"high","reason":"게시글 삭제는 지원하지 않음"}
                User: 장미가 어떤 꽃이야?
                JSON: {"domain":"flower_info","task":"basic_info","keyword":"장미","needs_screen":false,"confidence":"high","reason":"꽃 기본 정보 질문"}
                User: 수국 꽃말 알려줘
                JSON: {"domain":"flower_info","task":"meaning_bloom","keyword":"수국","needs_screen":false,"confidence":"high","reason":"꽃말 질문"}
                User: 장미 키우는 법 알려줘
                JSON: {"domain":"flower_info","task":"grow_guide","keyword":"장미","needs_screen":false,"confidence":"high","reason":"재배 정보 질문"}
                User: 이번 달에 피는 꽃 추천해줘
                JSON: {"domain":"flower_info","task":"monthly_recommendation","keyword":"","needs_screen":false,"confidence":"high","reason":"월별 꽃 추천"}
                User: 분홍색 꽃인데 이름이 뭘까?
                JSON: {"domain":"flower_info","task":"candidate_inference","keyword":"분홍색 꽃","needs_screen":false,"confidence":"medium","reason":"꽃 이름 후보 추정"}
                User: 벚꽃 지도에서 보여줘
                JSON: {"domain":"map_place","task":"place_search","keyword":"벚꽃","nearby":false,"route_request":false,"route_mode":"none","needs_screen":true,"confidence":"high","reason":"지도에서 장소 확인"}
                User: 수국 명소 어디 있어?
                JSON: {"domain":"map_place","task":"place_search","keyword":"수국","nearby":false,"route_request":false,"route_mode":"none","needs_screen":false,"confidence":"high","reason":"꽃 명소 위치 질문"}
                User: 근처 꽃 명소 추천해줘
                JSON: {"domain":"map_place","task":"place_search","keyword":"꽃","nearby":true,"route_request":false,"route_mode":"none","needs_screen":true,"confidence":"high","reason":"근처 꽃 명소 추천"}
                User: 라벤더 볼 수 있는 곳 알려줘
                JSON: {"domain":"map_place","task":"place_search","keyword":"라벤더","nearby":false,"route_request":false,"route_mode":"none","needs_screen":false,"confidence":"high","reason":"꽃을 볼 수 있는 장소 질문"}
                User: 수국 위치 지도 열어줘
                JSON: {"domain":"map_place","task":"place_search","keyword":"수국","nearby":false,"route_request":false,"route_mode":"none","needs_screen":true,"confidence":"high","reason":"수국 위치를 지도에서 확인"}
                User: 장미 가는 길 알려줘
                JSON: {"domain":"map_place","task":"place_search","keyword":"장미","nearby":false,"route_request":true,"route_mode":"none","needs_screen":true,"confidence":"high","reason":"장미 장소 길찾기 요청"}
                User: 장미까지 도보 길찾기
                JSON: {"domain":"map_place","task":"place_search","keyword":"장미","nearby":false,"route_request":true,"route_mode":"walk","needs_screen":true,"confidence":"high","reason":"도보 길찾기 요청"}
                User: 수국 명소 자동차 길찾기
                JSON: {"domain":"map_place","task":"place_search","keyword":"수국","nearby":false,"route_request":true,"route_mode":"car","needs_screen":true,"confidence":"high","reason":"자동차 길찾기 요청"}
                User: 벚꽃 명소 대중교통으로 가는 길
                JSON: {"domain":"map_place","task":"place_search","keyword":"벚꽃","nearby":false,"route_request":true,"route_mode":"transit","needs_screen":true,"confidence":"high","reason":"대중교통 길찾기 요청"}
                User: 꽃 지도 열어줘
                JSON: {"domain":"app_navigation","task":"open_map","keyword":"","nearby":false,"needs_screen":true,"confidence":"high","reason":"지도 화면 이동"}
                User: 장미 정보 알려줘
                JSON: {"domain":"flower_info","task":"basic_info","keyword":"장미","nearby":false,"needs_screen":false,"confidence":"high","reason":"꽃 정보 질문"}
                User: 장미 도감에서 찾아줘
                JSON: {"domain":"app_navigation","task":"open_flower_book","keyword":"장미","needs_screen":true,"confidence":"high","reason":"도감 화면 이동"}
                User: 안녕
                JSON: {"domain":"general","task":"general_chat","keyword":"","needs_screen":false,"confidence":"high","reason":"인사"}
                User: 이번 주 꽃 축제 알려줘
                JSON: {"domain":"festival_info","task":"search_festivals","keyword":"꽃","date_filter":"this_week","needs_screen":false,"confidence":"high","reason":"이번 주 꽃 축제 정보 조회"}
                User: 서울 근처 꽃 축제 있어?
                JSON: {"domain":"festival_info","task":"recommend_nearby","keyword":"꽃","date_filter":"upcoming","needs_screen":false,"confidence":"high","reason":"근처 꽃 축제 추천"}
                User: 벚꽃 축제 찾아줘
                JSON: {"domain":"festival_info","task":"search_festivals","keyword":"벚꽃","date_filter":"upcoming","needs_screen":false,"confidence":"high","reason":"벚꽃 축제 검색"}
                User: 이번 달 갈 만한 꽃 행사 추천해줘
                JSON: {"domain":"festival_info","task":"search_festivals","keyword":"꽃","date_filter":"this_month","needs_screen":false,"confidence":"high","reason":"이번 달 꽃 행사 추천"}
                User: 다가오는 꽃 축제 알려줘
                JSON: {"domain":"festival_info","task":"search_festivals","keyword":"꽃","date_filter":"upcoming","needs_screen":false,"confidence":"high","reason":"다가오는 꽃 축제 조회"}
                User: 꽃 축제 지도에서 보여줘
                JSON: {"domain":"festival_info","task":"open_festival_map","keyword":"축제","date_filter":"upcoming","needs_screen":true,"confidence":"high","reason":"꽃 축제 지도 확인"}
                User: 축제 지도에서 보여줘
                JSON: {"domain":"festival_info","task":"open_festival_map","keyword":"축제","date_filter":"upcoming","needs_screen":true,"confidence":"high","reason":"축제 지도 확인"}
                User: 행사 지도 열어줘
                JSON: {"domain":"festival_info","task":"open_festival_map","keyword":"행사","date_filter":"upcoming","needs_screen":true,"confidence":"high","reason":"행사 지도 확인"}
                User: 축제 티켓 예매해줘
                JSON: {"domain":"unsupported","task":"private_or_admin","keyword":"","date_filter":"none","needs_screen":false,"confidence":"high","reason":"축제 예매는 지원하지 않음"}
                """;
    }

    private PlannerDecision repairPlannerDecision(String message, String invalidContent, PlannerValidation validation)
            throws Exception {
        String repairUserPrompt = """
                Original user message:
                %s

                Invalid planner JSON:
                %s

                Contract errors:
                %s

                Return corrected JSON only, using the same schema. Do not answer the user.
                """.formatted(
                message == null ? "" : message,
                invalidContent == null ? "" : invalidContent,
                String.join("; ", validation.errors())
        );

        String repaired = chatClient.prompt()
                .system(planningRepairSystemPrompt())
                .user(repairUserPrompt)
                .call()
                .content();
        return parsePlannerDecision(repaired, "AIPlannerRepair");
    }

    private String planningRepairSystemPrompt() {
        return """
                You repair FLOWER planner JSON.
                Return only valid JSON with this schema:
                {"domain":"flower_info|festival_info|community|map_place|app_navigation|unsupported|general","task":"string","keyword":"string","date_filter":"today|this_week|this_month|month|upcoming|none","month":0,"year":0,"nearby":true,"route_request":true,"route_mode":"walk|car|transit|none","needs_screen":true,"confidence":"high|medium|low","reason":"short Korean reason"}
                Do not add actions, tool names, markdown, or prose.
                Domain/task must be a valid pair.
                """;
    }

    private PlannerDecision parsePlannerDecision(String content, String source) throws Exception {
        if (content == null || content.isBlank()) {
            return new PlannerDecision("", "", "", "none", 0, 0, false, false, "none", false, "low", "empty planner response", source);
        }
        JsonNode root = JSON_MAPPER.readTree(content.trim());
        String domain = root.path("domain").asText("").trim().toLowerCase(Locale.ROOT);
        String task = root.path("task").asText("").trim().toLowerCase(Locale.ROOT);
        String keyword = root.path("keyword").asText(root.path("searchKeyword").asText("")).trim();
        String dateFilter = root.path("date_filter").asText(root.path("dateFilter").asText("none")).trim().toLowerCase(Locale.ROOT);
        int month = root.path("month").asInt(0);
        int year = root.path("year").asInt(0);
        boolean nearby = root.path("nearby").asBoolean(false);
        boolean routeRequest = root.path("route_request").asBoolean(root.path("routeRequest").asBoolean(false));
        String routeMode = root.path("route_mode").asText(root.path("routeMode").asText("none")).trim().toLowerCase(Locale.ROOT);
        boolean needsScreen = root.path("needs_screen").asBoolean(root.path("needsScreen").asBoolean(false));
        String confidence = root.path("confidence").asText("medium").trim().toLowerCase(Locale.ROOT);
        String reason = root.path("reason").asText("").trim();
        return new PlannerDecision(
                domain,
                task,
                sanitizePlannerKeyword(keyword),
                normalizePlannerDateFilter(dateFilter),
                sanitizePlannerMonth(month),
                sanitizePlannerYear(year),
                nearby,
                routeRequest,
                normalizeRouteMode(routeMode),
                needsScreen,
                confidence,
                reason,
                source
        );
    }

    private PlannerValidation validatePlannerDecision(PlannerDecision decision) {
        List<String> errors = new ArrayList<>();
        String domain = decision.domain();
        String task = decision.task();
        if (!List.of("flower_info", "festival_info", "community", "map_place", "app_navigation", "unsupported", "general")
                .contains(domain)) {
            errors.add("unknown domain: " + domain);
        }

        Map<String, List<String>> allowedTasks = Map.of(
                "flower_info", List.of("basic_info", "meaning_bloom", "grow_guide", "monthly_recommendation", "candidate_inference"),
                "festival_info", List.of("search_festivals", "recommend_nearby", "open_festival_map"),
                "community", List.of("search_posts", "latest_posts", "popular_posts", "open_community", "open_composer"),
                "map_place", List.of("place_search"),
                "app_navigation", List.of("open_map", "open_flower_book", "open_walk", "open_saved"),
                "unsupported", List.of("shop_purchase", "quest_verification", "community_mutation", "private_or_admin"),
                "general", List.of("general_chat")
        );
        if (!allowedTasks.getOrDefault(domain, List.of()).contains(task)) {
            errors.add("invalid task for domain: " + domain + "/" + task);
        }
        if ("unsupported".equals(domain) && decision.needsScreen()) {
            errors.add("unsupported requests must not need a screen");
        }
        if ("general".equals(domain) && decision.needsScreen()) {
            errors.add("general chat must not need a screen");
        }
        if (!List.of("today", "this_week", "this_month", "month", "upcoming", "none").contains(decision.dateFilter())) {
            errors.add("unknown date_filter: " + decision.dateFilter());
        }
        if ("month".equals(decision.dateFilter()) && (decision.month() < 1 || decision.month() > 12)) {
            errors.add("month date_filter requires month 1-12");
        }
        if (!List.of("walk", "car", "transit", "none").contains(decision.routeMode())) {
            errors.add("unknown route_mode: " + decision.routeMode());
        }
        if (decision.routeRequest() && (!"map_place".equals(domain) || !"place_search".equals(task))) {
            errors.add("route_request is only allowed for map_place/place_search");
        }
        if (decision.routeRequest() && !decision.needsScreen()) {
            errors.add("route requests must need a screen");
        }
        if ("app_navigation".equals(domain) && "open_map".equals(task) && !decision.keyword().isBlank()) {
            errors.add("open_map must not include a place keyword; use map_place/place_search");
        }
        return new PlannerValidation(errors.isEmpty(), errors);
    }

    private AgentPlan toAgentPlan(PlannerDecision decision) {
        String keyword = sanitizePlannerKeyword(decision.keyword());
        if ("community".equals(decision.domain())
                && List.of("latest_posts", "popular_posts").contains(decision.task())) {
            keyword = sanitizeCommunityReadKeyword(
                    keyword,
                    new CommunityPeriod(decision.dateFilter(), decision.month(), decision.year()));
        }
        List<RouteIntent> intents = new ArrayList<>();
        List<ChatAction> actions = new ArrayList<>();
        String informationTask = "general";

        switch (decision.domain()) {
            case "flower_info" -> {
                if ("grow_guide".equals(decision.task())) {
                    intents.add(RouteIntent.FLOWER_GROW);
                } else {
                    intents.add(RouteIntent.FLOWER);
                }
                informationTask = decision.task();
            }
            case "community" -> {
                intents.add(RouteIntent.COMMUNITY);
                informationTask = "app_navigation";
                if ("open_community".equals(decision.task())
                        || (List.of("search_posts", "latest_posts", "popular_posts").contains(decision.task())
                                && decision.needsScreen())) {
                    actions.add(navigateAction("COMMUNITY", keyword.isBlank() ? Map.of() : Map.of("query", keyword)));
                } else if ("open_composer".equals(decision.task())) {
                    actions.add(navigateAction("COMMUNITY_COMPOSE", Map.of()));
                }
            }
            case "festival_info" -> {
                intents.add(RouteIntent.FESTIVAL);
                informationTask = "app_navigation";
                if ("open_festival_map".equals(decision.task())) {
                    intents.add(RouteIntent.MAP);
                    actions.add(navigateAction("MAP", Map.of()));
                    actions.add(ChatAction.builder()
                            .type("MAP_SET_SEARCH_QUERY")
                            .target("MAP")
                            .params(Map.of("query", keyword.isBlank() ? "축제" : keyword))
                            .build());
                }
            }
            case "map_place" -> {
                intents.add(RouteIntent.MAP);
                intents.add(RouteIntent.FLOWER);
                informationTask = "place_search";
                if (decision.needsScreen() || decision.routeRequest()) {
                    actions.add(navigateAction("MAP", Map.of()));
                }
                if ((decision.needsScreen() || decision.routeRequest()) && !keyword.isBlank()) {
                    actions.add(ChatAction.builder()
                            .type("MAP_SET_SEARCH_QUERY")
                            .target("MAP")
                            .params(Map.of("query", keyword))
                            .build());
                }
            }
            case "app_navigation" -> {
                informationTask = "app_navigation";
                switch (decision.task()) {
                    case "open_map" -> {
                        intents.add(RouteIntent.MAP);
                        actions.add(navigateAction("MAP", Map.of()));
                    }
                    case "open_flower_book" -> {
                        intents.add(RouteIntent.FLOWER);
                        actions.add(navigateAction("FLOWER_BOOK", keyword.isBlank() ? Map.of() : Map.of("query", keyword)));
                    }
                    case "open_walk" -> {
                        intents.add(RouteIntent.WALK);
                        actions.add(navigateAction("WALK", Map.of()));
                    }
                    case "open_saved" -> {
                        intents.add(RouteIntent.SAVED);
                        actions.add(navigateAction("SAVED", Map.of()));
                    }
                    default -> intents.add(RouteIntent.GENERAL);
                }
            }
            case "unsupported" -> {
                informationTask = "unsupported";
                if ("shop_purchase".equals(decision.task())) {
                    intents.add(RouteIntent.SHOP);
                } else if ("quest_verification".equals(decision.task())) {
                    intents.add(RouteIntent.QUEST);
                } else if ("community_mutation".equals(decision.task())) {
                    intents.add(RouteIntent.COMMUNITY);
                } else {
                    intents.add(RouteIntent.GENERAL);
                }
            }
            default -> {
                intents.add(RouteIntent.GENERAL);
                informationTask = "general";
            }
        }

        return new AgentPlan(
                intents.stream().distinct().toList(),
                informationTask,
                keyword,
                actions,
                decision.source(),
                decision.domain(),
                decision.task(),
                decision.dateFilter(),
                decision.month(),
                decision.year(),
                decision.nearby(),
                decision.routeRequest(),
                decision.routeMode(),
                decision.needsScreen(),
                decision.confidence(),
                decision.reason()
        );
    }

    private String normalizePlannerDateFilter(String dateFilter) {
        String normalized = dateFilter == null ? "" : dateFilter.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "today", "this_week", "this_month", "month", "upcoming" -> normalized;
            default -> "none";
        };
    }

    private int sanitizePlannerMonth(int month) {
        return month >= 1 && month <= 12 ? month : 0;
    }

    private int sanitizePlannerYear(int year) {
        return year >= 2000 && year <= 2100 ? year : 0;
    }

    private String normalizeRouteMode(String routeMode) {
        String normalized = routeMode == null ? "" : routeMode.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "walk", "car", "transit" -> normalized;
            default -> "none";
        };
    }

    private AgentPlan parseAgentPlan(String content) throws Exception {
        if (content == null || content.isBlank()) {
            return new AgentPlan(List.of(), "", "", List.of(), "AIPlanner");
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
        String informationTask = root.path("information_task").asText(root.path("informationTask").asText(""));
        List<ChatAction> actions = new ArrayList<>();
        for (JsonNode node : root.path("actions")) {
            actions.add(ChatAction.builder()
                    .type(node.path("type").asText(""))
                    .target(node.path("target").asText(""))
                    .params(readParams(node.path("params")))
                    .build());
        }
        return new AgentPlan(intents.stream().distinct().toList(), informationTask, searchKeyword, actions, "AIPlanner");
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
            return new AgentPlan(intents, "unsupported", keyword, actions, "FallbackPlanner");
        }
        boolean communityIntent = wantsCommunity(message);
        boolean communityCompose = communityIntent && wantsCommunityCompose(message);
        boolean latestCommunity = communityIntent && wantsLatestCommunityPosts(message);
        boolean popularCommunity = communityIntent && wantsPopularCommunityPosts(message);
        if (latestCommunity || popularCommunity) {
            return fallbackCommunityReadPlan(message, keyword, latestCommunity ? "latest_posts" : "popular_posts");
        }
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
        String informationTask = inferInformationTask(message, intents, actions);
        return new AgentPlan(intents.stream().distinct().toList(), informationTask, keyword, actions, "FallbackPlanner");
    }

    private AgentPlan fallbackCommunityReadPlan(String message, String keyword, String task) {
        CommunityPeriod period = resolveCommunityPeriod(message);
        String effectiveKeyword = sanitizeCommunityReadKeyword(keyword, period);
        boolean needsScreen = containsAny(message == null ? "" : message.toLowerCase(Locale.ROOT),
                "보여줘", "보고 싶", "볼래", "열어", "show", "open");
        List<ChatAction> actions = new ArrayList<>();
        if (needsScreen) {
            actions.add(navigateAction("COMMUNITY", effectiveKeyword.isBlank() ? Map.of() : Map.of("query", effectiveKeyword)));
        }
        return new AgentPlan(
                List.of(RouteIntent.COMMUNITY),
                "app_navigation",
                effectiveKeyword,
                actions,
                "FallbackPlanner",
                "community",
                task,
                period.dateFilter(),
                period.month(),
                period.year(),
                false,
                false,
                "none",
                needsScreen,
                "medium",
                "커뮤니티 최신/인기 글 fallback 분류"
        );
    }

    private String sanitizeCommunityReadKeyword(String keyword, CommunityPeriod period) {
        String cleaned = sanitizePlannerKeyword(keyword).trim();
        if (cleaned.isBlank() || cleaned.length() > 20 || cleaned.matches("\\d+")) {
            return "";
        }
        if (cleaned.matches(".*\\s+.*")) {
            return "";
        }
        if (looksLikeCommunityReadControlKeyword(cleaned, period)) {
            return "";
        }
        return cleaned;
    }

    private boolean looksLikeCommunityReadControlKeyword(String keyword, CommunityPeriod period) {
        String compact = keyword.replaceAll("\\s+", "");
        if (compact.isBlank()) {
            return true;
        }
        if (compact.matches("\\d+월.*") || compact.matches("\\d+주.*")) {
            return true;
        }
        String[] controlWords = {
                "최신", "최신글", "최근", "최근글", "새글", "인기", "인기글",
                "오늘", "이번주", "이번달", "이달", "조회", "검색", "보기", "보여줘", "알려줘", "소개"
        };
        for (String word : controlWords) {
            if (compact.equals(word)) {
                return true;
            }
        }
        if (!"none".equals(period.dateFilter())
                && containsAny(compact, "최신", "최근", "인기", "오늘", "이번", "월", "주")) {
            return true;
        }
        return false;
    }

    private CommunityPeriod resolveCommunityPeriod(String message) {
        String value = message == null ? "" : message;
        String lower = value.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "today", "오늘")) {
            return new CommunityPeriod("today", 0, 0);
        }
        if (containsAny(lower, "this week", "이번 주", "이번주")) {
            return new CommunityPeriod("this_week", 0, 0);
        }
        if (containsAny(lower, "this month", "이번 달", "이번달", "이달")) {
            return new CommunityPeriod("this_month", 0, 0);
        }
        for (int month = 1; month <= 12; month++) {
            if (value.contains(month + "월")) {
                return new CommunityPeriod("month", month, LocalDate.now().getYear());
            }
        }
        return new CommunityPeriod("none", 0, 0);
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

    private String callSpringAi(
            String message,
            String sessionId,
            String localContext,
            ChatAction action,
            List<ToolResult> toolResults,
            String answerDomain,
            String answerTask
    ) {
        String guardedAnswer = buildGuardedAnswer(message, toolResults, answerDomain, answerTask);
        if (guardedAnswer != null) {
            return guardedAnswer;
        }
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
                    .system(buildAnswerSystemPrompt(answerDomain, answerTask, action))
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

    String buildGuardedAnswer(
            String message,
            List<ToolResult> toolResults,
            String answerDomain,
            String answerTask
    ) {
        if (toolResults == null || toolResults.isEmpty()) {
            return null;
        }
        if ("flower_info".equals(answerDomain)) {
            return buildFlowerGuardedAnswer(message, toolResults, answerTask);
        }
        if ("festival_info".equals(answerDomain)) {
            return buildFestivalGuardedAnswer(toolResults);
        }
        return null;
    }

    private String buildFlowerGuardedAnswer(String message, List<ToolResult> toolResults, String answerTask) {
        ToolResult flowerResult = findFirstToolResult(toolResults,
                "flower.getBasicInfo",
                "flower.lookupDescriptionSource",
                "flower.getMeaningAndBloom",
                "flower.getGrowGuide",
                "flower.lookupGrowTipsSource",
                "flower.recommendByMonth",
                "flower.recommendSeasonalFlowers",
                "flower.inferCandidates");
        if (flowerResult == null) {
            return null;
        }
        if (isErrorResult(flowerResult)) {
            return buildFlowerErrorReply(message, answerTask, flowerResult);
        }
        if ("candidate_inference".equals(answerTask) && hasEmptyCandidates(flowerResult)) {
            return """
                    설명만으로는 지금 꽃 후보를 자연스럽게 좁혀 드리기 어려워요.
                    확인 가능한 도감 후보를 찾지 못했습니다. 사진이나 모양, 크기, 핀 시기 같은 특징을 조금 더 알려주시면 다시 도와드릴게요.
                    """.trim();
        }
        if (hasEmptyItems(flowerResult)) {
            return buildFlowerNoDataReply(message, answerTask, flowerResult);
        }
        return null;
    }

    private String buildFestivalGuardedAnswer(List<ToolResult> toolResults) {
        ToolResult festivalResult = findFirstToolResult(toolResults, "festival.searchFlowerFestivals");
        if (festivalResult == null) {
            return null;
        }
        if (isErrorResult(festivalResult)) {
            return buildFestivalErrorReply(festivalResult);
        }
        if (hasEmptyItems(festivalResult)) {
            return buildFestivalNoDataReply(festivalResult);
        }
        return null;
    }

    private ToolResult findFirstToolResult(List<ToolResult> toolResults, String... toolNames) {
        List<String> candidates = List.of(toolNames);
        return toolResults.stream()
                .filter(result -> result != null && candidates.contains(result.getTool()))
                .findFirst()
                .orElse(null);
    }

    private boolean isErrorResult(ToolResult result) {
        return result != null && "ERROR".equalsIgnoreCase(result.getStatus());
    }

    private boolean hasEmptyItems(ToolResult result) {
        Map<String, Object> data = result == null ? null : result.getData();
        return data != null && data.get("items") instanceof List<?> items && items.isEmpty();
    }

    private boolean hasEmptyCandidates(ToolResult result) {
        Map<String, Object> data = result == null ? null : result.getData();
        return data != null && data.get("candidates") instanceof List<?> candidates && candidates.isEmpty();
    }

    private String buildFlowerErrorReply(String message, String answerTask, ToolResult result) {
        String subject = extractAnswerSubject(message);
        String reason = humanizeFlowerFailureReason(result);
        return switch (answerTask) {
            case "grow_guide" -> (subject + " 재배 정보를 지금은 가져올 수 없어요. " + reason).trim();
            case "meaning_bloom" -> (subject + " 꽃말이나 개화 정보를 지금은 가져올 수 없어요. " + reason).trim();
            case "monthly_recommendation" -> {
                String monthLabel = extractFlowerMonthLabel(result);
                yield (monthLabel + " 기준으로 추천할 꽃 정보를 지금은 가져올 수 없어요. " + reason).trim();
            }
            case "candidate_inference" -> ("설명만으로 후보를 확인하는 중 문제가 있어 지금은 꽃 후보를 안내드리기 어려워요. " + reason).trim();
            default -> (subject + " 꽃 정보를 지금은 가져올 수 없어요. " + reason).trim();
        };
    }

    private String buildFlowerNoDataReply(String message, String answerTask, ToolResult result) {
        String subject = extractAnswerSubject(message);
        return switch (answerTask) {
            case "grow_guide" -> (subject + " 재배 정보를 바로 안내드리기 어려워요. "
                    + "등록된 꽃 도감 데이터에서 키우는 법 항목을 찾지 못했습니다.").trim();
            case "meaning_bloom" -> (subject + " 꽃말이나 개화 정보를 확인하지 못했어요. "
                    + "등록된 꽃 도감 데이터에서 관련 항목을 찾지 못했습니다.").trim();
            case "monthly_recommendation" -> {
                String monthLabel = extractFlowerMonthLabel(result);
                yield (monthLabel + " 기준으로 바로 추천드릴 꽃 정보를 확인하지 못했어요. "
                        + "등록된 개화 데이터에서 추천 후보를 찾지 못했습니다.").trim();
            }
            default -> (subject + "에 대해 바로 안내드릴 수 있는 꽃 정보를 확인하지 못했어요. "
                    + "등록된 꽃 도감 데이터에서 일치하는 정보를 찾지 못했습니다.").trim();
        };
    }

    private String buildFestivalErrorReply(ToolResult result) {
        String periodPrefix = festivalPeriodPrefix(result);
        return (periodPrefix + "꽃 축제 정보를 지금은 가져올 수 없어요. "
                + humanizeFestivalFailureReason(result)).trim();
    }

    private String buildFestivalNoDataReply(ToolResult result) {
        String periodPrefix = festivalPeriodPrefix(result);
        return (periodPrefix + "현재 확인된 꽃 축제 정보는 없어요. "
                + "조회된 축제 데이터에서 안내드릴 일정을 찾지 못했습니다.").trim();
    }

    private String extractAnswerSubject(String message) {
        String keyword = sanitizePlannerKeyword(extractKeyword(message));
        return keyword == null || keyword.isBlank() ? "요청하신 꽃" : "'" + keyword + "'";
    }

    private String extractFlowerMonthLabel(ToolResult result) {
        Map<String, Object> data = result == null ? null : result.getData();
        if (data != null && data.get("month") instanceof Number month) {
            return month.intValue() + "월";
        }
        return "현재 시기";
    }

    private String festivalPeriodPrefix(ToolResult result) {
        Map<String, Object> data = result == null ? null : result.getData();
        String dateFilter = data == null || data.get("dateFilter") == null
                ? ""
                : data.get("dateFilter").toString();
        return switch (dateFilter) {
            case "today" -> "오늘 기준으로 ";
            case "this_week" -> "이번 주 기준으로 ";
            case "this_month" -> "이번 달 기준으로 ";
            case "upcoming" -> "다가오는 일정 기준으로 ";
            default -> "";
        };
    }

    private String humanizeFlowerFailureReason(ToolResult result) {
        String error = result == null || result.getError() == null ? "" : result.getError();
        if (!error.isBlank()) {
            return "정보를 확인하는 중 문제가 있어 잠시 후 다시 시도해 주세요.";
        }
        return "지금은 정보를 확인하지 못해 잠시 후 다시 시도해 주세요.";
    }

    private String humanizeFestivalFailureReason(ToolResult result) {
        String error = result == null || result.getError() == null ? "" : result.getError();
        if (error.contains("TOUR_API_KEY is not configured")) {
            return "외부 연동 설정이 없어 축제 데이터를 확인하지 못했습니다.";
        }
        return "축제 정보를 확인하는 중 문제가 생겨 잠시 후 다시 시도해 주세요.";
    }

    String buildAnswerSystemPrompt(String answerDomain, String answerTask, ChatAction action) {
        String basePrompt = buildBaseAnswerPrompt();
        String formatPrompt = buildAnswerFormatPrompt(answerDomain, answerTask);
        String domainStylePrompt = buildDomainStylePrompt(answerDomain, answerTask);
        String actionInstruction = action == null
                ? "도구 결과에 별도 액션이 포함되지 않았다면 내부 앱 액션은 필요하지 않습니다."
                : "클라이언트에 전달할 내부 앱 액션이 준비되었습니다: " + action.getType() + " / " + action.getTarget()
                + ". 최종 답변에서는 내부 액션 이름을 말하지 말고, 정보 답변을 먼저 작성하세요.";

        return basePrompt + "\n" + formatPrompt + "\n" + domainStylePrompt + "\n" + actionInstruction;
    }

    String buildBaseAnswerPrompt() {
        return """
                당신은 FLOWER 앱 안에서 동작하는 경량 Agentic RAG 챗봇입니다.
                사용자가 한국어로 쓰면 한국어로 답하고, 영어로 쓰면 영어로 답하세요.
                사용자 언어는 도구 이름, 필드명, 데이터베이스 값, 내부 컨텍스트 라벨의 언어보다 우선합니다.
                사용자가 한국어로 쓴 경우 최종 답변의 본문, 설명, 주의사항, 출처 언급을 모두 자연스러운 한국어로 작성하세요.
                "description", "growTips", "source", "Tool results", "lookup returned" 같은 영어 도구 라벨을 최종 답변에 그대로 복사하지 마세요.
                사실 근거는 이번 턴에 제공된 도구 결과와 앱 액션만 사용하세요.
                이전 assistant 답변이나 대화 기록이 이번 턴 도구 결과와 충돌하면 이번 턴 도구 결과를 우선하고 이전 내용은 무시하세요.
                정확한 개화일, 위치, 게시글 내용, 구매, 작성 완료 여부를 지어내지 마세요.
                조회 결과가 0건이면 활동량, 분위기, 경향, 원인, 인기 변화 같은 해석을 덧붙이지 말고 현재 확인된 정보가 없다고만 설명하세요.
                꽃 기본 정보는 flower.getBasicInfo 결과만 근거로 사용하세요.
                꽃말과 개화시기는 flower.getMeaningAndBloom 결과만 근거로 사용하세요.
                재배, 물주기, 햇빛, 토양, 관리 답변은 flower.getGrowGuide 결과만 근거로 사용하고 일반 원예 상식으로 보강하지 마세요.
                월별/계절 추천은 flower.recommendByMonth 결과를 우선 사용하고 3~5개만 짧게 설명하세요.
                모호한 설명에서 꽃 후보를 추정한 경우 flower.inferCandidates 결과만 사용하고, 확정 식별이 아니라 가능성 있는 후보라고 말하세요.
                꽃 축제 질문은 festival.searchFlowerFestivals 결과만 근거로 사용하고, 예매/예약/결제 가능 여부를 지어내지 마세요.
                커뮤니티 최신글/인기글은 community.getLatestPosts 또는 community.getPopularPosts 결과만 근거로 요약하세요.
                "많이 본 글"처럼 조회수 기준을 묻더라도 조회수 필드가 없으므로 좋아요와 댓글 기준 인기글이라고 말하세요.
                장소/지도 데이터는 꽃 정보보다 뒤에 보조로만 설명하세요. 장소 결과가 없더라도 꽃 정보가 있으면 정보부터 답하세요.
                flower.searchFlowerSpots 결과가 비어 있으면 일반 지식으로 장소, 지역, 계절 정보를 만들어 말하지 말고 등록된 장소 데이터가 없다고만 말하세요.
                길찾기 요청에서 장소 결과가 없으면 길찾기를 실행할 수 없다고 말하고 임의 목적지를 만들지 마세요.
                커뮤니티 작성처럼 쓰기 성격의 작업에서 작성 화면을 여는 경우, 내용을 생성하거나 자동 저장하지 않고 글 작성 화면만 연다고 명확히 말하세요.
                상점, 구매, 퀘스트, 미션, 인증, 포인트 지급 같은 미지원 요청은 실행하지 말고 아직 지원하지 않는다고 말하세요.
                내부 route, action, tool 이름은 최종 답변에 노출하지 마세요.
                화면 이동 액션이 있더라도 "화면을 열었습니다"를 정보 답변보다 먼저 말하지 마세요.
                """;
    }

    String buildAnswerFormatPrompt(String answerDomain, String answerTask) {
        if (isActionFirstAnswer(answerDomain, answerTask)) {
            return """
                    액션성 응답 형식:
                    1) 어떤 화면이나 기능을 열어드리는지 짧게 안내
                    2) 사용자가 직접 해야 하는 제한사항이 있으면 한 문장으로 명확히 안내
                    3) 필요할 때만 짧은 다음 행동 한 줄 추가
                    설명을 길게 늘리거나 핵심 정보 2~4개 형식을 억지로 맞추지 마세요.
                    """;
        }
        return """
                정보성 응답 형식:
                1) 직접 답변
                2) 핵심 정보 2~4개
                3) 출처 또는 데이터 없음 안내
                4) 필요한 경우에만 다음 행동 제안
                """;
    }

    private boolean isActionFirstAnswer(String answerDomain, String answerTask) {
        if ("community".equals(answerDomain) && "open_composer".equals(answerTask)) {
            return true;
        }
        if ("festival_info".equals(answerDomain) && "open_festival_map".equals(answerTask)) {
            return true;
        }
        return "app_navigation".equals(answerDomain);
    }

    String buildDomainStylePrompt(String answerDomain, String answerTask) {
        if ("community".equals(answerDomain)) {
            return buildCommunityStylePrompt(answerTask);
        }
        if ("festival_info".equals(answerDomain)) {
            return buildFestivalStylePrompt(answerTask);
        }
        if ("flower_info".equals(answerDomain)) {
            return buildFlowerStylePrompt(answerTask);
        }
        return "도메인 전용 문체 규칙이 없으면 공통 답변 골격만 유지하세요.";
    }

    private String buildCommunityStylePrompt(String answerTask) {
        String taskRule = switch (answerTask) {
            case "latest_posts" -> """
                    최신글은 시간 흐름 중심으로 요약하세요.
                    최근에 어떤 글이 올라왔는지 한눈에 들어오게 정리하고, 필요하면 기간 기준을 짧게 덧붙이세요.
                    조회 결과가 없으면 최근 활동 상태를 추정하지 말고 현재 확인된 최신 글이 없다고만 답하세요.
                    """;
            case "popular_posts" -> """
                    인기글은 좋아요와 댓글 기준으로 반응이 좋은 글처럼 설명하세요.
                    조회수 정보는 없으므로 조회수 기준이라고 말하지 마세요.
                    조회 결과가 없으면 반응이 적었다거나 잠잠하다는 해석을 붙이지 말고 현재 확인된 인기글이 없다고만 답하세요.
                    """;
            case "open_composer" -> """
                    글 작성 요청은 글을 대신 써주거나 저장한 것처럼 말하지 말고, 작성 화면만 열어 주는 제한을 분명히 설명하세요.
                    불필요하게 장황한 격려 문구를 붙이지 말고 짧고 분명하게 안내하세요.
                    """;
            default -> """
                    커뮤니티 답변은 검색 결과 나열보다 게시글 요약 브리핑처럼 작성하세요.
                    사용자가 어떤 후기나 게시글을 참고할 수 있는지 먼저 짧게 정리하세요.
                    조회 결과가 없으면 요즘 분위기, 활동량, 반응 추세를 해석하지 말고 현재 확인된 글이 없다고만 답하세요.
                    """;
        };

        return """
                커뮤니티 도메인 답변 규칙:
                첫 문장은 커뮤니티에서 확인된 흐름이나 게시글 성격을 바로 요약하세요.
                본문은 게시글을 단순 열거하지 말고, 요즘 어떤 내용이 올라오는지 읽기 쉽게 정리하세요.
                조회 결과가 비어 있으면 활발하다, 잠잠하다, 반응이 적었다 같은 추정 표현을 쓰지 마세요.
                필요할 때만 커뮤니티 화면에서 더 확인할 수 있다고 한 줄 덧붙이세요.
                """ + "\n" + taskRule;
    }

    private String buildFestivalStylePrompt(String answerTask) {
        String taskRule = switch (answerTask) {
            case "recommend_nearby" -> """
                    근처 축제 추천은 현재 위치 기준으로 가까운 후보를 먼저 언급하세요.
                    거리나 지역 비교가 가능할 때만 짧게 덧붙이세요.
                    """;
            case "open_festival_map" -> """
                    축제를 지도에서 보려는 요청이라도 답변 첫머리는 일정과 장소 요약을 우선하세요.
                    화면 이동 언급은 마지막에만 짧게 덧붙이세요.
                    """;
            default -> """
                    축제 답변은 성공적으로 조회된 경우에만 일정과 장소 중심의 브리핑처럼 작성하세요.
                    """;
        };

        return """
                축제 도메인 답변 규칙:
                첫 문장에서 언제 열리는 축제인지와 어디서 볼 수 있는지를 먼저 정리하세요.
                본문은 장소, 일정, 문의처 순으로 묶어 설명하세요.
                기간 필터가 있으면 이번 주, 이번 달, 다가오는 일정 같은 자연어 표현으로 풀어 쓰세요.
                운영 여부, 예약, 현장 상황은 조회 결과에 없으면 추정하지 마세요.
                조회 결과가 없으면 확인된 일정이 없다고만 말하고 분위기나 개최 가능성을 추정하지 마세요.
                조회 실패는 정보 없음으로 바꾸지 말고 지금은 가져올 수 없다고 구분해서 설명하세요.
                """ + "\n" + taskRule;
    }

    private String buildFlowerStylePrompt(String answerTask) {
        String taskRule = switch (answerTask) {
            case "meaning_bloom" -> """
                    꽃말과 개화 질문은 첫 문장에서 꽃말 또는 피는 시기를 바로 답하세요.
                    꽃 설명보다 꽃말과 개화 정보를 우선 배치하세요.
                    조회 항목이 없으면 설명을 확장하지 말고 확인된 정보가 없다고만 답하세요.
                    """;
            case "grow_guide" -> """
                    재배 질문은 관리 요점을 먼저 정리하고, 물주기·햇빛·토양 같은 실제 조회 항목만 이어서 설명하세요.
                    조회 항목이 없으면 일반 원예 상식을 절대 보강하지 마세요.
                    "보통", "일반적으로", "대체로" 같은 표현으로 외부 상식을 끼워 넣지 마세요.
                    """;
            case "monthly_recommendation" -> """
                    월별 추천은 계절감 있는 안내처럼 쓰되 3~5개 안에서만 간결하게 추천하세요.
                    각 꽃은 한 줄 정도의 짧은 이유만 붙이세요.
                    추천 데이터가 없으면 감성 문장으로 메우지 말고 확인된 추천 후보가 없다고 답하세요.
                    """;
            case "candidate_inference" -> """
                    후보 추정은 첫 문장에서 확정이 아니라 가능성 있는 후보라고 분명히 말하세요.
                    후보마다 왜 그렇게 본 것인지 짧게 설명하되 단정하지 마세요.
                    번호를 매겨 정답 목록처럼 보이게 하지 말고, 가능한 후보 몇 가지를 조심스럽게 제시하세요.
                    대표 후보, 가장 가능성이 높다 같은 순위형 표현을 피하세요.
                    """;
            default -> """
                    꽃 기본 정보는 도감형 설명처럼 쓰세요.
                    첫 문장에서 꽃의 정체나 핵심 특징을 바로 설명하세요.
                    조회된 항목이 없으면 설명을 확장하지 말고 확인된 정보가 없다고만 답하세요.
                    """;
        };

        return """
                꽃 정보 도메인 답변 규칙:
                답변은 꽃 도감 문장처럼 정리하고, 조회된 정보만 자연스럽게 연결하세요.
                설명, 꽃말, 개화, 재배 정보가 함께 있어도 질문 의도와 직접 맞는 정보를 먼저 배치하세요.
                감상 위주의 문장이나 과장된 표현보다 정보 전달을 우선하세요.
                """ + "\n" + taskRule;
    }

    private AnswerStyle resolveAnswerStyle(
            AgentPlan plan,
            String informationTask,
            List<ToolResult> toolResults,
            List<ChatAction> actions
    ) {
        for (ToolResult result : toolResults) {
            AnswerStyle style = answerStyleForTool(result.getTool(), plan);
            if (style != null) {
                return style;
            }
        }
        if (actionsContainTarget(actions, "COMMUNITY_COMPOSE")) {
            return new AnswerStyle("community", "open_composer");
        }
        if (plan.domain() != null && !plan.domain().isBlank()) {
            return new AnswerStyle(plan.domain(), plan.task());
        }
        return switch (informationTask) {
            case "basic_info", "meaning_bloom", "grow_guide", "monthly_recommendation", "candidate_inference" ->
                    new AnswerStyle("flower_info", informationTask);
            case "unsupported" -> new AnswerStyle("unsupported", "");
            default -> new AnswerStyle("", "");
        };
    }

    private AnswerStyle answerStyleForTool(String toolName, AgentPlan plan) {
        return switch (toolName) {
            case "community.searchPosts" -> new AnswerStyle("community", "search_posts");
            case "community.getLatestPosts" -> new AnswerStyle("community", "latest_posts");
            case "community.getPopularPosts" -> new AnswerStyle("community", "popular_posts");
            case "festival.searchFlowerFestivals" ->
                    new AnswerStyle("festival_info", plan.task() == null || plan.task().isBlank()
                            ? "search_festivals"
                            : plan.task());
            case "flower.getBasicInfo", "flower.lookupDescriptionSource" ->
                    new AnswerStyle("flower_info", "basic_info");
            case "flower.getMeaningAndBloom" -> new AnswerStyle("flower_info", "meaning_bloom");
            case "flower.getGrowGuide", "flower.lookupGrowTipsSource" ->
                    new AnswerStyle("flower_info", "grow_guide");
            case "flower.recommendByMonth", "flower.recommendSeasonalFlowers" ->
                    new AnswerStyle("flower_info", "monthly_recommendation");
            case "flower.inferCandidates" -> new AnswerStyle("flower_info", "candidate_inference");
            default -> null;
        };
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
                "\uAF43\uB9D0", "\uD559\uBA85", "\uAC1C\uD654", "\uC2DC\uAE30",
                "\uC5B8\uC81C", "\uD53C\uC5B4", "\uD53C\uB294", "\uC5B4\uB5A4",
                "\uBB50\uC57C", "\uBB54\uC9C0", "\uBB50\uC9C0", "\uAF43\uC774\uC57C",
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
            String informationTask,
            String searchKeyword,
            List<ChatAction> actions,
            String source,
            String domain,
            String task,
            String dateFilter,
            int month,
            int year,
            boolean nearby,
            boolean routeRequest,
            String routeMode,
            boolean needsScreen,
            String confidence,
            String reason
    ) {
        private AgentPlan(
                List<RouteIntent> intents,
                String informationTask,
                String searchKeyword,
                List<ChatAction> actions,
                String source
        ) {
            this(intents, informationTask, searchKeyword, actions, source, "", "", "none", 0, 0, false, false, "none", false, "", "");
        }
    }

    private String mapFocusActionType(ToolResult result, AgentPlan plan) {
        if ("flower.searchFlowerSpots".equals(result.getTool()) && plan.routeRequest()) {
            return "none".equals(normalizeRouteMode(plan.routeMode()))
                    ? "MAP_OPEN_ROUTE_CHOOSER"
                    : "MAP_START_ROUTE";
        }
        return "flower.searchFlowerSpots".equals(result.getTool())
                ? "MAP_OPEN_FLOWER_PREVIEW"
                : "MAP_SHOW_FLOWER";
    }

    private record PlannerDecision(
            String domain,
            String task,
            String keyword,
            String dateFilter,
            int month,
            int year,
            boolean nearby,
            boolean routeRequest,
            String routeMode,
            boolean needsScreen,
            String confidence,
            String reason,
            String source
    ) {
    }

    private record CommunityPeriod(
            String dateFilter,
            int month,
            int year
    ) {
    }

    private record PlannerValidation(
            boolean valid,
            List<String> errors
    ) {
    }

    private record AnswerStyle(
            String domain,
            String task
    ) {
    }

    private record AgentExecution(
            List<ChatAction> actions,
            List<ToolResult> toolResults,
            AgentRunTrace trace,
            String answerDomain,
            String answerTask
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
            if (data.get("candidates") instanceof List<?> candidates) {
                return formatCandidatesForAnswer(result.getTool(), data, candidates);
            }
            return "- " + koreanToolLabel(result.getTool()) + ": " + data + "\n";
        }

        private String formatItemsForAnswer(String tool, Map<String, Object> data, List<?> items) {
            StringBuilder context = new StringBuilder();
            if (items.isEmpty()) {
                context.append("- ").append(koreanToolLabel(tool)).append(": 조회된 데이터 없음.\n");
                appendFestivalDateFilterContext(context, tool, data);
                appendCommunityPostContext(context, tool, data);
                if (Boolean.TRUE.equals(data.get("queryExpanded")) && data.get("candidateKeywords") instanceof List<?> candidates) {
                    context.append("  - 사용자가 꽃 이름을 특정하지 않아 후보 꽃으로 확장 검색함: ")
                            .append(candidates)
                            .append("\n");
                }
                return context.toString();
            }
            context.append("- ").append(koreanToolLabel(tool)).append(" 데이터:\n");
            appendFestivalDateFilterContext(context, tool, data);
            appendCommunityPostContext(context, tool, data);
            if (Boolean.TRUE.equals(data.get("queryExpanded")) && data.get("candidateKeywords") instanceof List<?> candidates) {
                context.append("  - 사용자가 꽃 이름을 특정하지 않아 후보 꽃으로 확장 검색함: ")
                        .append(candidates)
                        .append("\n");
            }
            for (Object item : items) {
                if (item instanceof Map<?, ?> row) {
                    context.append("  - ");
                    appendIfPresent(context, row, "name", "이름");
                    appendIfPresent(context, row, "title", "축제명");
                    appendIfPresent(context, row, "id", "게시글 ID");
                    appendIfPresent(context, row, "nickname", "작성자");
                    appendIfPresent(context, row, "content", "내용");
                    appendIfPresent(context, row, "species", "꽃 종류");
                    appendIfPresent(context, row, "plantName", "식물명");
                    appendIfPresent(context, row, "scientificName", "학명");
                    appendIfPresent(context, row, "description", "설명");
                    appendIfPresent(context, row, "shortDescription", "요약");
                    appendIfPresent(context, row, "growTips", "재배 팁");
                    appendIfPresent(context, row, "source", "출처");
                    appendIfPresent(context, row, "bloomDate", "개화");
                    appendIfPresent(context, row, "flowerLanguage", "꽃말");
                    appendIfPresent(context, row, "imageUrl", "이미지");
                    appendIfPresent(context, row, "spotCount", "승인 명소 수");
                    appendIfPresent(context, row, "representativeSpotName", "대표 명소");
                    appendIfPresent(context, row, "address", "주소");
                    appendIfPresent(context, row, "period", "기간");
                    appendIfPresent(context, row, "tel", "문의");
                    appendIfPresent(context, row, "likes", "좋아요");
                    appendIfPresent(context, row, "comments", "댓글");
                    appendIfPresent(context, row, "createdAt", "작성일");
                    appendIfPresent(context, row, "distanceKm", "거리 km");
                    appendIfPresent(context, row, "bloomStart", "개화 시작");
                    appendIfPresent(context, row, "bloomEnd", "개화 종료");
                    context.append("\n");
                } else {
                    context.append("  - ").append(item).append("\n");
                }
            }
            return context.toString();
        }

        private void appendFestivalDateFilterContext(StringBuilder context, String tool, Map<String, Object> data) {
            if (!"festival.searchFlowerFestivals".equals(tool)) {
                return;
            }
            Object dateFilter = data.get("dateFilter");
            Object rangeStart = data.get("rangeStart");
            Object rangeEnd = data.get("rangeEnd");
            if (dateFilter == null || rangeStart == null) {
                return;
            }
            context.append("  - 조회 기간 기준=")
                    .append(dateFilter)
                    .append(", 시작=")
                    .append(rangeStart);
            if (rangeEnd != null && !rangeEnd.toString().isBlank()) {
                context.append(", 종료=").append(rangeEnd);
            }
            context.append(", 이미 종료된 축제 제외\n");
        }

        private void appendCommunityPostContext(StringBuilder context, String tool, Map<String, Object> data) {
            if (!List.of("community.getLatestPosts", "community.getPopularPosts").contains(tool)) {
                return;
            }
            Object dateFilter = data.get("dateFilter");
            Object rangeStart = data.get("rangeStart");
            Object rangeEnd = data.get("rangeEnd");
            Object rankingBasis = data.get("rankingBasis");
            context.append("  - 조회 기준=").append(dateFilter == null ? "none" : dateFilter);
            if (rangeStart != null && !rangeStart.toString().isBlank()) {
                context.append(", 시작=").append(rangeStart);
            }
            if (rangeEnd != null && !rangeEnd.toString().isBlank()) {
                context.append(", 종료=").append(rangeEnd);
            }
            if (rankingBasis != null && !rankingBasis.toString().isBlank()) {
                context.append(", 정렬=").append(rankingBasis);
            }
            context.append("\n");
        }

        private String formatCandidatesForAnswer(String tool, Map<String, Object> data, List<?> candidates) {
            StringBuilder context = new StringBuilder();
            if (candidates.isEmpty()) {
                context.append("- ").append(koreanToolLabel(tool)).append(": 추정 후보 없음.\n");
                return context.toString();
            }
            context.append("- ").append(koreanToolLabel(tool)).append(" 데이터:\n");
            context.append("  - 사용자가 제공한 설명만으로는 확정할 수 없고, 후보로만 제시해야 함.\n");
            for (Object item : candidates) {
                if (item instanceof Map<?, ?> row) {
                    context.append("  - ");
                    appendIfPresent(context, row, "name", "후보");
                    appendIfPresent(context, row, "reason", "이유");
                    appendIfPresent(context, row, "scientificName", "학명");
                    appendIfPresent(context, row, "description", "설명");
                    appendIfPresent(context, row, "flowerLanguage", "꽃말");
                    appendIfPresent(context, row, "bloomDate", "개화");
                    appendIfPresent(context, row, "source", "출처");
                    appendIfPresent(context, row, "confidenceHint", "주의");
                    context.append("\n");
                } else {
                    context.append("  - ").append(item).append("\n");
                }
            }
            return context.toString();
        }

        private String koreanToolLabel(String tool) {
            return switch (tool) {
                case "flower.getBasicInfo", "flower.lookupDescriptionSource" -> "꽃 기본 정보 조회";
                case "flower.getMeaningAndBloom" -> "꽃말/개화 정보 조회";
                case "flower.getGrowGuide", "flower.lookupGrowTipsSource" -> "꽃 재배 가이드 조회";
                case "flower.recommendByMonth", "flower.recommendSeasonalFlowers" -> "월별 꽃 추천";
                case "flower.inferCandidates" -> "꽃 후보 추정";
                case "flower.searchFlowerSpots" -> "꽃 명소 조회";
                case "festival.searchFlowerFestivals" -> "꽃 축제 조회";
                case "community.searchPosts", "searchCommunityPosts" -> "커뮤니티 글 조회";
                case "community.getLatestPosts" -> "커뮤니티 최신글 조회";
                case "community.getPopularPosts" -> "커뮤니티 인기글 조회";
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
                case "MAP_OPEN_ROUTE_CHOOSER" -> "지도에서 길찾기 이동수단 선택 열기";
                case "MAP_START_ROUTE" -> "지도에서 길찾기 실행";
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
