package com.flower.backend.flower;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Entity
@Table(name = "flower_book")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlowerBook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, length = 20)
    private String dataNo;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 200)
    private String scientificName;

    private Integer bloomMonth;
    private Integer bloomDay;

    @Column(columnDefinition = "TEXT")
    private String flowerLanguage;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String growTips;

    @Column(length = 1024)
    private String imageUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private FlowerCategory category;

    // 데이터 출처: NONGSARO | WIKIPEDIA | MANUAL
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String source = "NONGSARO";

    // 데이터 완성도: COMPLETE | AUTO | PENDING
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "COMPLETE";

    public void updateStatus(String status) {
        this.status = status;
    }

    public void updateImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }
}
