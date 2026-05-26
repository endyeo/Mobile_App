package com.flower.backend.chatbot.tool.FestivalAgent;

import com.flower.backend.chatbot.dto.ToolResult;
import com.flower.backend.festival.Festival;
import com.flower.backend.festival.FestivalRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    void dateRangeRequiresBothStartAndEndDate() {
        FestivalToolService.DateRange range = FestivalToolService.resolveDateRange("upcoming", today);

        assertThat(FestivalToolService.matchesFestivalDateRange("20260601", "", range, today))
                .isFalse();
        assertThat(FestivalToolService.matchesFestivalDateRange("", "20260610", range, today))
                .isFalse();
    }

    @Test
    void chatbotFestivalToolUsesDbRepositoryBeforeTourApi() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        FestivalRepository festivalRepository = mock(FestivalRepository.class);
        FestivalToolService service = new FestivalToolService(restTemplate, festivalRepository);
        when(festivalRepository.searchChatbotCandidates(anyString(), any(), eq("수국"), any(Pageable.class)))
                .thenReturn(List.of(
                        festival("1", "수국 축제", "20991201", "20991210", "서울", "중구"),
                        festival("missing-end", "수국 기간 미정", "20991201", "", "서울", "중구")
                ));

        ToolResult result = service.searchFlowerFestivalsResult("수국", null, false, "upcoming");
        Map<String, Object> data = result.getData();

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertFestivalDbContract(data);
        assertThat(data).containsEntry("query", "수국");
        assertThat(items(data)).hasSize(1);
        assertThat(items(data).get(0)).containsEntry("title", "수국 축제");
        assertOnlyDatedFestivalItems(data);
        verify(festivalRepository).searchChatbotCandidates(anyString(), any(), eq("수국"), any(Pageable.class));
        verifyNoInteractions(restTemplate);
    }

    @Test
    void genericFestivalKeywordDoesNotOverFilterDbCandidates() {
        FestivalRepository festivalRepository = mock(FestivalRepository.class);
        FestivalToolService service = new FestivalToolService(mock(RestTemplate.class), festivalRepository);
        when(festivalRepository.searchChatbotCandidates(anyString(), any(), eq(""), any(Pageable.class)))
                .thenReturn(List.of(festival("rose", "장미축제", "20991201", "20991210", "서울", "중구")));

        ToolResult result = service.searchFlowerFestivalsResult("꽃", null, false, "upcoming");

        assertThat(items(result.getData())).hasSize(1);
        assertThat(items(result.getData()).get(0)).containsEntry("title", "장미축제");
        verify(festivalRepository).searchChatbotCandidates(anyString(), any(), eq(""), any(Pageable.class));
    }

    @Test
    void genericFlowerFestivalKeywordDoesNotOverFilterDbCandidates() {
        FestivalRepository festivalRepository = mock(FestivalRepository.class);
        FestivalToolService service = new FestivalToolService(mock(RestTemplate.class), festivalRepository);
        when(festivalRepository.searchChatbotCandidates(anyString(), any(), eq(""), any(Pageable.class)))
                .thenReturn(List.of(festival("rose", "장미축제", "20991201", "20991210", "서울", "중구")));

        ToolResult result = service.searchFlowerFestivalsResult("꽃 축제", null, false, "upcoming");

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(items(result.getData())).hasSize(1);
        verify(festivalRepository).searchChatbotCandidates(anyString(), any(), eq(""), any(Pageable.class));
    }

    @Test
    void specificFestivalKeywordFiltersDbCandidates() {
        FestivalRepository festivalRepository = mock(FestivalRepository.class);
        FestivalToolService service = new FestivalToolService(mock(RestTemplate.class), festivalRepository);
        when(festivalRepository.searchChatbotCandidates(anyString(), any(), eq("벚꽃"), any(Pageable.class)))
                .thenReturn(List.of(festival("cherry", "벚꽃축제", "20991201", "20991210", "서울", "중구")));

        ToolResult result = service.searchFlowerFestivalsResult("벚꽃", null, false, "upcoming");

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(items(result.getData())).hasSize(1);
        assertThat(items(result.getData()).get(0)).containsEntry("title", "벚꽃축제");
        verify(festivalRepository).searchChatbotCandidates(anyString(), any(), eq("벚꽃"), any(Pageable.class));
    }

    @Test
    void emptyDbResultIsSuccessfulNoDataResult() {
        FestivalRepository festivalRepository = mock(FestivalRepository.class);
        FestivalToolService service = new FestivalToolService(mock(RestTemplate.class), festivalRepository);
        when(festivalRepository.searchChatbotCandidates(anyString(), any(), anyString(), any(Pageable.class)))
                .thenReturn(List.of());

        ToolResult result = service.searchFlowerFestivalsResult("꽃", null, false, "upcoming");

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertFestivalDbContract(result.getData());
        assertThat(items(result.getData())).isEmpty();
    }

    @Test
    void dbExceptionReturnsErrorToolResult() {
        FestivalRepository festivalRepository = mock(FestivalRepository.class);
        FestivalToolService service = new FestivalToolService(mock(RestTemplate.class), festivalRepository);
        when(festivalRepository.searchChatbotCandidates(anyString(), any(), anyString(), any(Pageable.class)))
                .thenThrow(new RuntimeException("db unavailable"));

        ToolResult result = service.searchFlowerFestivalsResult("꽃", null, false, "upcoming");

        assertThat(result.getStatus()).isEqualTo("ERROR");
        assertThat(result.getError()).isEqualTo("축제 DB 조회 중 오류가 발생했습니다.");
        assertFestivalDbContract(result.getData());
        assertThat(items(result.getData())).isEmpty();
    }

    @Test
    void nearbyFestivalDbResultsAreSortedByDistanceWhenLocationExists() {
        FestivalRepository festivalRepository = mock(FestivalRepository.class);
        FestivalToolService service = new FestivalToolService(mock(RestTemplate.class), festivalRepository);
        when(festivalRepository.searchChatbotCandidates(anyString(), any(), eq(""), any(Pageable.class)))
                .thenReturn(List.of(
                        festival("far", "멀리 있는 장미축제", "20991201", "20991210", "부산", "", 129.0756, 35.1796),
                        festival("near", "가까운 장미축제", "20991201", "20991210", "서울", "", 126.9780, 37.5665)
                ));
        com.flower.backend.chatbot.dto.ChatMessageRequest.LocationContext location =
                new com.flower.backend.chatbot.dto.ChatMessageRequest.LocationContext(37.5665, 126.9780);

        ToolResult result = service.searchFlowerFestivalsResult("꽃", location, true, "upcoming");

        assertThat(items(result.getData())).extracting(item -> item.get("contentId"))
                .containsExactly("near", "far");
    }

    @Test
    void searchFestival2ResultSkipsKeywordFallback() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        FestivalToolService service = service(restTemplate);
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn(responseArray(item("1", "서울 꽃축제", "20991201", "20991210", "http://example.com/a.jpg")));

        ToolResult result = service.searchFlowerFestivalsResult("축제", null, false, "upcoming");
        Map<String, Object> data = result.getData();

        assertFestivalDbContract(data);
        assertThat(data).containsEntry("query", "축제");
        assertThat(data).containsEntry("dateFilter", "upcoming");
        assertThat(data).containsEntry("excludedPastCount", 0);
        assertThat(data).containsEntry("locationUsed", false);
        assertThat(items(data)).hasSize(1);
        assertOnlyDatedFestivalItems(data);
        assertThat(items(data).get(0)).containsEntry("source", "festival_db");
        assertThat((String) items(data).get(0).get("imageUrl")).startsWith("https://");
        verify(restTemplate, times(1)).getForObject(anyString(), eq(String.class));
    }

    @Test
    void emptySearchFestival2UsesTwoKeywordFallbacksAndDedupes() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        FestivalToolService service = service(restTemplate);
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenAnswer(invocation -> {
            String uri = invocation.getArgument(0);
            if (uri.contains("searchFestival2")) {
                return emptyResponse();
            }
            if (uri.contains("detailIntro2")) {
                return detailIntroResponse("20991201", "20991210");
            }
            return responseArray(item("same", "장미축제", "20991201", "20991210", ""));
        });

        ToolResult result = service.searchFlowerFestivalsResult("축제", null, false, "upcoming");
        Map<String, Object> data = result.getData();

        assertFestivalDbContract(data);
        assertThat(items(data)).hasSize(1);
        assertOnlyDatedFestivalItems(data);
        verify(restTemplate, times(3)).getForObject(anyString(), eq(String.class));
    }

    @Test
    void enrichesMissingDatesFromDetailIntro2BeforeReturningFestival() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        FestivalToolService service = service(restTemplate);
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenAnswer(invocation -> {
            String uri = invocation.getArgument(0);
            if (uri.contains("searchFestival2")) {
                return emptyResponse();
            }
            if (uri.contains("searchKeyword2")) {
                return responseArray(item("same", "장미축제", "", "", ""));
            }
            if (uri.contains("detailIntro2")) {
                return detailIntroResponse("20991201", "20991210");
            }
            return emptyResponse();
        });

        ToolResult result = service.searchFlowerFestivalsResult("축제", null, false, "upcoming");
        Map<String, Object> data = result.getData();

        assertFestivalDbContract(data);
        assertThat(items(data)).hasSize(1);
        assertThat(items(data).get(0)).containsEntry("eventStartDate", "20991201");
        assertThat(items(data).get(0)).containsEntry("eventEndDate", "20991210");
        assertOnlyDatedFestivalItems(data);
    }

    @Test
    void keepsFestivalExcludedWhenDetailIntro2DoesNotProvideDates() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        FestivalToolService service = service(restTemplate);
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenAnswer(invocation -> {
            String uri = invocation.getArgument(0);
            if (uri.contains("searchFestival2")) {
                return emptyResponse();
            }
            if (uri.contains("searchKeyword2")) {
                return responseArray(item("same", "장미축제", "", "", ""));
            }
            if (uri.contains("detailIntro2")) {
                return detailIntroResponse("", "");
            }
            return emptyResponse();
        });

        ToolResult result = service.searchFlowerFestivalsResult("축제", null, false, "upcoming");
        Map<String, Object> data = result.getData();

        assertFestivalDbContract(data);
        assertThat(items(data)).isEmpty();
    }

    @Test
    void keepsFestivalExcludedWhenDetailIntro2ProvidesOnlyOneDate() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        FestivalToolService service = service(restTemplate);
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenAnswer(invocation -> {
            String uri = invocation.getArgument(0);
            if (uri.contains("searchFestival2")) {
                return emptyResponse();
            }
            if (uri.contains("searchKeyword2")) {
                return responseArray(item("same", "장미축제", "", "", ""));
            }
            if (uri.contains("detailIntro2")) {
                return detailIntroResponse("20991201", "");
            }
            return emptyResponse();
        });

        ToolResult result = service.searchFlowerFestivalsResult("축제", null, false, "upcoming");
        Map<String, Object> data = result.getData();

        assertFestivalDbContract(data);
        assertThat(items(data)).isEmpty();
    }

    @Test
    void timeoutReturnsErrorWithDiagnostics() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        FestivalToolService service = service(restTemplate);
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenThrow(new ResourceAccessException("Read timed out"));

        ToolResult result = service.searchFlowerFestivalsResult("축제", null, false, "upcoming");

        assertThat(result.getStatus()).isEqualTo("ERROR");
        assertFestivalDbContract(result.getData());
        assertThat(result.getData()).containsEntry("query", "축제");
        assertThat(result.getData()).containsEntry("dateFilter", "upcoming");
        assertThat((List<?>) result.getData().get("items")).isEmpty();
    }

    @Test
    void fallbackTimeoutWithNoItemsReturnsError() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        FestivalToolService service = service(restTemplate);
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenAnswer(invocation -> {
            String uri = invocation.getArgument(0);
            if (uri.contains("searchFestival2")) {
                return emptyResponse();
            }
            throw new ResourceAccessException("Read timed out");
        });

        ToolResult result = service.searchFlowerFestivalsResult("축제", null, false, "upcoming");

        assertThat(result.getStatus()).isEqualTo("ERROR");
        assertFestivalDbContract(result.getData());
        assertThat((List<?>) result.getData().get("items")).isEmpty();
    }

    @Test
    void parsesSingleItemObjectResponse() {
        RestTemplate restTemplate = mock(RestTemplate.class);
        FestivalToolService service = service(restTemplate);
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn(responseObject(item("single", "국화축제", "20991201", "20991210", "")));

        ToolResult result = service.searchFlowerFestivalsResult("축제", null, false, "upcoming");

        assertFestivalDbContract(result.getData());
        assertThat(items(result.getData())).hasSize(1);
        assertThat(items(result.getData()).get(0).get("title")).isEqualTo("국화축제");
        assertOnlyDatedFestivalItems(result.getData());
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

        assertFestivalDbContract(data);
        assertThat(data.get("excludedPastCount")).isEqualTo(1);
        assertThat(items(data)).hasSize(1);
        assertThat(items(data).get(0).get("title")).isEqualTo("오늘 꽃축제");
        assertOnlyDatedFestivalItems(data);
    }

    private FestivalToolService service(RestTemplate restTemplate) {
        FestivalToolService service = new FestivalToolService(restTemplate);
        ReflectionTestUtils.setField(service, "tourApiKey", "test-key");
        return service;
    }

    private Festival festival(
            String id,
            String title,
            String startDate,
            String endDate,
            String addr1,
            String addr2
    ) {
        return festival(id, title, startDate, endDate, addr1, addr2, 126.9780, 37.5665);
    }

    private Festival festival(
            String id,
            String title,
            String startDate,
            String endDate,
            String addr1,
            String addr2,
            double mapX,
            double mapY
    ) {
        return Festival.builder()
                .contentId(id)
                .title(title)
                .addr1(addr1)
                .addr2(addr2)
                .mapX(mapX)
                .mapY(mapY)
                .firstImage("http://example.com/a.jpg")
                .firstImage2("")
                .tel("02-0000-0000")
                .eventStartDate(startDate)
                .eventEndDate(endDate)
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("items");
    }

    private void assertFestivalDbContract(Map<String, Object> data) {
        assertThat(data).containsKeys("source", "items", "dateFilter", "query", "excludedPastCount", "locationUsed");
        assertThat(data).containsEntry("source", "festival_db");
        assertThat(data).doesNotContainKeys(
                "primaryEndpoint",
                "fallbackEndpoint",
                "keywordFallbackUsed",
                "apiTimedOut",
                "fallbackLimited",
                "attemptedEndpoints",
                "elapsedMs",
                "detailIntroAttemptedCount",
                "detailIntroEnrichedCount",
                "detailIntroFailedCount",
                "detailIntroLimited",
                "rawFestivalCount",
                "flowerFilteredCount",
                "excludedDateCount",
                "excludedUnknownDateCount",
                "rawSamples",
                "pageSamples",
                "failureReason",
                "lat",
                "lng",
                "nearby",
                "keyword",
                "today",
                "rangeStart",
                "rangeEnd"
        );
    }

    private void assertOnlyDatedFestivalItems(Map<String, Object> data) {
        for (Map<String, Object> item : items(data)) {
            assertThat((String) item.get("eventStartDate")).isNotBlank();
            assertThat((String) item.get("eventEndDate")).isNotBlank();
            assertThat((String) item.get("period")).isNotBlank();
        }
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

    private String detailIntroResponse(String eventStartDate, String eventEndDate) {
        return """
                {"response":{"body":{"items":{"item":{"eventstartdate":"%s","eventenddate":"%s"}}}}}
                """.formatted(eventStartDate, eventEndDate);
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
