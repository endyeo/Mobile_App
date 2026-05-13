package com.flower.backend.chatbot.tool.CommunityAgent;

import com.flower.backend.chatbot.dto.ChatAction;
import com.flower.backend.chatbot.dto.ToolResult;
import com.flower.backend.chatbot.tool.ChatbotActionContext;
import com.flower.backend.community.entity.Post;
import com.flower.backend.community.repository.PostRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CommunityTools {

    private final PostRepository postRepository;
    private final ChatbotActionContext actionContext;

    // KO: 커뮤니티 게시글을 검색어 기준으로 조회합니다.
    @Tool(description = "Search FLOWER community posts by keyword.")
    public ToolResult searchPosts(
            // KO: 커뮤니티 게시글 검색어입니다.
            @ToolParam(description = "Search keyword for community posts.") String query
    ) {
        actionContext.markSearchInvoked();
        actionContext.incrementToolCount("community.searchPosts");

        String sanitized = sanitizeKeyword(query, 100);

        try {
            List<Post> results = sanitized.isBlank()
                    ? postRepository.findAll().stream().limit(5).toList()
                    : postRepository.searchByKeyword(sanitized).stream().limit(5).toList();

            return ToolResult.builder()
                    .tool("community.searchPosts")
                    .status("SUCCESS")
                    .summary("'" + displayKeyword(sanitized) + "' community search returned "
                            + results.size() + " result(s).")
                    .data(Map.of("items", toItems(results)))
                    .build();
        } catch (Exception e) {
            log.error("[Tool:searchPosts] search failed", e);
            return ToolResult.builder()
                    .tool("community.searchPosts")
                    .status("ERROR")
                    .summary("Community post search failed.")
                    .error("게시글 검색 중 오류가 발생했습니다.")
                    .build();
        }
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

    // KO: 게시글을 저장하지 않고 커뮤니티 글 작성 초안 액션만 준비합니다.
    @Tool(description = "Prepare a draft-only community post action without saving a post.")
    public ChatAction prepareDraft(
            // KO: 커뮤니티 글 초안에 사용할 선택 주제입니다.
            @ToolParam(description = "Optional topic for the community draft.") String topic
    ) {
        actionContext.incrementToolCount("community.prepareDraft");

        String sanitized = sanitizeKeyword(topic, 80);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("mode", "DRAFT_ONLY");
        if (!sanitized.isBlank()) {
            params.put("topic", sanitized);
        }

        ChatAction action = ChatAction.builder()
                .type("PREPARE_DRAFT")
                .target("COMMUNITY")
                .params(params)
                .build();
        actionContext.addAction(action);

        return action;
    }

    private List<Map<String, Object>> toItems(List<Post> posts) {
        return posts.stream()
                .map(post -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("nickname", nullToDash(post.getNickname()));
                    item.put("content", nullToDash(post.getContent()));
                    item.put("species", nullToDash(post.getSpecies()));
                    item.put("likes", post.getLikesCount());
                    return item;
                })
                .toList();
    }

    private String sanitizeKeyword(String keyword, int maxLength) {
        if (keyword == null || keyword.isBlank()) {
            return "";
        }
        String sanitized = keyword.trim();
        if (sanitized.length() > maxLength) {
            sanitized = sanitized.substring(0, maxLength);
            log.warn("[Tool:community] keyword truncated to {} characters", maxLength);
        }
        return sanitized;
    }

    private String displayKeyword(String keyword) {
        return keyword == null || keyword.isBlank() ? "all" : keyword;
    }

    private String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
