package com.flower.backend.festival;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface FestivalRepository extends JpaRepository<Festival, Long> {

    Optional<Festival> findByContentId(String contentId);

    /**
     * 종료일이 오늘 이후이거나 종료일이 null인 (진행 중 또는 미래) 축제 조회.
     * 시작일 오름차순 정렬.
     */
    @Query("""
        SELECT f FROM Festival f
        WHERE (f.eventEndDate IS NULL OR f.eventEndDate >= :todayStr)
        ORDER BY f.eventStartDate ASC NULLS LAST
    """)
    List<Festival> findOngoingOrUpcoming(@Param("todayStr") String todayStr, Pageable pageable);

    /**
     * 챗봇 축제 도구용 조회.
     * 시작일/종료일이 모두 있는 축제만 답변 후보로 사용한다.
     */
    @Query("""
        SELECT f FROM Festival f
        WHERE f.eventStartDate IS NOT NULL
          AND f.eventEndDate IS NOT NULL
          AND f.eventEndDate >= :rangeStart
          AND (:rangeEnd IS NULL OR f.eventStartDate <= :rangeEnd)
          AND (
            :keyword = ''
            OR LOWER(f.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
            OR LOWER(COALESCE(f.addr1, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
            OR LOWER(COALESCE(f.addr2, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))
          )
        ORDER BY f.eventStartDate ASC
    """)
    List<Festival> searchChatbotCandidates(
            @Param("rangeStart") String rangeStart,
            @Param("rangeEnd") String rangeEnd,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    /** 종료일 지난 캐시 정리 (선택, scheduler에서 사용) */
    @Modifying
    @Query("DELETE FROM Festival f WHERE f.eventEndDate IS NOT NULL AND f.eventEndDate < :todayStr")
    int deletePastFestivals(@Param("todayStr") String todayStr);

    /** 모든 contentId 조회 — upsert 판단용 */
    @Query("SELECT f.contentId FROM Festival f")
    List<String> findAllContentIds();

    default List<Festival> findOngoingOrUpcomingNow(Pageable pageable) {
        String today = LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        return findOngoingOrUpcoming(today, pageable);
    }
}
