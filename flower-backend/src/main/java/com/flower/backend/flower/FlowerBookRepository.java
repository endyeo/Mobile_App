package com.flower.backend.flower;

import org.springframework.data.jpa.repository.JpaRepository;
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

    List<FlowerBook> findByBloomMonthOrderByBloomDay(Integer bloomMonth);

    Optional<FlowerBook> findByScientificNameIgnoreCase(String scientificName);

    @Query("SELECT f FROM FlowerBook f WHERE f.scientificName LIKE :genus%")
    List<FlowerBook> findByGenus(@Param("genus") String genus);
}
