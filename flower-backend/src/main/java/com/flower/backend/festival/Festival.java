package com.flower.backend.festival;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * TourAPI에서 캐싱한 꽃 축제 정보.
 * FestivalCacheService가 주 1회 갱신.
 */
@Entity
@Table(name = "festivals", indexes = {
        @Index(name = "idx_festivals_event_dates", columnList = "event_start_date, event_end_date"),
        @Index(name = "idx_festivals_content_id", columnList = "content_id", unique = true)
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class Festival {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** TourAPI의 contentid (외부 식별자, unique) */
    @Column(name = "content_id", nullable = false, length = 32)
    private String contentId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 200)
    private String addr1;

    @Column(length = 200)
    private String addr2;

    /** TourAPI mapy (위도) */
    @Column(name = "map_y")
    private Double mapY;

    /** TourAPI mapx (경도) */
    @Column(name = "map_x")
    private Double mapX;

    @Column(name = "first_image", length = 500)
    private String firstImage;

    @Column(name = "first_image2", length = 500)
    private String firstImage2;

    @Column(length = 50)
    private String tel;

    /** YYYYMMDD 문자열 */
    @Column(name = "event_start_date", length = 8)
    private String eventStartDate;

    /** YYYYMMDD 문자열 */
    @Column(name = "event_end_date", length = 8)
    private String eventEndDate;

    /** 마지막 캐시 갱신 시각 */
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** YYYYMMDD → LocalDate 변환 헬퍼 */
    public LocalDate parsedEndDate() {
        return parseYyyymmdd(eventEndDate);
    }

    public LocalDate parsedStartDate() {
        return parseYyyymmdd(eventStartDate);
    }

    private static LocalDate parseYyyymmdd(String s) {
        if (s == null || s.length() != 8) return null;
        try {
            int y = Integer.parseInt(s.substring(0, 4));
            int m = Integer.parseInt(s.substring(4, 6));
            int d = Integer.parseInt(s.substring(6, 8));
            return LocalDate.of(y, m, d);
        } catch (Exception e) {
            return null;
        }
    }

    public void updateFrom(Festival fresh) {
        this.title = fresh.title;
        this.addr1 = fresh.addr1;
        this.addr2 = fresh.addr2;
        this.mapX = fresh.mapX;
        this.mapY = fresh.mapY;
        this.firstImage = fresh.firstImage;
        this.firstImage2 = fresh.firstImage2;
        this.tel = fresh.tel;
        this.eventStartDate = fresh.eventStartDate;
        this.eventEndDate = fresh.eventEndDate;
    }
}
