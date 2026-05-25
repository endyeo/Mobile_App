package com.flower.backend.walk;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "walk_records",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "record_date"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WalkRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "record_date", nullable = false)
    private LocalDate recordDate;

    @Column(name = "step_count", nullable = false)
    private int stepCount;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static WalkRecord create(Long userId, LocalDate date, int stepCount) {
        WalkRecord r = new WalkRecord();
        r.userId = userId;
        r.recordDate = date;
        r.stepCount = Math.max(0, stepCount);
        r.updatedAt = LocalDateTime.now();
        return r;
    }

    public void updateStepCount(int stepCount) {
        if (stepCount < this.stepCount) return;
        this.stepCount = stepCount;
        this.updatedAt = LocalDateTime.now();
    }
}
