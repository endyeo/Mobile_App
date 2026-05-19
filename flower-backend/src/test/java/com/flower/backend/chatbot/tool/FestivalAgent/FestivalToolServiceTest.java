package com.flower.backend.chatbot.tool.FestivalAgent;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FestivalToolServiceTest {

    private final LocalDate today = LocalDate.of(2026, 5, 19);

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
}
