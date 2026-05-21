package com.flower.backend.chatbot.service;

import com.flower.backend.chatbot.dto.ChatAction;
import com.flower.backend.chatbot.dto.ChatMessageRequest;
import com.flower.backend.chatbot.dto.ChatMessageResponse;
import com.flower.backend.chatbot.dto.ToolResult;
import com.flower.backend.chatbot.tool.CommunityAgent.CommunityTools;
import com.flower.backend.chatbot.tool.FestivalAgent.FestivalToolService;
import com.flower.backend.chatbot.tool.FlowerAgent.FlowerToolService;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatbotServiceTest {

    private FlowerToolService flowerToolService;
    private CommunityTools communityTools;
    private FestivalToolService festivalToolService;
    private ChatbotService chatbotService;

    @BeforeEach
    void setUp() {
        flowerToolService = mock(FlowerToolService.class);
        communityTools = mock(CommunityTools.class);
        festivalToolService = mock(FestivalToolService.class);
        chatbotService = new ChatbotService(flowerToolService, communityTools, festivalToolService, noChatClient(), "");

        when(flowerToolService.searchFlowerSpots(anyString())).thenReturn(List.of());
        when(flowerToolService.lookupFlowerDescriptionSourceResult(anyString(), anyBoolean()))
                .thenReturn(tool("flower.lookupDescriptionSource"));
        when(flowerToolService.lookupFlowerGrowTipsSourceResult(anyString(), anyBoolean()))
                .thenReturn(tool("flower.lookupGrowTipsSource"));
        when(flowerToolService.recommendSeasonalFlowersResult(any()))
                .thenReturn(tool("flower.recommendSeasonalFlowers"));
        when(flowerToolService.getBasicInfoResult(anyString(), anyBoolean()))
                .thenReturn(tool("flower.getBasicInfo"));
        when(flowerToolService.getMeaningAndBloomResult(anyString(), anyBoolean()))
                .thenReturn(tool("flower.getMeaningAndBloom"));
        when(flowerToolService.getGrowGuideResult(anyString(), anyBoolean()))
                .thenReturn(tool("flower.getGrowGuide"));
        when(flowerToolService.recommendByMonthResult(any()))
                .thenReturn(tool("flower.recommendByMonth"));
        when(flowerToolService.inferCandidatesResult(anyString()))
                .thenReturn(tool("flower.inferCandidates"));
        when(flowerToolService.searchFlowerSpotsResult(anyString()))
                .thenReturn(tool("flower.searchFlowerSpots"));
        when(communityTools.searchPosts(anyString()))
                .thenReturn(tool("community.searchPosts"));
        when(communityTools.getLatestPosts(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(tool("community.getLatestPosts"));
        when(communityTools.getPopularPosts(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(tool("community.getPopularPosts"));
    }

    @Test
    void fallbackRoutesFlowerMapRequestToMapSearchAction() {
        ChatMessageResponse response = chatbotService.chat(request("벚꽃 지도에서 보여줘"));

        assertThat(response.getAgentRun().getRoute()).isEqualTo("MAP_FLOWER");
        assertThat(response.getActions()).extracting(ChatAction::getType)
                .contains("NAVIGATE", "MAP_SET_SEARCH_QUERY");
        assertThat(response.getActions().get(1).getParams()).containsEntry("query", "벚꽃");
        assertThat(response.getToolResults()).extracting(ToolResult::getTool)
                .containsExactly("flower.getBasicInfo");
        verify(flowerToolService).getBasicInfoResult("벚꽃", false);
    }

    @Test
    void fallbackUsesSeasonalRecommendationToolForMonthlyRecommendation() {
        ChatMessageResponse response = chatbotService.chat(request("이번 달에 볼 만한 꽃 추천해줘"));

        assertThat(response.getAgentRun().getRoute()).isEqualTo("FLOWER");
        assertThat(response.getToolResults()).extracting(ToolResult::getTool)
                .containsExactly("flower.recommendByMonth");
        verify(flowerToolService).recommendByMonthResult(any());
    }

    @Test
    void fallbackUsesGrowTipToolForCareQuestion() {
        ChatMessageResponse response = chatbotService.chat(request("장미 키우는 법 알려줘"));

        assertThat(response.getAgentRun().getRoute()).isEqualTo("FLOWER_GROW");
        assertThat(response.getToolResults()).extracting(ToolResult::getTool)
                .containsExactly("flower.getGrowGuide");
        assertThat(response.getReply()).contains("'장미' 재배 정보를 바로 안내드리기 어려워요.");
        assertThat(response.getReply()).doesNotContain("햇빛");
        verify(flowerToolService).getGrowGuideResult("장미", false);
    }

    @Test
    void communitySearchPassesQueryToActionAndTool() {
        ChatMessageResponse response = chatbotService.chat(request("수국 후기 찾아줘"));

        assertThat(response.getAgentRun().getRoute()).isEqualTo("COMMUNITY");
        assertThat(response.getActions()).hasSize(1);
        assertThat(response.getActions().get(0).getTarget()).isEqualTo("COMMUNITY");
        assertThat(response.getActions().get(0).getParams()).containsEntry("query", "수국");
        verify(communityTools).searchPosts("수국");
    }

    @Test
    void communityComposeDoesNotSearchOrGenerateDraft() {
        ChatMessageResponse response = chatbotService.chat(request("수국 후기 글 써줘"));

        assertThat(response.getActions()).hasSize(1);
        assertThat(response.getActions().get(0).getTarget()).isEqualTo("COMMUNITY_COMPOSE");
        assertThat(response.getToolResults()).isEmpty();
        verify(communityTools, never()).searchPosts(anyString());
    }

    @Test
    void latestCommunityRequestUsesLatestToolWithoutKeywordSearch() {
        ChatMessageResponse response = chatbotService.chat(request("최신 글들은 어떤 걸 소개 해?"));

        assertThat(response.getAgentRun().getRoute()).isEqualTo("COMMUNITY");
        assertThat(response.getToolResults()).extracting(ToolResult::getTool)
                .containsExactly("community.getLatestPosts");
        verify(communityTools).getLatestPosts("", "none", 0, 0);
        verify(communityTools, never()).searchPosts(anyString());
    }

    @Test
    void popularCommunityRequestUsesPopularToolWithPeriod() {
        ChatMessageResponse response = chatbotService.chat(request("이번 주 인기글 보여줘"));

        assertThat(response.getAgentRun().getRoute()).isEqualTo("COMMUNITY");
        assertThat(response.getActions()).extracting(ChatAction::getTarget)
                .contains("COMMUNITY");
        assertThat(response.getToolResults()).extracting(ToolResult::getTool)
                .containsExactly("community.getPopularPosts");
        verify(communityTools).getPopularPosts("", "this_week", 0, 0);
        verify(communityTools, never()).searchPosts(anyString());
    }

    @Test
    void monthlyPopularCommunityRequestPassesMonthFilter() {
        ChatMessageResponse response = chatbotService.chat(request("3월 인기글 보여줘"));

        assertThat(response.getToolResults()).extracting(ToolResult::getTool)
                .containsExactly("community.getPopularPosts");
        verify(communityTools).getPopularPosts("", "month", 3, java.time.LocalDate.now().getYear());
        verify(communityTools, never()).searchPosts(anyString());
    }

    @Test
    void unsupportedShopRequestReturnsNoAction() {
        ChatMessageResponse response = chatbotService.chat(request("상점에서 아이템 사줘"));

        assertThat(response.getActions()).isEmpty();
        assertThat(response.getToolResults()).extracting(ToolResult::getTool)
                .containsExactly("app.unsupported");
    }

    @Test
    void answerPromptKeepsBaseRulesAcrossDomains() {
        String prompt = chatbotService.buildAnswerSystemPrompt("", "", null);

        assertThat(prompt).contains("사용자가 한국어로 쓰면 한국어로 답하고, 영어로 쓰면 영어로 답하세요.");
        assertThat(prompt).contains("사실 근거는 이번 턴에 제공된 도구 결과와 앱 액션만 사용하세요.");
        assertThat(prompt).contains("이전 assistant 답변이나 대화 기록이 이번 턴 도구 결과와 충돌하면");
        assertThat(prompt).contains("조회 결과가 0건이면 활동량, 분위기, 경향, 원인, 인기 변화 같은 해석을 덧붙이지 말고");
        assertThat(prompt).contains("내부 route, action, tool 이름은 최종 답변에 노출하지 마세요.");
        assertThat(prompt).contains("정보성 응답 형식:");
        assertThat(prompt).contains("1) 직접 답변");
    }

    @Test
    void answerPromptAddsCommunityStyleRules() {
        String prompt = chatbotService.buildAnswerSystemPrompt("community", "popular_posts", null);

        assertThat(prompt).contains("커뮤니티 도메인 답변 규칙:");
        assertThat(prompt).contains("게시글 요약 브리핑");
        assertThat(prompt).contains("좋아요와 댓글 기준으로 반응이 좋은 글처럼 설명하세요.");
        assertThat(prompt).contains("조회수 정보는 없으므로 조회수 기준이라고 말하지 마세요.");
        assertThat(prompt).contains("조회 결과가 없으면 반응이 적었다거나 잠잠하다는 해석을 붙이지 말고");
        assertThat(prompt).contains("활발하다, 잠잠하다, 반응이 적었다 같은 추정 표현을 쓰지 마세요.");
    }

    @Test
    void answerPromptAddsFestivalStyleRules() {
        String prompt = chatbotService.buildAnswerSystemPrompt("festival_info", "open_festival_map", null);

        assertThat(prompt).contains("축제 도메인 답변 규칙:");
        assertThat(prompt).contains("언제 열리는 축제인지와 어디서 볼 수 있는지를 먼저 정리하세요.");
        assertThat(prompt).contains("장소, 일정, 문의처 순으로 묶어 설명하세요.");
        assertThat(prompt).contains("화면 이동 언급은 마지막에만 짧게 덧붙이세요.");
        assertThat(prompt).contains("조회 결과가 없으면 확인된 일정이 없다고만 말하고");
        assertThat(prompt).contains("조회 실패는 정보 없음으로 바꾸지 말고");
    }

    @Test
    void answerPromptAddsFlowerCandidateStyleRules() {
        String prompt = chatbotService.buildAnswerSystemPrompt("flower_info", "candidate_inference", null);

        assertThat(prompt).contains("꽃 정보 도메인 답변 규칙:");
        assertThat(prompt).contains("답변은 꽃 도감 문장처럼 정리");
        assertThat(prompt).contains("확정이 아니라 가능성 있는 후보라고 분명히 말하세요.");
        assertThat(prompt).contains("단정하지 마세요.");
        assertThat(prompt).contains("번호를 매겨 정답 목록처럼 보이게 하지 말고");
        assertThat(prompt).contains("대표 후보, 가장 가능성이 높다 같은 순위형 표현을 피하세요.");
    }

    @Test
    void answerPromptUsesActionFirstFormatForComposer() {
        String prompt = chatbotService.buildAnswerSystemPrompt("community", "open_composer", null);

        assertThat(prompt).contains("액션성 응답 형식:");
        assertThat(prompt).contains("어떤 화면이나 기능을 열어드리는지 짧게 안내");
        assertThat(prompt).contains("설명을 길게 늘리거나 핵심 정보 2~4개 형식을 억지로 맞추지 마세요.");
        assertThat(prompt).contains("불필요하게 장황한 격려 문구를 붙이지 말고 짧고 분명하게 안내하세요.");
    }

    @Test
    void answerPromptStrengthensFlowerGrowGuideGuardrails() {
        String prompt = chatbotService.buildAnswerSystemPrompt("flower_info", "grow_guide", null);

        assertThat(prompt).contains("조회 항목이 없으면 일반 원예 상식을 절대 보강하지 마세요.");
        assertThat(prompt).contains("\"보통\", \"일반적으로\", \"대체로\" 같은 표현으로 외부 상식을 끼워 넣지 마세요.");
    }

    @Test
    void guardedAnswerTranslatesFestivalErrorIntoNaturalMessage() {
        ToolResult festivalError = ToolResult.builder()
                .tool("festival.searchFlowerFestivals")
                .status("ERROR")
                .summary("festival error")
                .error("TOUR_API_KEY is not configured.")
                .data(Map.of(
                        "items", List.of(),
                        "dateFilter", "this_month"
                ))
                .build();

        String reply = chatbotService.buildGuardedAnswer(
                "이번 달 꽃 축제 알려줘",
                List.of(festivalError),
                "festival_info",
                "search_festivals");

        assertThat(reply).contains("이번 달 기준으로 꽃 축제 정보를 지금은 가져올 수 없어요.");
        assertThat(reply).contains("외부 연동 설정이 없어 축제 데이터를 확인하지 못했습니다.");
        assertThat(reply).doesNotContain("TOUR_API_KEY");
    }

    @Test
    void guardedAnswerUsesCandidateNoDataTemplate() {
        ToolResult candidateResult = ToolResult.builder()
                .tool("flower.inferCandidates")
                .status("SUCCESS")
                .summary("candidate empty")
                .data(Map.of("candidates", List.of()))
                .build();

        String reply = chatbotService.buildGuardedAnswer(
                "분홍색 꽃인데 이름이 뭘까?",
                List.of(candidateResult),
                "flower_info",
                "candidate_inference");

        assertThat(reply).contains("설명만으로는 지금 꽃 후보를 자연스럽게 좁혀 드리기 어려워요.");
        assertThat(reply).contains("사진이나 모양, 크기, 핀 시기 같은 특징");
    }

    private ChatMessageRequest request(String message) {
        return new ChatMessageRequest(message, "test-session", null);
    }

    private ToolResult tool(String name) {
        return ToolResult.builder()
                .tool(name)
                .status("SUCCESS")
                .summary(name + " ok")
                .data(Map.of("items", List.of()))
                .build();
    }

    private ObjectProvider<ChatClient.Builder> noChatClient() {
        return new ObjectProvider<>() {
            @Override
            public ChatClient.Builder getObject(Object... args) {
                return null;
            }

            @Override
            public ChatClient.Builder getIfAvailable() {
                return null;
            }

            @Override
            public ChatClient.Builder getIfUnique() {
                return null;
            }

            @Override
            public ChatClient.Builder getObject() {
                return null;
            }

            @Override
            public Iterator<ChatClient.Builder> iterator() {
                return List.<ChatClient.Builder>of().iterator();
            }

            @Override
            public Stream<ChatClient.Builder> stream() {
                return Stream.empty();
            }

            @Override
            public Stream<ChatClient.Builder> orderedStream() {
                return Stream.empty();
            }
        };
    }
}
