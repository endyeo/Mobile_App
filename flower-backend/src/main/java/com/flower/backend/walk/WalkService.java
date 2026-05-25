package com.flower.backend.walk;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WalkService {

    private final WalkRecordRepository repository;

    private static final int WEEKLY_DAYS = 7;

    @Transactional(readOnly = true)
    public List<WalkDto.DailyRecord> getWeeklyRecords(Long userId) {
        LocalDate from = LocalDate.now().minusDays(WEEKLY_DAYS - 1L);
        return repository
                .findByUserIdAndRecordDateGreaterThanEqualOrderByRecordDateAsc(userId, from)
                .stream()
                .map(WalkDto.DailyRecord::from)
                .toList();
    }

    @Transactional
    public WalkDto.SyncResponse syncTodaySteps(Long userId, int stepCount) {
        int safeSteps = Math.max(0, stepCount);
        LocalDate today = LocalDate.now();

        WalkRecord record = repository.findByUserIdAndRecordDate(userId, today)
                .orElseGet(() -> WalkRecord.create(userId, today, safeSteps));

        if (record.getId() != null) {
            record.updateStepCount(safeSteps);
        }

        WalkRecord saved = repository.save(record);
        return new WalkDto.SyncResponse(saved.getRecordDate(), saved.getStepCount());
    }
}
