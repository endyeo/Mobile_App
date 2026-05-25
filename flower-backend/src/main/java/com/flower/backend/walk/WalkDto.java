package com.flower.backend.walk;

import java.time.LocalDate;

public class WalkDto {

    public record DailyRecord(LocalDate recordDate, int stepCount) {
        public static DailyRecord from(WalkRecord r) {
            return new DailyRecord(r.getRecordDate(), r.getStepCount());
        }
    }

    public record SyncRequest(int stepCount) {}

    public record SyncResponse(LocalDate recordDate, int stepCount) {}
}
