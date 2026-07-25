package com.seoulfit.backend.notification.adapter.out.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seoulfit.backend.notification.domain.NotificationHistory;
import com.seoulfit.backend.notification.domain.NotificationType;
import com.seoulfit.backend.trigger.domain.TriggerCondition;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("외부 알림 전송 어댑터")
class NotificationSenderAdapterTest {

    @Mock private NotificationHistory notification;

    private MockWebServer server;
    private NotificationSenderAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        adapter = new NotificationSenderAdapter(WebClient.builder().build(), new ObjectMapper());
        ReflectionTestUtils.setField(adapter, "fcmServerKey", "test-key");
        ReflectionTestUtils.setField(adapter, "fcmUrl", server.url("/fcm").toString());
        ReflectionTestUtils.setField(adapter, "webhookTimeoutSeconds", 2);

        when(notification.getId()).thenReturn(42L);
        when(notification.getUserId()).thenReturn(7L);
        when(notification.getNotificationType()).thenReturn(NotificationType.WEATHER);
        when(notification.getTitle()).thenReturn("미세먼지 경보");
        when(notification.getMessage()).thenReturn("마스크를 착용하세요.");
        when(notification.getTriggerCondition()).thenReturn(TriggerCondition.AIR_QUALITY_BAD);
        when(notification.getLocationInfo()).thenReturn("서울 성동구");
        when(notification.getSentAt()).thenReturn(LocalDateTime.of(2026, 7, 26, 9, 0));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    @DisplayName("FCM 전송 성공 시 인증 헤더와 알림 식별자를 포함한 payload를 보낸다")
    void sendsPushNotificationAndRecordsSuccess() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));

        assertThat(adapter.sendPushNotification(notification, "device-token")).isTrue();
        assertThat(adapter.getDeliveryStatus(42L)).isEqualTo("SENT");

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).isEqualTo("/fcm");
        assertThat(request.getHeader("Authorization")).isEqualTo("key=test-key");
        JsonNode body = new ObjectMapper().readTree(request.getBody().readUtf8());
        assertThat(body.at("/to").asText()).isEqualTo("device-token");
        assertThat(body.at("/data/notificationId").asText()).isEqualTo("42");
        assertThat(body.at("/data/locationInfo").asText()).isEqualTo("서울 성동구");
    }

    @Test
    @DisplayName("푸시 입력·설정 오류와 제공자 실패를 false 및 FAILED 상태로 처리한다")
    void handlesPushValidationAndProviderFailure() {
        assertThat(adapter.sendPushNotification(notification, " ")).isFalse();
        ReflectionTestUtils.setField(adapter, "fcmServerKey", "");
        assertThat(adapter.sendPushNotification(notification, "device-token")).isFalse();

        ReflectionTestUtils.setField(adapter, "fcmServerKey", "test-key");
        server.enqueue(new MockResponse().setResponseCode(500).setBody("provider failure"));
        assertThat(adapter.sendPushNotification(notification, "device-token")).isFalse();
        assertThat(adapter.getDeliveryStatus(42L)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("웹훅 성공과 실패를 처리하고 원본 알림 데이터를 전달한다")
    void sendsWebhookAndHandlesFailure() throws Exception {
        assertThat(adapter.sendWebhook(notification, " ")).isFalse();

        server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
        assertThat(adapter.sendWebhook(notification, server.url("/webhook").toString())).isTrue();
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        JsonNode body = new ObjectMapper().readTree(request.getBody().readUtf8());
        assertThat(body.at("/id").asLong()).isEqualTo(42L);
        assertThat(body.at("/type").asText()).isEqualTo("WEATHER");

        server.enqueue(new MockResponse().setResponseCode(500).setBody("provider failure"));
        assertThat(adapter.sendWebhook(notification, server.url("/webhook").toString())).isFalse();
        assertThat(adapter.getDeliveryStatus(42L)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("이메일과 SMS는 활성화·입력값을 확인하고 발송 상태를 기록한다")
    void validatesAndRecordsEmailAndSmsDelivery() {
        assertThat(adapter.sendEmailNotification(notification, "user@example.com")).isFalse();
        ReflectionTestUtils.setField(adapter, "emailEnabled", true);
        assertThat(adapter.sendEmailNotification(notification, "invalid")).isFalse();
        assertThat(adapter.sendEmailNotification(notification, "user@example.com")).isTrue();
        assertThat(adapter.getDeliveryStatus(42L)).isEqualTo("SENT");

        assertThat(adapter.sendSmsNotification(notification, "010-1234-5678")).isFalse();
        ReflectionTestUtils.setField(adapter, "smsEnabled", true);
        assertThat(adapter.sendSmsNotification(notification, " ")).isFalse();
        assertThat(adapter.sendSmsNotification(notification, "010-1234-5678")).isTrue();
        assertThat(adapter.getDeliveryStatus(42L)).isEqualTo("SENT");
        assertThat(adapter.getDeliveryStatus(999L)).isEqualTo("UNKNOWN");
    }
}
