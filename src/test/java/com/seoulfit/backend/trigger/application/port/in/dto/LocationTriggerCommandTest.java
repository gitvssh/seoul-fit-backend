package com.seoulfit.backend.trigger.application.port.in.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("위치 기반 트리거 명령")
class LocationTriggerCommandTest {

    @Test
    @DisplayName("일반 명령은 기본 반경과 비강제 평가를 사용한다")
    void createsDefaultCommand() {
        LocationTriggerCommand command = LocationTriggerCommand.of(
                "7", 37.5, 127.0, null, List.of("AIR_QUALITY"));

        assertThat(command.getRadius()).isEqualTo(2000);
        assertThat(command.getForceEvaluation()).isFalse();
        assertThat(command.getTriggerTypes()).containsExactly("AIR_QUALITY");
    }

    @Test
    @DisplayName("구독 명령은 평가 근거와 중복 방지 키를 보존한다")
    void createsSubscriptionCommand() {
        LocalDateTime observedAt = LocalDateTime.of(2026, Month.JULY, 26, 9, 0);

        LocationTriggerCommand command = LocationTriggerCommand.ofSubscription(
                "7", 37.5, 127.0, 700, "AIR_QUALITY", 11L,
                "threshold exceeded", "/places/11", observedAt, "dedup-11");

        assertThat(command.getRadius()).isEqualTo(700);
        assertThat(command.getTriggerTypes()).containsExactly("AIR_QUALITY");
        assertThat(command.getSubscriptionId()).isEqualTo(11L);
        assertThat(command.getReason()).isEqualTo("threshold exceeded");
        assertThat(command.getDeepLink()).isEqualTo("/places/11");
        assertThat(command.getDataObservedAt()).isEqualTo(observedAt);
        assertThat(command.getDedupKey()).isEqualTo("dedup-11");
    }

    @Test
    @DisplayName("강제 평가 명령은 명시 값과 기본값을 모두 지원한다")
    void createsForcedCommand() {
        LocationTriggerCommand forced = LocationTriggerCommand.ofForced(
                "7", 37.5, 127.0, 300, List.of("BIKE_SHORTAGE"), true);
        LocationTriggerCommand defaulted = LocationTriggerCommand.ofForced(
                "7", 37.5, 127.0, null, List.of("BIKE_SHORTAGE"), null);

        assertThat(forced.getRadius()).isEqualTo(300);
        assertThat(forced.getForceEvaluation()).isTrue();
        assertThat(defaulted.getRadius()).isEqualTo(2000);
        assertThat(defaulted.getForceEvaluation()).isFalse();
    }
}
