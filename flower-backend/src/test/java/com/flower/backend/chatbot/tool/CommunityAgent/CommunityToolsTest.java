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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    void latestPostsUsesWholeFeedLatestQueryWhenKeywordIsBlank() {
        when(postRepository.findLatestPosts(any(Pageable.class)))
                .thenReturn(List.of(
                        post(23L, "가장 최신 글", "수국", "수국", 1, 0, LocalDateTime.now()),
                        post(22L, "두 번째 글", "장미", "장미", 0, 0, LocalDateTime.now().minusHours(1))
                ));

        ToolResult result = communityTools.getLatestPosts("", "today", 5, 2026);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getData()).containsEntry("rankingBasis", "createdAt DESC");
        assertThat(result.getData()).doesNotContainKeys("dateFilter", "rangeStart", "rangeEnd", "periodFallbackUsed");
        assertThat((List<?>) result.getData().get("items")).hasSize(2);
        verify(postRepository).findLatestPosts(pageableWithSize(5));
        verify(postRepository, never()).findLatestPostsByKeyword(any(), any(Pageable.class));
    }

    @Test
    void latestPostsUsesKeywordLatestQueryOnlyWhenKeywordExists() {
        when(postRepository.findLatestPostsByKeyword(any(), any(Pageable.class)))
                .thenReturn(List.of(
                        post(1L, "서울식물원 꽃 본 날", "장미", "장미", "서울식물원", 1, 0, LocalDateTime.now())
                ));

        ToolResult result = communityTools.getLatestPosts("서울식물원", "none", 0, 0);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        List<Map<String, Object>> items = items(result);
        assertThat(items).hasSize(1);
        assertThat(items.get(0))
                .containsEntry("address", "서울식물원")
                .doesNotContainKey("title");
        verify(postRepository).findLatestPostsByKeyword(eq("서울식물원"), pageableWithSize(5));
        verify(postRepository, never()).findLatestPosts(any(Pageable.class));
    }

    @Test
    void popularPostsUsesWholeFeedLikeOnlyQueryWhenKeywordIsBlank() {
        when(postRepository.findPopularPosts(any(Pageable.class)))
                .thenReturn(List.of(
                        post(10L, "좋아요 제일 많은 글", "장미", "장미", 8, 0, LocalDateTime.now().minusDays(2)),
                        post(9L, "그 다음 글", "수국", "수국", 5, 100, LocalDateTime.now())
                ));

        ToolResult result = communityTools.getPopularPosts("", "this_week", 0, 0);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getData()).containsEntry("rankingBasis", "likeCount DESC, id DESC");
        assertThat(items(result)).extracting(item -> item.get("id"))
                .containsExactly(10L, 9L);
        verify(postRepository).findPopularPosts(pageableWithSize(5));
        verify(postRepository, never()).findPopularPostsByKeyword(any(), any(Pageable.class));
    }

    @Test
    void popularPostsUsesKeywordPopularQueryOnlyWhenKeywordExists() {
        when(postRepository.findPopularPostsByKeyword(any(), any(Pageable.class)))
                .thenReturn(List.of(
                        post(3L, "장미 인기글", "장미", "장미", 4, 0, LocalDateTime.now())
                ));

        ToolResult result = communityTools.getPopularPosts("장미", "none", 0, 0);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(items(result)).extracting(item -> item.get("content"))
                .containsExactly("장미 인기글");
        verify(postRepository).findPopularPostsByKeyword(eq("장미"), pageableWithSize(5));
        verify(postRepository, never()).findPopularPosts(any(Pageable.class));
    }

    @Test
    void popularPostsReturnsSuccessWithEmptyItemsWhenNoData() {
        when(postRepository.findPopularPosts(any(Pageable.class)))
                .thenReturn(List.of());

        ToolResult result = communityTools.getPopularPosts("", "this_week", 0, 0);

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(items(result)).isEmpty();
        assertThat(result.getError()).isNull();
    }

    @Test
    void latestPostsReturnsErrorWithQueryFailureStageWhenRepositoryFails() {
        when(postRepository.findLatestPosts(any(Pageable.class)))
                .thenThrow(new RuntimeException("bad latest query"));

        ToolResult result = communityTools.getLatestPosts("", "none", 0, 0);

        assertThat(result.getStatus()).isEqualTo("ERROR");
        assertThat(result.getData()).containsEntry("failureStage", "latest_query");
        assertThat(result.getData()).containsEntry("failureReason", "RuntimeException");
        assertThat(items(result)).isEmpty();
        verify(postRepository, never()).findFeed(any(Pageable.class));
    }

    @Test
    void popularPostsReturnsErrorWithQueryFailureStageWhenRepositoryFails() {
        when(postRepository.findPopularPosts(any(Pageable.class)))
                .thenThrow(new RuntimeException("bad popular query"));

        ToolResult result = communityTools.getPopularPosts("", "none", 0, 0);

        assertThat(result.getStatus()).isEqualTo("ERROR");
        assertThat(result.getData()).containsEntry("failureStage", "popular_query");
        assertThat(result.getData()).containsEntry("failureReason", "RuntimeException");
        assertThat(items(result)).isEmpty();
        verify(postRepository, never()).findFeed(any(Pageable.class));
    }

    @Test
    void latestPostsReturnsErrorWithToItemsStageWhenItemConversionFails() {
        CommunityPost post = post(1L, "내용", "장미", "장미", 1, 0, LocalDateTime.now());
        when(post.getUser().getNickname()).thenThrow(new RuntimeException("nickname unavailable"));
        when(postRepository.findLatestPosts(any(Pageable.class)))
                .thenReturn(List.of(post));

        ToolResult result = communityTools.getLatestPosts("", "none", 0, 0);

        assertThat(result.getStatus()).isEqualTo("ERROR");
        assertThat(result.getData()).containsEntry("failureStage", "to_items");
        assertThat(result.getData()).containsEntry("failureReason", "RuntimeException");
        assertThat(items(result)).isEmpty();
    }

    @Test
    void searchPostsReturnsSuccessWithEmptyItemsWhenNoData() {
        when(postRepository.searchByKeyword(any(), any(Pageable.class)))
                .thenReturn(List.of());

        ToolResult result = communityTools.searchPosts("수국");

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getTool()).isEqualTo("community.searchPosts");
        assertThat(items(result)).isEmpty();
        assertThat(result.getError()).isNull();
    }

    private Pageable pageableWithSize(int size) {
        return org.mockito.ArgumentMatchers.argThat(pageable -> pageable != null && pageable.getPageSize() == size);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(ToolResult result) {
        return (List<Map<String, Object>>) result.getData().get("items");
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
