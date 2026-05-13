package com.flower.backend.flower;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface FlowerSpeciesMappingRepository extends JpaRepository<FlowerSpeciesMapping, Long> {

    Optional<FlowerSpeciesMapping> findByAiNameIgnoreCase(String aiName);

    // 속명(첫 단어)으로 검색
    @Query("SELECT m FROM FlowerSpeciesMapping m WHERE :aiName LIKE CONCAT(m.aiName, '%')")
    Optional<FlowerSpeciesMapping> findByGenusMatch(@Param("aiName") String aiName);
}
