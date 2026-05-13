package com.flower.backend.flower.repository;

import com.flower.backend.flower.Flower;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FlowerRepository extends JpaRepository<Flower, Long> {

    @Query("SELECT f FROM Flower f WHERE f.approvalStatus = 'APPROVED'")
    List<Flower> findApproved();

    @Query("""
        SELECT f FROM Flower f
        WHERE f.approvalStatus = 'APPROVED'
          AND (f.name LIKE %:keyword% OR f.species LIKE %:keyword% OR f.address LIKE %:keyword%)
    """)
    List<Flower> searchApprovedByKeyword(@Param("keyword") String keyword);
}
