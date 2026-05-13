package com.flower.backend.flower;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "flowers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Flower {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String species;

    @Column(nullable = false)
    private String address;

    @Column(nullable = false)
    private double lat;

    @Column(nullable = false)
    private double lng;

    @Column(name = "bloom_start")
    private String bloomStart;

    @Column(name = "bloom_end")
    private String bloomEnd;

    @Column(length = 1000)
    private String description;

    @Column(name = "thumbnail_url")
    private String thumbnailUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BloomStatus status = BloomStatus.BLOOMING;

    @Enumerated(EnumType.STRING)
    @Column(name = "approval_status", nullable = false)
    private ApprovalStatus approvalStatus = ApprovalStatus.APPROVED;

    @Column(name = "verification_count", nullable = false)
    private int verificationCount = 0;

    public static Flower createByQuest(String name, String species, String address, double lat, double lng) {
        Flower flower = new Flower();
        flower.name = name;
        flower.species = species;
        flower.address = address;
        flower.lat = lat;
        flower.lng = lng;
        flower.status = BloomStatus.BLOOMING;
        flower.approvalStatus = ApprovalStatus.PENDING;
        flower.verificationCount = 1;
        return flower;
    }

    public void addVerification() {
        verificationCount += 1;
        if (verificationCount >= 3) {
            approvalStatus = ApprovalStatus.APPROVED;
        }
    }

    public enum BloomStatus { BEFORE, BLOOMING, DONE }
    public enum ApprovalStatus { PENDING, APPROVED, REJECTED }
}
