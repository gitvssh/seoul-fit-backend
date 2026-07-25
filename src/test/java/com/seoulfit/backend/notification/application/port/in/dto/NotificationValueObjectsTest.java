package com.seoulfit.backend.notification.application.port.in.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seoulfit.backend.notification.domain.NotificationHistory;
import com.seoulfit.backend.notification.domain.NotificationStatus;
import com.seoulfit.backend.notification.domain.NotificationType;
import com.seoulfit.backend.trigger.domain.TriggerCondition;
import java.time.LocalDateTime;
import java.time.Month;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("알림 명령과 결과 값 객체")
class NotificationValueObjectsTest {

    private static final LocalDateTime OBSERVED_AT = LocalDateTime.of(2026, Month.JULY, 26, 9, 0);

    @Test
    @DisplayName("기본·우선순위·인앱 알림 명령이 전달 데이터를 보존한다")
    void createsNotificationCommands() {
        CreateNotificationCommand basic = CreateNotificationCommand.of(
                7L, NotificationType.WEATHER, "날씨", "비가 옵니다",
                TriggerCondition.HEAVY_RAIN, "서울");
        CreateNotificationCommand prioritized = CreateNotificationCommand.of(
                7L, NotificationType.WEATHER, "날씨", "비가 옵니다",
                TriggerCondition.HEAVY_RAIN, "서울", 3);
        CreateNotificationCommand inApp = CreateNotificationCommand.inApp(
                7L, NotificationType.WEATHER, "날씨", "비가 옵니다",
                TriggerCondition.HEAVY_RAIN, "서울", 3, 11L, "임계치 초과",
                "/places/11", OBSERVED_AT, "dedup-11");

        assertThat(basic).extracting(CreateNotificationCommand::userId,
                CreateNotificationCommand::priority, CreateNotificationCommand::getNotificationType)
                .containsExactly(7L, null, NotificationType.WEATHER);
        assertThat(prioritized.priority()).isEqualTo(3);
        assertThat(inApp).extracting(CreateNotificationCommand::subscriptionId,
                CreateNotificationCommand::reason, CreateNotificationCommand::deepLink,
                CreateNotificationCommand::dataObservedAt, CreateNotificationCommand::dedupKey)
                .containsExactly(11L, "임계치 초과", "/places/11", OBSERVED_AT, "dedup-11");
    }

    @Test
    @DisplayName("필수 알림 필드가 누락되면 명확하게 거부한다")
    void rejectsInvalidNotificationCommands() {
        assertThatThrownBy(() -> CreateNotificationCommand.of(
                null, NotificationType.WEATHER, "제목", "내용", TriggerCondition.HEAVY_RAIN, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("사용자 ID는 필수입니다.");
        assertThatThrownBy(() -> CreateNotificationCommand.of(
                7L, null, "제목", "내용", TriggerCondition.HEAVY_RAIN, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("알림 타입은 필수입니다.");
        assertThatThrownBy(() -> CreateNotificationCommand.of(
                7L, NotificationType.WEATHER, " ", "내용", TriggerCondition.HEAVY_RAIN, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("알림 제목은 필수입니다.");
        assertThatThrownBy(() -> CreateNotificationCommand.of(
                7L, NotificationType.WEATHER, "제목", " ", TriggerCondition.HEAVY_RAIN, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("알림 메시지는 필수입니다.");
    }

    @Test
    @DisplayName("인앱 알림 이력은 배달 맥락과 결과 응답을 보존한다")
    void mapsInAppHistoryToResult() {
        NotificationHistory history = NotificationHistory.createInApp(
                7L, NotificationType.WEATHER, "날씨", "비가 옵니다",
                TriggerCondition.HEAVY_RAIN, "서울", 11L, "임계치 초과",
                "/places/11", OBSERVED_AT, "dedup-11");
        ReflectionTestUtils.setField(history, "id", 99L);
        ReflectionTestUtils.setField(history, "sentAt", OBSERVED_AT);

        assertThat(history).extracting(NotificationHistory::getSubscriptionId,
                NotificationHistory::getReason, NotificationHistory::getDeepLink,
                NotificationHistory::getDataObservedAt, NotificationHistory::getProviderStatus)
                .containsExactly(11L, "임계치 초과", "/places/11", OBSERVED_AT, "IN_APP_CREATED");

        NotificationHistoryResult result = NotificationHistoryResult.from(history);
        assertThat(result).extracting(NotificationHistoryResult::id, NotificationHistoryResult::userId,
                NotificationHistoryResult::subscriptionId, NotificationHistoryResult::getCreatedAt,
                NotificationHistoryResult::getNotificationType, NotificationHistoryResult::isRead)
                .containsExactly(99L, 7L, 11L, OBSERVED_AT, NotificationType.WEATHER, false);

        history.markAsRead();
        NotificationHistoryResult readResult = NotificationHistoryResult.from(history);
        assertThat(readResult.isRead()).isTrue();
        assertThat(readResult.status()).isEqualTo(NotificationStatus.READ);
    }
}
