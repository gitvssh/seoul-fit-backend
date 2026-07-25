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
@Table(name = "saved_zones")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SavedZone {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 40)
    private String label;

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    @Column(name = "radius_meters", nullable = false)
    private int radiusMeters;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static SavedZone create(
            Long userId,
            String label,
            double latitude,
            double longitude,
            int radiusMeters,
            LocalDateTime now) {
        SavedZone zone = new SavedZone();
        zone.userId = userId;
        zone.createdAt = now;
        zone.update(label, latitude, longitude, radiusMeters, now);
        return zone;
    }

    public void update(
            String label,
            double latitude,
            double longitude,
            int radiusMeters,
            LocalDateTime now) {
        this.label = label;
        this.latitude = latitude;
        this.longitude = longitude;
        this.radiusMeters = radiusMeters;
        this.updatedAt = now;
    }

    public boolean belongsTo(Long candidateUserId) {
        return userId.equals(candidateUserId);
    }
}
