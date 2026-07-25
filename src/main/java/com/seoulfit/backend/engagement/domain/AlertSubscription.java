package com.seoulfit.backend.engagement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "alert_subscriptions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AlertSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "zone_id", nullable = false)
    private Long zoneId;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", nullable = false, length = 40)
    private AlertRuleType alertType;

    @Column(name = "active_days", nullable = false, length = 32)
    private String activeDays;

    @Column(name = "active_start")
    private LocalTime activeStart;

    @Column(name = "active_end")
    private LocalTime activeEnd;

    @Column(name = "quiet_start")
    private LocalTime quietStart;

    @Column(name = "quiet_end")
    private LocalTime quietEnd;

    @Column(name = "cooldown_minutes", nullable = false)
    private int cooldownMinutes;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "last_triggered_at")
    private LocalDateTime lastTriggeredAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static AlertSubscription create(
            Long userId,
            Long zoneId,
            AlertRuleType alertType,
            Set<DayOfWeek> activeDays,
            LocalTime activeStart,
            LocalTime activeEnd,
            LocalTime quietStart,
            LocalTime quietEnd,
            int cooldownMinutes,
            boolean active,
            LocalDateTime now) {
        AlertSubscription subscription = new AlertSubscription();
        subscription.userId = userId;
        subscription.zoneId = zoneId;
        subscription.alertType = alertType;
        subscription.createdAt = now;
        subscription.update(
                activeDays,
                activeStart,
                activeEnd,
                quietStart,
                quietEnd,
                cooldownMinutes,
                active,
                now);
        return subscription;
    }

    public void update(
            Set<DayOfWeek> days,
            LocalTime activeStart,
            LocalTime activeEnd,
            LocalTime quietStart,
            LocalTime quietEnd,
            int cooldownMinutes,
            boolean active,
            LocalDateTime now) {
        Set<DayOfWeek> effectiveDays =
                days == null || days.isEmpty() ? EnumSet.allOf(DayOfWeek.class) : EnumSet.copyOf(days);
        this.activeDays = effectiveDays.stream()
                .map(day -> day.name().substring(0, 3))
                .sorted()
                .collect(Collectors.joining(","));
        this.activeStart = activeStart;
        this.activeEnd = activeEnd;
        this.quietStart = quietStart;
        this.quietEnd = quietEnd;
        this.cooldownMinutes = cooldownMinutes;
        this.active = active;
        this.updatedAt = now;
    }

    public Set<DayOfWeek> getActiveDaySet() {
        if (activeDays == null || activeDays.isBlank()) {
            return EnumSet.allOf(DayOfWeek.class);
        }
        Set<String> abbreviations = Arrays.stream(activeDays.split(",")).collect(Collectors.toSet());
        return Arrays.stream(DayOfWeek.values())
                .filter(day -> abbreviations.contains(day.name().substring(0, 3)))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
    }

    public boolean canTriggerAt(LocalDateTime now) {
        if (!active || !getActiveDaySet().contains(now.getDayOfWeek())) return false;
        if (!isWithinWindow(now.toLocalTime(), activeStart, activeEnd, true)) return false;
        if (isWithinWindow(now.toLocalTime(), quietStart, quietEnd, false)) return false;
        return lastTriggeredAt == null || !now.isBefore(lastTriggeredAt.plusMinutes(cooldownMinutes));
    }

    private boolean isWithinWindow(
            LocalTime time, LocalTime start, LocalTime end, boolean emptyMeansAllowed) {
        if (start == null || end == null) return emptyMeansAllowed;
        if (start.equals(end)) return true;
        if (start.isBefore(end)) {
            return !time.isBefore(start) && time.isBefore(end);
        }
        return !time.isBefore(start) || time.isBefore(end);
    }

    public void markTriggered(LocalDateTime now) {
        this.lastTriggeredAt = now;
        this.updatedAt = now;
    }

    public boolean belongsTo(Long candidateUserId) {
        return userId.equals(candidateUserId);
    }
}
