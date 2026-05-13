package com.flower.backend.flower;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FlowerCategoryRepository extends JpaRepository<FlowerCategory, Long> {
    Optional<FlowerCategory> findByName(String name);
}
