package com.seoulfit.backend.engagement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "user_places")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPlace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "place_key", nullable = false, length = 180)
    private String placeKey;

    @Column(name = "source_id", nullable = false, length = 140)
    private String sourceId;

    @Column(nullable = false, length = 50)
    private String category;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 500)
    private String address;

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    @Column(nullable = false)
    private boolean favorite;

    @Column(name = "saved_at")
    private LocalDateTime savedAt;

    @Column(name = "last_viewed_at")
    private LocalDateTime lastViewedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @SuppressWarnings("java:S107") // These fields are the persisted identity and details of one place.
    public static UserPlace create(
            Long userId,
            String placeKey,
            String sourceId,
            String category,
            String name,
            String address,
            double latitude,
            double longitude,
            LocalDateTime now) {
        UserPlace place = new UserPlace();
        place.userId = userId;
        place.placeKey = placeKey;
        place.updateDetails(sourceId, category, name, address, latitude, longitude, now);
        place.createdAt = now;
        return place;
    }

    public void updateDetails(
            String sourceId,
            String category,
            String name,
            String address,
            double latitude,
            double longitude,
            LocalDateTime now) {
        this.sourceId = sourceId;
        this.category = category;
        this.name = name;
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.updatedAt = now;
    }

    public void saveAsFavorite(LocalDateTime now) {
        this.favorite = true;
        this.savedAt = now;
        this.updatedAt = now;
    }

    public void removeFavorite(LocalDateTime now) {
        this.favorite = false;
        this.savedAt = null;
        this.updatedAt = now;
    }

    public void markViewed(LocalDateTime now) {
        this.lastViewedAt = now;
        this.updatedAt = now;
    }

    public boolean belongsTo(Long candidateUserId) {
        return userId.equals(candidateUserId);
    }
}
