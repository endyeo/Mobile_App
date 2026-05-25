package com.flower.backend.walk;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WalkRecordRepository extends JpaRepository<WalkRecord, Long> {

    Optional<WalkRecord> findByUserIdAndRecordDate(Long userId, LocalDate recordDate);

    List<WalkRecord> findByUserIdAndRecordDateGreaterThanEqualOrderByRecordDateAsc(
            Long userId, LocalDate fromDate);
}
