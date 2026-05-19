package com.flower.backend.chatbot.tool.FestivalAgent;

import com.flower.backend.chatbot.dto.ToolResult;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FestivalToolServiceTest {

    private final LocalDate today = LocalDate.of(2026, 5, 19);
    private final DateTimeFormatter tourDate = DateTimeFormatter.BASIC_ISO_DATE;

    @Test
    void thisWeekRangeUsesKoreanWeekMondayToSunday() {
        FestivalToolService.DateRange range = FestivalToolService.resolveDateRange("this_week", today);

        assertThat(range.start()).isEqualTo(LocalDate.of(2026, 5, 18));
        assertThat(range.end()).isEqualTo(LocalDate.of(2026, 5, 24));
    }

    @Test
    void thisMonthRangeUsesCurrentMonthStartAndEnd() {
        FestivalToolService.DateRange range = FestivalToolService.resolveDateRange("this_month", today);

        assertThat(range.start()).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(range.end()).isEqualTo(LocalDate.of(2026, 5, 31));
    }

    @Test
    void unknownDateFilterFallsBackToUpcoming() {
        FestivalToolService.DateRange range = FestivalToolService.resolveDateRange("none", today);

        assertThat(range.filter()).isEqualTo("upcoming");
        assertThat(range.start()).isEqualTo(today);
        assertThat(range.end()).isNull();
    }

    @Test
    void dateRangeExcludesFestivalEndedYesterday() {
        FestivalToolService.DateRange range = FestivalToolService.resolveDateRange("upcoming", today);

        assertThat(FestivalToolService.matchesFestivalDateRange("20260501", "20260518", range, today))
                .isFalse();
    }

    @Test
    void dateRangeIncludesFestivalEndingToday() {
        FestivalToolService.DateRange range = FestivalToolService.resolveDateRange("upcoming", today);

        assertThat(FestivalToolService.matchesFestivalDateRange("20260501", "20260519", range, today))
                .isTrue();
    }

    @Test
    void thisMonthIncludesNotYetEndedOverlappingFestival() {
        FestivalToolService.DateRange range = FestivalToolService.resolveDateRange("this_month", today);

        assertThat(FestivalToolService.matchesFestivalDateRange("20260520", "20260602", range, today))
                .isTrue();
    }

    @Test
    void thisMonthExcludesNextMonthFestival() {
        FestivalToolService.DateRange range = FestivalToolService.resolveDateRange("this_month", today);

        assertThat(FestivalToolService.matchesFestivalDateRange("20260601", "20260610", range, today))
                .isFalse();
    }

    @Test
    void searchFestival2ResultSkipsKeywordFallback() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        FestivalToolService service = service(restTemplate);
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn(responseArray(item("1", "서울 꽃축제", "20991201", "20991210", "http://example.com/a.jpg")));

        ToolResult result = service.searchFlowerFestivalsResult("축제", null, false, "upcoming");
        Map<String, Object> data = result.getData();

        assertThat(data.get("keywordFallbackUsed")).isEqualTo(false);
        assertThat(data.get("primaryEndpoint")).isEqualTo("searchFestival2");
        assertThat(data.get("fallbackEndpoint")).isEqualTo("searchKeyword2");
        assertThat(data.get("rawFestivalCount")).isEqualTo(1);
        assertThat(data.get("flowerFilteredCount")).isEqualTo(1);
        assertThat(items(data)).hasSize(1);
        assertThat((String) items(data).get(0).get("imageUrl")).startsWith("https://");
        verify(restTemplate, times(1)).getForObject(anyString(), eq(String.class));
    }

    @Test
    void emptySearchFestival2UsesSixKeywordFallbacksAndDedupes() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        FestivalToolService service = service(restTemplate);
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenAnswer(invocation -> {
            String uri = invocation.getArgument(0);
            if (uri.contains("searchFestival2")) {
                return emptyResponse();
            }
            return responseArray(item("same", "장미축제", "20991201", "20991210", ""));
        });

        ToolResult result = service.searchFlowerFestivalsResult("축제", null, false, "upcoming");
        Map<String, Object> data = result.getData();

        assertThat(data.get("keywordFallbackUsed")).isEqualTo(true);
        assertThat(data.get("rawFestivalCount")).isEqualTo(1);
        assertThat(data.get("flowerFilteredCount")).isEqualTo(1);
        assertThat(items(data)).hasSize(1);
        verify(restTemplate, times(7)).getForObject(anyString(), eq(String.class));
    }

    @Test
    void parsesSingleItemObjectResponse() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        FestivalToolService service = service(restTemplate);
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn(responseObject(item("single", "국화축제", "20991201", "20991210", "")));

        ToolResult result = service.searchFlowerFestivalsResult("축제", null, false, "upcoming");

        assertThat(items(result.getData())).hasSize(1);
        assertThat(items(result.getData()).get(0).get("title")).isEqualTo("국화축제");
    }

    @Test
    void excludesPastFestivalAndIncludesFestivalEndingToday() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        FestivalToolService service = service(restTemplate);
        LocalDate serviceToday = LocalDate.now(ZoneId.of("Asia/Seoul"));
        String yesterday = serviceToday.minusDays(1).format(tourDate);
        String todayText = serviceToday.format(tourDate);
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn(responseArray(
                        item("past", "지난 꽃축제", serviceToday.minusDays(10).format(tourDate), yesterday, ""),
                        item("today", "오늘 꽃축제", serviceToday.minusDays(1).format(tourDate), todayText, "")
                ));

        ToolResult result = service.searchFlowerFestivalsResult("축제", null, false, "upcoming");
        Map<String, Object> data = result.getData();

        assertThat(data.get("excludedPastCount")).isEqualTo(1);
        assertThat(items(data)).hasSize(1);
        assertThat(items(data).get(0).get("title")).isEqualTo("오늘 꽃축제");
    }

    private FestivalToolService service(RestTemplate restTemplate) {
        FestivalToolService service = new FestivalToolService(restTemplate);
        ReflectionTestUtils.setField(service, "tourApiKey", "test-key");
        return service;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("items");
    }

    private String emptyResponse() {
        return """
                {"response":{"body":{"items":""}}}
                """;
    }

    private String responseArray(String... items) {
        return """
                {"response":{"body":{"items":{"item":[%s]}}}}
                """.formatted(String.join(",", items));
    }

    private String responseObject(String item) {
        return """
                {"response":{"body":{"items":{"item":%s}}}}
                """.formatted(item);
    }

    private String item(String id, String title, String startDate, String endDate, String imageUrl) {
        return """
                {
                  "contentid":"%s",
                  "title":"%s",
                  "addr1":"서울",
                  "addr2":"중구",
                  "mapx":"126.9780",
                  "mapy":"37.5665",
                  "firstimage":"%s",
                  "firstimage2":"",
                  "tel":"02-0000-0000",
                  "eventstartdate":"%s",
                  "eventenddate":"%s"
                }
                """.formatted(id, title, imageUrl, startDate, endDate);
    }
}
