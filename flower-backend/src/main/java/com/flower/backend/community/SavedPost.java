package com.flower.backend.community;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "saved_posts")
@Getter
@NoArgsConstructor
public class SavedPost {

    @EmbeddedId
    private SavedPostId id;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public SavedPost(SavedPostId id) {
        this.id = id;
    }
}
