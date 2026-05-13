package com.flower.backend.community;

import com.flower.backend.auth.User;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "community_posts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
public class CommunityPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String content;

    @Column(name = "flower_species")
    private String flowerSpecies;

    @Column(name = "image_url", length = 1024)
    private String imageUrl;

    private Double latitude;
    private Double longitude;
    private String address;

    @Column(name = "like_count", nullable = false)
    private int likeCount = 0;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public CommunityPost(User user, String content, String flowerSpecies,
                         String imageUrl, Double latitude, Double longitude, String address) {
        this.user = user;
        this.content = content;
        this.flowerSpecies = flowerSpecies;
        this.imageUrl = imageUrl;
        this.latitude = latitude;
        this.longitude = longitude;
        this.address = address;
    }

    public void increaseLikeCount() { this.likeCount++; }
    public void decreaseLikeCount() { if (this.likeCount > 0) this.likeCount--; }
}
