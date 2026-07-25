package com.seoulfit.backend.engagement.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.util.EnumSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AlertSubscriptionTest {

    @Test
    @DisplayName("활성 요일과 시간대 안에서는 알림을 평가할 수 있다")
    void canTriggerInsideActiveWindow() {
        LocalDateTime now = LocalDateTime.of(2026, Month.JULY, 20, 12, 0);
        AlertSubscription subscription = subscription(
                EnumSet.of(DayOfWeek.MONDAY),
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
                null,
                null,
                now);

        assertThat(subscription.canTriggerAt(now.plusHours(1))).isTrue();
        assertThat(subscription.canTriggerAt(now.plusDays(1))).isFalse();
    }

    @Test
    @DisplayName("자정을 넘는 방해 금지 시간에는 알림을 평가하지 않는다")
    void blocksOvernightQuietHours() {
        LocalDateTime now = LocalDateTime.of(2026, Month.JULY, 20, 12, 0);
        AlertSubscription subscription = subscription(
                EnumSet.allOf(DayOfWeek.class),
                null,
                null,
                LocalTime.of(22, 0),
                LocalTime.of(7, 0),
                now);

        assertThat(subscription.canTriggerAt(now.withHour(23))).isFalse();
        assertThat(subscription.canTriggerAt(now.plusDays(1).withHour(6))).isFalse();
        assertThat(subscription.canTriggerAt(now.withHour(12))).isTrue();
    }

    @Test
    @DisplayName("쿨다운 동안 재평가를 막고 시간이 지나면 다시 허용한다")
    void enforcesCooldown() {
        LocalDateTime now = LocalDateTime.of(2026, Month.JULY, 20, 12, 0);
        AlertSubscription subscription = subscription(
                EnumSet.allOf(DayOfWeek.class), null, null, null, null, now);
        subscription.markTriggered(now);

        assertThat(subscription.canTriggerAt(now.plusMinutes(59))).isFalse();
        assertThat(subscription.canTriggerAt(now.plusMinutes(60))).isTrue();
    }

    private AlertSubscription subscription(
            EnumSet<DayOfWeek> days,
            LocalTime activeStart,
            LocalTime activeEnd,
            LocalTime quietStart,
            LocalTime quietEnd,
            LocalDateTime now) {
        return AlertSubscription.create(
                1L,
                10L,
                AlertRuleType.AIR_QUALITY,
                days,
                activeStart,
                activeEnd,
                quietStart,
                quietEnd,
                60,
                true,
                now);
    }
}
