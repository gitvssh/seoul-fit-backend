package com.seoulfit.backend.engagement.adapter.in.web;

import com.seoulfit.backend.engagement.domain.AlertRuleType;
import com.seoulfit.backend.engagement.domain.AlertSubscription;
import com.seoulfit.backend.engagement.domain.SavedZone;
import com.seoulfit.backend.engagement.domain.UserPlace;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;

public final class EngagementDtos {
    private EngagementDtos() {}

    public record PlaceRequest(
            @NotBlank
            @Pattern(regexp = "[A-Za-z0-9:_-]{1,180}")
            String placeKey,
            @NotBlank @Size(max = 140) String sourceId,
            @NotBlank @Pattern(regexp = "[a-z_]{2,50}") String category,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 500) String address,
            @NotNull @DecimalMin("37.3") @DecimalMax("37.8") Double latitude,
            @NotNull @DecimalMin("126.6") @DecimalMax("127.3") Double longitude) {}

    public record PlaceResponse(
            Long id,
            String placeKey,
            String sourceId,
            String category,
            String name,
            String address,
            double latitude,
            double longitude,
            boolean favorite,
            LocalDateTime savedAt,
            LocalDateTime lastViewedAt) {
        public static PlaceResponse from(UserPlace place) {
            return new PlaceResponse(
                    place.getId(),
                    place.getPlaceKey(),
                    place.getSourceId(),
                    place.getCategory(),
                    place.getName(),
                    place.getAddress(),
                    place.getLatitude(),
                    place.getLongitude(),
                    place.isFavorite(),
                    place.getSavedAt(),
                    place.getLastViewedAt());
        }
    }

    public record ZoneRequest(
            @NotBlank @Size(max = 40) String label,
            @NotNull @DecimalMin("37.3") @DecimalMax("37.8") Double latitude,
            @NotNull @DecimalMin("126.6") @DecimalMax("127.3") Double longitude,
            @NotNull @Min(100) @Max(20000) Integer radiusMeters) {}

    public record ZoneResponse(
            Long id,
            String label,
            double latitude,
            double longitude,
            int radiusMeters,
            LocalDateTime createdAt) {
        public static ZoneResponse from(SavedZone zone) {
            return new ZoneResponse(
                    zone.getId(),
                    zone.getLabel(),
                    zone.getLatitude(),
                    zone.getLongitude(),
                    zone.getRadiusMeters(),
                    zone.getCreatedAt());
        }
    }

    public record SubscriptionRequest(
            @NotNull Long zoneId,
            @NotNull AlertRuleType alertType,
            @NotEmpty @Size(max = 7) Set<DayOfWeek> activeDays,
            LocalTime activeStart,
            LocalTime activeEnd,
            LocalTime quietStart,
            LocalTime quietEnd,
            @NotNull @Min(15) @Max(10080) Integer cooldownMinutes,
            @NotNull Boolean active) {}

    public record SubscriptionResponse(
            Long id,
            Long zoneId,
            AlertRuleType alertType,
            Set<DayOfWeek> activeDays,
            LocalTime activeStart,
            LocalTime activeEnd,
            LocalTime quietStart,
            LocalTime quietEnd,
            int cooldownMinutes,
            boolean active,
            LocalDateTime lastTriggeredAt,
            LocalDateTime createdAt) {
        public static SubscriptionResponse from(AlertSubscription subscription) {
            return new SubscriptionResponse(
                    subscription.getId(),
                    subscription.getZoneId(),
                    subscription.getAlertType(),
                    subscription.getActiveDaySet(),
                    subscription.getActiveStart(),
                    subscription.getActiveEnd(),
                    subscription.getQuietStart(),
                    subscription.getQuietEnd(),
                    subscription.getCooldownMinutes(),
                    subscription.isActive(),
                    subscription.getLastTriggeredAt(),
                    subscription.getCreatedAt());
        }
    }

    public record EvaluationResponse(int evaluated, int generated, int deferred) {}
}
