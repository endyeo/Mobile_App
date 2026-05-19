package com.flower.backend.flower;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FlowerBookRepository extends JpaRepository<FlowerBook, Long> {

    Optional<FlowerBook> findByDataNo(String dataNo);

    @Query("""
        SELECT f FROM FlowerBook f
        WHERE f.category.id = :categoryId
        ORDER BY f.bloomMonth, f.bloomDay
    """)
    List<FlowerBook> findByCategoryId(@Param("categoryId") Long categoryId);

    @Query("""
        SELECT f FROM FlowerBook f LEFT JOIN f.category c
        WHERE f.name LIKE %:keyword%
           OR c.name LIKE %:keyword%
        ORDER BY f.bloomMonth, f.bloomDay
    """)
    List<FlowerBook> searchByKeyword(@Param("keyword") String keyword);

    @Query("""
        SELECT f.id AS id,
               f.dataNo AS dataNo,
               f.name AS name,
               f.scientificName AS scientificName,
               f.description AS description,
               f.flowerLanguage AS flowerLanguage,
               f.bloomMonth AS bloomMonth,
               f.bloomDay AS bloomDay,
               f.imageUrl AS imageUrl,
               f.source AS source
        FROM FlowerBook f LEFT JOIN f.category c
        WHERE f.name LIKE %:keyword%
           OR f.scientificName LIKE %:keyword%
           OR c.name LIKE %:keyword%
        ORDER BY f.bloomMonth, f.bloomDay
    """)
    List<DescriptionSourceView> findDescriptionSourceByKeyword(
            @Param("keyword") String keyword,
            Pageable pageable
    );

    @Query("""
        SELECT f.id AS id,
               f.dataNo AS dataNo,
               f.name AS name,
               f.scientificName AS scientificName,
               f.growTips AS growTips,
               f.source AS source
        FROM FlowerBook f LEFT JOIN f.category c
        WHERE f.name LIKE %:keyword%
           OR f.scientificName LIKE %:keyword%
           OR c.name LIKE %:keyword%
        ORDER BY f.bloomMonth, f.bloomDay
    """)
    List<GrowTipsSourceView> findGrowTipsSourceByKeyword(
            @Param("keyword") String keyword,
            Pageable pageable
    );

    interface DescriptionSourceView {
        Long getId();

        String getDataNo();

        String getName();

        String getScientificName();

        String getDescription();

        String getFlowerLanguage();

        Integer getBloomMonth();

        Integer getBloomDay();

        String getImageUrl();

        String getSource();
    }

    interface GrowTipsSourceView {
        Long getId();

        String getDataNo();

        String getName();

        String getScientificName();

        String getGrowTips();

        String getSource();
    }

    List<FlowerBook> findByBloomMonthOrderByBloomDay(Integer bloomMonth);

    Optional<FlowerBook> findByScientificNameIgnoreCase(String scientificName);

    @Query("SELECT f FROM FlowerBook f WHERE f.scientificName LIKE :genus%")
    List<FlowerBook> findByGenus(@Param("genus") String genus);
}
