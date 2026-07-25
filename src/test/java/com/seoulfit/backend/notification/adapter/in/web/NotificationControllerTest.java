package com.seoulfit.backend.notification.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seoulfit.backend.config.TestSecurityConfig;
import com.seoulfit.backend.config.WithMockCustomUser;
import com.seoulfit.backend.notification.application.port.in.ManageNotificationUseCase;
import com.seoulfit.backend.notification.application.port.in.dto.CreateNotificationCommand;
import com.seoulfit.backend.notification.application.port.in.dto.NotificationHistoryQuery;
import com.seoulfit.backend.notification.application.port.in.dto.NotificationHistoryResult;
import com.seoulfit.backend.notification.domain.NotificationStatus;
import com.seoulfit.backend.notification.domain.NotificationType;
import com.seoulfit.backend.trigger.domain.TriggerCondition;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(NotificationController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@WithMockCustomUser(id = 42L)
class NotificationControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private ManageNotificationUseCase manageNotificationUseCase;

    @Test
    @DisplayName("알림 생성은 요청의 userId가 아니라 인증 사용자를 사용한다")
    void createsNotificationForAuthenticatedUser() throws Exception {
        when(manageNotificationUseCase.createNotification(any()))
                .thenReturn(notification(1L, 42L));

        mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": 999,
                                  "notificationType": "WEATHER",
                                  "title": "날씨 알림",
                                  "message": "우산을 챙기세요.",
                                  "triggerCondition": "WEATHER_CHANGE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(42L));

        ArgumentCaptor<CreateNotificationCommand> command =
                ArgumentCaptor.forClass(CreateNotificationCommand.class);
        verify(manageNotificationUseCase).createNotification(command.capture());
        assertThat(command.getValue().userId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("알림 목록 쿼리는 인증 사용자를 사용한다")
    void listsAuthenticatedUsersNotifications() throws Exception {
        when(manageNotificationUseCase.getNotificationHistory(any()))
                .thenReturn(new PageImpl<>(List.of(notification(1L, 42L))));

        mockMvc.perform(get("/api/notifications").param("userId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].userId").value(42L));

        ArgumentCaptor<NotificationHistoryQuery> query =
                ArgumentCaptor.forClass(NotificationHistoryQuery.class);
        verify(manageNotificationUseCase).getNotificationHistory(query.capture());
        assertThat(query.getValue().userId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("읽음 처리도 인증 사용자 범위로 제한한다")
    void marksReadForAuthenticatedUser() throws Exception {
        mockMvc.perform(patch("/api/notifications/7/read").param("userId", "999"))
                .andExpect(status().isOk());
        verify(manageNotificationUseCase).markAsRead(7L, 42L);

        mockMvc.perform(patch("/api/notifications/read-all").param("userId", "999"))
                .andExpect(status().isOk());
        verify(manageNotificationUseCase).markAllAsRead(42L);
    }

    @Test
    @DisplayName("읽지 않은 수는 인증 사용자 범위로 제한한다")
    void countsUnreadForAuthenticatedUser() throws Exception {
        when(manageNotificationUseCase.getUnreadCount(42L)).thenReturn(3L);

        mockMvc.perform(get("/api/notifications/unread-count").param("userId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(3));
    }

    @Test
    @DisplayName("필수 필드가 없으면 생성 요청을 거부한다")
    void rejectsInvalidNotification() throws Exception {
        mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    private NotificationHistoryResult notification(Long id, Long userId) {
        return new NotificationHistoryResult(
                id,
                userId,
                NotificationType.WEATHER,
                "날씨 알림",
                "우산을 챙기세요.",
                null,
                TriggerCondition.WEATHER_CHANGE,
                "서울",
                NotificationStatus.SENT,
                LocalDateTime.now(),
                null);
    }
}
