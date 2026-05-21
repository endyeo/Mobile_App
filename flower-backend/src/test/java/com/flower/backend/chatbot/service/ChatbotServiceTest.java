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
