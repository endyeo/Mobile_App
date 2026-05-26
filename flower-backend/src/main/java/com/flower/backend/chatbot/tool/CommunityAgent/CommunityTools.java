package com.flower.backend.chatbot.tool.CommunityAgent;

import com.flower.backend.chatbot.dto.ChatAction;
import com.flower.backend.chatbot.dto.ToolResult;
import com.flower.backend.chatbot.tool.ChatbotActionContext;
import com.flower.backend.community.CommunityPost;
import com.flower.backend.community.CommunityPostRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class CommunityTools {

    private static final int RESULT_LIMIT = 5;

    private final CommunityPostRepository postRepository;
    private final ChatbotActionContext actionContext;

    // KO: 커뮤니티 게시글을 검색어 기준으로 조회합니다.
    @Tool(description = "Search FLOWER community posts by keyword.")
    @Transactional(readOnly = true)
    public ToolResult searchPosts(
            // KO: 커뮤니티 게시글 검색어입니다.
            @ToolParam(description = "Search keyword for community posts.") String query
    ) {
        actionContext.markSearchInvoked();
        actionContext.incrementToolCount("community.searchPosts");

        String sanitized = sanitizeKeyword(query, 100);

        try {
            List<CommunityPost> results = sanitized.isBlank()
                    ? postRepository.findFeed(PageRequest.of(0, RESULT_LIMIT))
                    : postRepository.searchByKeyword(sanitized, PageRequest.of(0, RESULT_LIMIT));

            return ToolResult.builder()
                    .tool("community.searchPosts")
                    .status("SUCCESS")
                    .summary("'" + displayKeyword(sanitized) + "' 커뮤니티 검색 결과 "
                            + results.size() + "건을 찾았습니다.")
                    .data(Map.of("items", toItems(results)))
                    .build();
        } catch (Exception e) {
            log.error("[Tool:searchPosts] 커뮤니티 게시글 검색 실패", e);
            return ToolResult.builder()
                    .tool("community.searchPosts")
                    .status("ERROR")
                    .summary("커뮤니티 게시글 검색에 실패했습니다.")
                    .error("게시글 검색 중 오류가 발생했습니다.")
                    .build();
        }
    }

    // KO: 검색어 조건에 맞는 최신 커뮤니티 게시글을 전체 기간 기준으로 조회합니다.
    @Tool(description = "Get latest FLOWER community posts, optionally filtered by keyword.")
    @Transactional(readOnly = true)
    public ToolResult getLatestPosts(
            // KO: 선택 검색어입니다. 전체 최신글 조회는 빈 문자열을 전달합니다.
            @ToolParam(description = "Optional keyword for latest community posts.") String query,
            // KO: 호환성 유지용 입력입니다. 커뮤니티 최신글은 기간 필터를 적용하지 않습니다.
            @ToolParam(description = "Ignored. Community latest posts are not filtered by period.") String dateFilter,
            // KO: 호환성 유지용 입력입니다. 커뮤니티 최신글은 월 필터를 적용하지 않습니다.
            @ToolParam(description = "Ignored. Community latest posts are not filtered by month.") int month,
            // KO: 호환성 유지용 입력입니다. 커뮤니티 최신글은 연도 필터를 적용하지 않습니다.
            @ToolParam(description = "Ignored. Community latest posts are not filtered by year.") int year
    ) {
        actionContext.incrementToolCount("community.getLatestPosts");
        return readPosts("community.getLatestPosts", "최신", query);
    }

    // KO: 검색어 조건에 맞는 인기 커뮤니티 게시글을 전체 기간 기준으로 조회합니다.
    @Tool(description = "Get popular FLOWER community posts by likes.")
    @Transactional(readOnly = true)
    public ToolResult getPopularPosts(
            // KO: 선택 검색어입니다. 전체 인기글 조회는 빈 문자열을 전달합니다.
            @ToolParam(description = "Optional keyword for popular community posts.") String query,
            // KO: 호환성 유지용 입력입니다. 커뮤니티 인기글은 기간 필터를 적용하지 않습니다.
            @ToolParam(description = "Ignored. Community popular posts are not filtered by period.") String dateFilter,
            // KO: 호환성 유지용 입력입니다. 커뮤니티 인기글은 월 필터를 적용하지 않습니다.
            @ToolParam(description = "Ignored. Community popular posts are not filtered by month.") int month,
            // KO: 호환성 유지용 입력입니다. 커뮤니티 인기글은 연도 필터를 적용하지 않습니다.
            @ToolParam(description = "Ignored. Community popular posts are not filtered by year.") int year
    ) {
        actionContext.incrementToolCount("community.getPopularPosts");
        return readPosts("community.getPopularPosts", "인기", query);
    }

    // KO: 커뮤니티 화면을 여는 앱 내부 액션을 준비합니다.
    @Tool(description = "Prepare an internal client follow-up that opens the community screen.")
    public ChatAction openCommunity(
            // KO: 커뮤니티 화면에 전달할 선택 검색어입니다.
            @ToolParam(description = "Optional keyword for the community screen.") String keyword
    ) {
        actionContext.incrementToolCount("community.openCommunity");

        String sanitized = sanitizeKeyword(keyword, 80);
        Map<String, Object> params = new LinkedHashMap<>();
        if (!sanitized.isBlank()) {
            params.put("query", sanitized);
        }

        ChatAction action = ChatAction.builder()
                .type("NAVIGATE")
                .target("COMMUNITY")
                .params(params.isEmpty() ? null : params)
                .build();
        actionContext.addAction(action);

        return action;
    }

    // KO: 게시글을 생성하지 않고 커뮤니티 글 작성 화면을 여는 앱 내부 액션만 준비합니다.
    @Tool(description = "Prepare an internal client follow-up that opens the community post composer.")
    public ChatAction openPostComposer(
            // KO: 현재 v1에서는 작성 화면에 자동 입력하지 않는 선택 주제입니다.
            @ToolParam(description = "Optional topic; v1 opens the composer without generating content.") String topic
    ) {
        actionContext.incrementToolCount("community.openPostComposer");

        ChatAction action = ChatAction.builder()
                .type("NAVIGATE")
                .target("COMMUNITY_COMPOSE")
                .params(null)
                .build();
        actionContext.addAction(action);

        return action;
    }

    private List<Map<String, Object>> toItems(List<CommunityPost> posts) {
        return posts.stream()
                .map(post -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", post.getId());
                    item.put("nickname", post.getUser() != null
                            ? nullToDash(post.getUser().getNickname()) : "-");
                    item.put("content", nullToDash(post.getContent()));
                    item.put("species", nullToDash(post.getFlowerSpecies()));
                    item.put("plantName", nullToDash(post.getPlantName()));
                    item.put("address", nullToDash(post.getAddress()));
                    item.put("likes", post.getLikeCount());
                    item.put("comments", post.getCommentCount());
                    item.put("createdAt", post.getCreatedAt() == null ? "" : post.getCreatedAt().toString());
                    return item;
                })
                .toList();
    }

    private ToolResult readPosts(
            String toolName,
            String label,
            String query
    ) {
        String sanitized = sanitizeKeyword(query, 100);

        try {
            List<CommunityPost> results = queryPosts(toolName, sanitized);
            return successResult(toolName, label, sanitized, results);
        } catch (Exception queryFailure) {
            String failureStage = "community.getPopularPosts".equals(toolName)
                    ? "popular_query"
                    : "latest_query";
            log.error("[Tool:{}] 커뮤니티 {}글 조회 실패", toolName, label, queryFailure);
            return errorResult(toolName, label, sanitized, failureStage, queryFailure);
        }
    }

    private List<CommunityPost> queryPosts(String toolName, String sanitized) {
        boolean popular = "community.getPopularPosts".equals(toolName);
        PageRequest page = PageRequest.of(0, RESULT_LIMIT);
        if (popular) {
            return sanitized.isBlank()
                    ? postRepository.findPopularPosts(page)
                    : postRepository.findPopularPostsByKeyword(sanitized, page);
        }
        return sanitized.isBlank()
                ? postRepository.findLatestPosts(page)
                : postRepository.findLatestPostsByKeyword(sanitized, page);
    }

    private ToolResult successResult(
            String toolName,
            String label,
            String sanitized,
            List<CommunityPost> results
    ) {
        try {
            List<Map<String, Object>> items = toItems(results);
            return ToolResult.builder()
                    .tool(toolName)
                    .status("SUCCESS")
                    .summary("'" + displayKeyword(sanitized) + "' 전체 기간 " + label
                            + " 커뮤니티 글 " + items.size() + "건을 찾았습니다.")
                    .data(diagnosticData(toolName, sanitized, items))
                    .build();
        } catch (Exception itemFailure) {
            log.error("[Tool:{}] 커뮤니티 {}글 결과 변환 실패", toolName, label, itemFailure);
            return errorResult(toolName, label, sanitized, "to_items", itemFailure);
        }
    }

    private Map<String, Object> diagnosticData(
            String toolName,
            String sanitized,
            List<Map<String, Object>> items
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("keyword", sanitized);
        data.put("rankingBasis", "community.getPopularPosts".equals(toolName)
                ? "likeCount DESC, id DESC"
                : "createdAt DESC");
        return data;
    }

    private ToolResult errorResult(
            String toolName,
            String label,
            String sanitized,
            String failureStage,
            Exception failure
    ) {
        Map<String, Object> data = diagnosticData(toolName, sanitized, List.of());
        data.put("failureStage", failureStage);
        data.put("failureReason", failure.getClass().getSimpleName());
        return ToolResult.builder()
                .tool(toolName)
                .status("ERROR")
                .summary("커뮤니티 " + label + "글 조회에 실패했습니다.")
                .data(data)
                .error(label + "글 조회 중 오류가 발생했습니다.")
                .build();
    }

    private String sanitizeKeyword(String keyword, int maxLength) {
        if (keyword == null || keyword.isBlank()) {
            return "";
        }
        String sanitized = keyword.trim();
        if (sanitized.length() > maxLength) {
            sanitized = sanitized.substring(0, maxLength);
            log.warn("[Tool:community] 검색어를 {}자로 잘랐습니다.", maxLength);
        }
        return sanitized;
    }

    private String displayKeyword(String keyword) {
        return keyword == null || keyword.isBlank() ? "전체" : keyword;
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
