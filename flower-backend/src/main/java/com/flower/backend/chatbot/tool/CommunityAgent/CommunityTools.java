package com.flower.backend.chatbot.tool.CommunityAgent;

import com.flower.backend.chatbot.dto.ChatAction;
import com.flower.backend.chatbot.dto.ToolResult;
import com.flower.backend.chatbot.tool.ChatbotActionContext;
import com.flower.backend.community.CommunityPost;
import com.flower.backend.community.CommunityPostRepository;
import java.util.Comparator;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");
    private static final int FALLBACK_SCAN_LIMIT = 500;
    private static final int RESULT_LIMIT = 5;
    private static final Comparator<CommunityPost> LATEST_ORDER =
            Comparator.comparing(CommunityPost::getCreatedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));
    private static final Comparator<CommunityPost> POPULAR_ORDER =
            Comparator.comparingInt(CommunityPost::getLikeCount).reversed()
                    .thenComparing(Comparator.comparingInt(CommunityPost::getCommentCount).reversed())
                    .thenComparing(LATEST_ORDER);

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

    // KO: 검색어와 기간 조건에 맞는 최신 커뮤니티 게시글을 조회합니다.
    @Tool(description = "Get latest FLOWER community posts, optionally filtered by keyword and period.")
    @Transactional(readOnly = true)
    public ToolResult getLatestPosts(
            // KO: 선택 검색어입니다. 전체 최신글 조회는 빈 문자열을 전달합니다.
            @ToolParam(description = "Optional keyword for latest community posts.") String query,
            // KO: today, this_week, this_month, month, none 중 하나입니다.
            @ToolParam(description = "Period filter: today, this_week, this_month, month, none.") String dateFilter,
            // KO: dateFilter=month일 때 사용할 월입니다. 없으면 0입니다.
            @ToolParam(description = "Month number when dateFilter is month. Use 0 if absent.") int month,
            // KO: dateFilter=month일 때 사용할 연도입니다. 없으면 0입니다.
            @ToolParam(description = "Year when dateFilter is month. Use 0 if absent.") int year
    ) {
        actionContext.incrementToolCount("community.getLatestPosts");
        return readPosts("community.getLatestPosts", "최신", query, dateFilter, month, year);
    }

    // KO: 검색어와 기간 조건에 맞는 인기 커뮤니티 게시글을 조회합니다.
    @Tool(description = "Get popular FLOWER community posts by likes, comments, and recency.")
    @Transactional(readOnly = true)
    public ToolResult getPopularPosts(
            // KO: 선택 검색어입니다. 전체 인기글 조회는 빈 문자열을 전달합니다.
            @ToolParam(description = "Optional keyword for popular community posts.") String query,
            // KO: today, this_week, this_month, month, none 중 하나입니다.
            @ToolParam(description = "Period filter: today, this_week, this_month, month, none.") String dateFilter,
            // KO: dateFilter=month일 때 사용할 월입니다. 없으면 0입니다.
            @ToolParam(description = "Month number when dateFilter is month. Use 0 if absent.") int month,
            // KO: dateFilter=month일 때 사용할 연도입니다. 없으면 0입니다.
            @ToolParam(description = "Year when dateFilter is month. Use 0 if absent.") int year
    ) {
        actionContext.incrementToolCount("community.getPopularPosts");
        return readPosts("community.getPopularPosts", "인기", query, dateFilter, month, year);
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
            String query,
            String dateFilter,
            int month,
            int year
    ) {
        String sanitized = sanitizeKeyword(query, 100);
        PeriodRange range = resolvePeriod(dateFilter, month, year);
        boolean periodFilterRequested = range.from() != null || range.to() != null;

        try {
            List<CommunityPost> results = "community.getPopularPosts".equals(toolName)
                    ? postRepository.findPopularPosts(sanitized, range.from(), range.to(), PageRequest.of(0, RESULT_LIMIT))
                    : postRepository.findLatestPosts(sanitized, range.from(), range.to(), PageRequest.of(0, RESULT_LIMIT));

            return successResult(toolName, label, sanitized, range, results, false, false);
        } catch (Exception primaryFailure) {
            log.warn("[Tool:{}] 전용 조회 실패. 안정 경로로 재시도합니다.", toolName, primaryFailure);
            boolean queryFailed = true;
            boolean periodFallbackUsed = periodFilterRequested;
            try {
                List<CommunityPost> fallbackSource = postRepository.findFeed(PageRequest.of(0, FALLBACK_SCAN_LIMIT));
                List<CommunityPost> fallbackResults = fallbackSource.stream()
                        .filter(post -> matchesKeyword(post, sanitized))
                        .filter(post -> withinRange(post, range))
                        .sorted(sortOrder(toolName))
                        .limit(RESULT_LIMIT)
                        .toList();

                return successResult(toolName, label, sanitized, range, fallbackResults, periodFallbackUsed, queryFailed);
            } catch (Exception fallbackFailure) {
                log.error("[Tool:{}] 커뮤니티 {}글 조회 실패", toolName, label, fallbackFailure);
                Map<String, Object> data = diagnosticData(
                        toolName,
                        sanitized,
                        range,
                        periodFallbackUsed,
                        queryFailed,
                        List.of());
                data.put("failureStage", "fallback_feed");
                data.put("failureReason", fallbackFailure.getClass().getSimpleName());
                return ToolResult.builder()
                        .tool(toolName)
                        .status("ERROR")
                        .summary("커뮤니티 " + label + "글 조회에 실패했습니다.")
                        .data(data)
                        .error(label + "글 조회 중 오류가 발생했습니다.")
                        .build();
            }
        }
    }

    private ToolResult successResult(
            String toolName,
            String label,
            String sanitized,
            PeriodRange range,
            List<CommunityPost> results,
            boolean periodFallbackUsed,
            boolean queryFailed
    ) {
        return ToolResult.builder()
                .tool(toolName)
                .status("SUCCESS")
                .summary("'" + displayKeyword(sanitized) + "' " + range.label() + " " + label
                        + " 커뮤니티 글 " + results.size() + "건을 찾았습니다.")
                .data(diagnosticData(toolName, sanitized, range, periodFallbackUsed, queryFailed, results))
                .build();
    }

    private Map<String, Object> diagnosticData(
            String toolName,
            String sanitized,
            PeriodRange range,
            boolean periodFallbackUsed,
            boolean queryFailed,
            List<CommunityPost> results
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", toItems(results));
        data.put("keyword", sanitized);
        data.put("dateFilter", range.dateFilter());
        data.put("rangeStart", range.from() == null ? "" : range.from().toString());
        data.put("rangeEnd", range.to() == null ? "" : range.to().toString());
        data.put("periodFallbackUsed", periodFallbackUsed);
        data.put("queryFailed", queryFailed);
        data.put("rankingBasis", "community.getPopularPosts".equals(toolName)
                ? "likeCount DESC, commentCount DESC, createdAt DESC"
                : "createdAt DESC");
        return data;
    }

    private Comparator<CommunityPost> sortOrder(String toolName) {
        return "community.getPopularPosts".equals(toolName) ? POPULAR_ORDER : LATEST_ORDER;
    }

    private boolean matchesKeyword(CommunityPost post, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        return containsIgnoreCase(post.getContent(), keyword)
                || containsIgnoreCase(post.getFlowerSpecies(), keyword)
                || containsIgnoreCase(post.getPlantName(), keyword)
                || containsIgnoreCase(post.getAddress(), keyword);
    }

    private boolean withinRange(CommunityPost post, PeriodRange range) {
        LocalDateTime createdAt = post.getCreatedAt();
        if (createdAt == null) {
            return false;
        }
        if (range.from() != null && createdAt.isBefore(range.from())) {
            return false;
        }
        if (range.to() != null && createdAt.isAfter(range.to())) {
            return false;
        }
        return true;
    }

    private boolean containsIgnoreCase(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    private PeriodRange resolvePeriod(String dateFilter, int month, int year) {
        LocalDate today = LocalDate.now(KOREA_ZONE);
        String normalized = dateFilter == null ? "" : dateFilter.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "today" -> new PeriodRange("today", "오늘",
                    today.atStartOfDay(), today.atTime(LocalTime.MAX));
            case "this_week" -> {
                LocalDate start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                LocalDate end = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
                yield new PeriodRange("this_week", "이번 주", start.atStartOfDay(), end.atTime(LocalTime.MAX));
            }
            case "this_month" -> {
                YearMonth current = YearMonth.from(today);
                yield new PeriodRange("this_month", "이번 달",
                        current.atDay(1).atStartOfDay(), current.atEndOfMonth().atTime(LocalTime.MAX));
            }
            case "month" -> {
                int safeMonth = month >= 1 && month <= 12 ? month : today.getMonthValue();
                int safeYear = year > 0 ? year : Year.now(KOREA_ZONE).getValue();
                YearMonth requested = YearMonth.of(safeYear, safeMonth);
                yield new PeriodRange("month", safeMonth + "월",
                        requested.atDay(1).atStartOfDay(), requested.atEndOfMonth().atTime(LocalTime.MAX));
            }
            default -> new PeriodRange("none", "전체 기간", null, null);
        };
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

    private record PeriodRange(
            String dateFilter,
            String label,
            LocalDateTime from,
            LocalDateTime to
    ) {
    }
}
