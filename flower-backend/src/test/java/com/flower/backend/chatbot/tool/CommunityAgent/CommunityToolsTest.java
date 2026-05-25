package com.flower.backend.chatbot.tool.CommunityAgent;

import com.flower.backend.auth.User;
import com.flower.backend.chatbot.dto.ToolResult;
import com.flower.backend.chatbot.tool.ChatbotActionContext;
import com.flower.backend.community.CommunityPost;
import com.flower.backend.community.CommunityPostRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommunityToolsTest {

    private CommunityPostRepository postRepository;
    private CommunityTools communityTools;

    @BeforeEach
    void setUp() {
        postRepository = mock(CommunityPostRepository.class);
        communityTools = new CommunityTools(postRepository, mock(ChatbotActionContext.class));
    }

    @Test
    void latestPostsFallsBackToFeedWhenSpecializedQueryFails() {
        when(postRepository.findLatestPosts(anyString(), any(), any(), any(Pageable.class)))
                .thenThrow(new RuntimeException("bad query"));
        when(postRepository.findFeed(any(Pageable.class)))
                .thenReturn(List.of(
                        post(1L, "장미 산책 후기", "장미", "장미", 1, 0, LocalDateTime.now().minusHours(2)),
                        post(2L, "수국 이야기", "수국", "수국", 10, 3, LocalDateTime.now().minusDays(1))
                ));

        ToolResult result = communityTools.getLatestPosts("장미", "none", 0, 0);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getData()).containsEntry("queryFailed", true);
        assertThat(result.getData()).containsEntry("periodFallbackUsed", false);
        assertThat((List<?>) result.getData().get("items")).hasSize(1);
        assertThat(((List<Map<String, Object>>) result.getData().get("items")).get(0))
                .containsEntry("content", "장미 산책 후기");
    }

    @Test
    void latestPostsFallbackMatchesAddress() {
        when(postRepository.findLatestPosts(anyString(), any(), any(), any(Pageable.class)))
                .thenThrow(new RuntimeException("bad query"));
        when(postRepository.findFeed(any(Pageable.class)))
                .thenReturn(List.of(
                        post(1L, "꽃 본 날", "장미", "장미", "서울식물원", 1, 0, LocalDateTime.now().minusHours(2)),
                        post(2L, "수국 이야기", "수국", "수국", "부산 공원", 10, 3, LocalDateTime.now().minusDays(1))
                ));

        ToolResult result = communityTools.getLatestPosts("서울식물원", "none", 0, 0);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat((List<?>) result.getData().get("items")).hasSize(1);
        assertThat(((List<Map<String, Object>>) result.getData().get("items")).get(0))
                .containsEntry("address", "서울식물원")
                .doesNotContainKey("title");
    }

    @Test
    void searchPostsReturnsSuccessWithEmptyItemsWhenNoData() {
        when(postRepository.searchByKeyword(anyString(), any(Pageable.class)))
                .thenReturn(List.of());

        ToolResult result = communityTools.searchPosts("수국");

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getTool()).isEqualTo("community.searchPosts");
        assertThat((List<?>) result.getData().get("items")).isEmpty();
        assertThat(result.getError()).isNull();
    }

    @Test
    void popularPostsReturnsErrorWithDiagnosticsWhenFallbackAlsoFails() {
        when(postRepository.findPopularPosts(anyString(), any(), any(), any(Pageable.class)))
                .thenThrow(new RuntimeException("bad popular query"));
        when(postRepository.findFeed(any(Pageable.class)))
                .thenThrow(new RuntimeException("feed unavailable"));

        ToolResult result = communityTools.getPopularPosts("", "this_week", 0, 0);

        assertThat(result.getStatus()).isEqualTo("ERROR");
        assertThat(result.getData()).containsEntry("queryFailed", true);
        assertThat(result.getData()).containsEntry("periodFallbackUsed", true);
        assertThat(result.getData()).containsEntry("failureStage", "fallback_feed");
        assertThat(result.getData()).containsEntry("failureReason", "RuntimeException");
        assertThat((List<?>) result.getData().get("items")).isEmpty();
    }

    private CommunityPost post(
            Long id,
            String content,
            String flowerSpecies,
            String plantName,
            int likeCount,
            int commentCount,
            LocalDateTime createdAt
    ) {
        return post(id, content, flowerSpecies, plantName, "", likeCount, commentCount, createdAt);
    }

    private CommunityPost post(
            Long id,
            String content,
            String flowerSpecies,
            String plantName,
            String address,
            int likeCount,
            int commentCount,
            LocalDateTime createdAt
    ) {
        User user = mock(User.class);
        when(user.getNickname()).thenReturn("tester");
        CommunityPost post = CommunityPost.builder()
                .user(user)
                .content(content)
                .flowerSpecies(flowerSpecies)
                .plantName(plantName)
                .address(address)
                .build();
        ReflectionTestUtils.setField(post, "id", id);
        ReflectionTestUtils.setField(post, "likeCount", likeCount);
        ReflectionTestUtils.setField(post, "commentCount", commentCount);
        ReflectionTestUtils.setField(post, "createdAt", createdAt);
        return post;
    }
}
