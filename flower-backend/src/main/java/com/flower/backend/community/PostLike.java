package com.flower.backend.community;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "post_likes")
@Getter
@NoArgsConstructor
public class PostLike {

    @EmbeddedId
    private PostLikeId id;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public PostLike(PostLikeId id) {
        this.id = id;
    }
}
