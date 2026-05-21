package com.flower.backend.flower;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface FlowerCategoryRepository extends JpaRepository<FlowerCategory, Long> {
    Optional<FlowerCategory> findByName(String name);

    @Query("""
        SELECT c.id AS id, c.name AS name, c.emoji AS emoji, COUNT(f.id) AS flowerCount
        FROM FlowerCategory c LEFT JOIN FlowerBook f ON f.category.id = c.id
        GROUP BY c.id, c.name, c.emoji
        ORDER BY c.id
    """)
    List<CategoryWithCountView> findAllWithFlowerCount();

    interface CategoryWithCountView {
        Long getId();
        String getName();
        String getEmoji();
        long getFlowerCount();
    }
}
