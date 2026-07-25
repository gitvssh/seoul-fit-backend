package com.seoulfit.backend.engagement.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.seoulfit.backend.engagement.adapter.in.web.EngagementDtos.EvaluationResponse;
import com.seoulfit.backend.engagement.domain.AlertRuleType;
import com.seoulfit.backend.engagement.domain.AlertSubscription;
import com.seoulfit.backend.engagement.domain.SavedZone;
import com.seoulfit.backend.engagement.domain.UserPlace;
import com.seoulfit.backend.engagement.infrastructure.AlertSubscriptionRepository;
import com.seoulfit.backend.engagement.infrastructure.SavedZoneRepository;
import com.seoulfit.backend.engagement.infrastructure.UserPlaceRepository;
import com.seoulfit.backend.trigger.application.port.in.EvaluateTriggerUseCase;
import com.seoulfit.backend.trigger.application.port.in.dto.LocationTriggerCommand;
import com.seoulfit.backend.trigger.application.port.in.dto.TriggerEvaluationResult;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class EngagementServiceTest {

    @Mock private UserPlaceRepository userPlaceRepository;
    @Mock private SavedZoneRepository savedZoneRepository;
    @Mock private AlertSubscriptionRepository alertSubscriptionRepository;
    @Mock private EvaluateTriggerUseCase evaluateTriggerUseCase;

    private EngagementService service;

    @BeforeEach
    void setUp() {
        service = new EngagementService(
                userPlaceRepository,
                savedZoneRepository,
                alertSubscriptionRepository,
                evaluateTriggerUseCase);
    }

    @Test
    @DisplayName("다른 사용자의 저장 장소는 삭제할 수 없다")
    void cannotDeleteAnotherUsersFavorite() {
        UserPlace place = UserPlace.create(
                2L,
                "park:1",
                "1",
                "park",
                "공원",
                "서울",
                37.56,
                126.97,
                LocalDateTime.now());
        when(userPlaceRepository.findById(7L)).thenReturn(Optional.of(place));

        assertThatThrownBy(() -> service.removeFavorite(1L, 7L))
                .isInstanceOf(IllegalArgumentException.class);
        verify(userPlaceRepository, never()).delete(any());
    }

    @Test
    @DisplayName("생활권 삭제 시 종속 알림 규칙을 먼저 삭제한다")
    void deletesZoneSubscriptionsBeforeZone() {
        SavedZone zone = SavedZone.create(
                1L, "집", 37.56, 126.97, 1500, LocalDateTime.now());
        ReflectionTestUtils.setField(zone, "id", 8L);
        when(savedZoneRepository.findByIdAndUserId(8L, 1L)).thenReturn(Optional.of(zone));

        service.deleteZone(1L, 8L);

        verify(alertSubscriptionRepository).deleteByUserIdAndZoneId(1L, 8L);
        verify(savedZoneRepository).delete(zone);
    }

    @Test
    @DisplayName("활성 알림 규칙은 소유한 생활권 좌표로 평가하고 발동 시간을 기록한다")
    void evaluatesEligibleSubscription() {
        LocalDateTime createdAt = LocalDateTime.now().minusHours(1);
        SavedZone zone = SavedZone.create(1L, "회사", 37.50, 127.03, 1200, createdAt);
        ReflectionTestUtils.setField(zone, "id", 4L);
        AlertSubscription subscription = AlertSubscription.create(
                1L,
                4L,
                AlertRuleType.AIR_QUALITY,
                EnumSet.allOf(DayOfWeek.class),
                null,
                null,
                null,
                null,
                60,
                true,
                createdAt);
        ReflectionTestUtils.setField(subscription, "id", 9L);
        when(alertSubscriptionRepository.findByUserIdAndActiveTrueOrderByCreatedAtAsc(1L))
                .thenReturn(List.of(subscription));
        when(savedZoneRepository.findByIdAndUserId(4L, 1L)).thenReturn(Optional.of(zone));
        when(evaluateTriggerUseCase.evaluateLocationBasedTriggers(any()))
                .thenReturn(TriggerEvaluationResult.builder().triggered(true).build());

        EvaluationResponse response = service.evaluateActiveSubscriptions(1L);

        assertThat(response.evaluated()).isEqualTo(1);
        assertThat(response.generated()).isEqualTo(1);
        assertThat(subscription.getLastTriggeredAt()).isNotNull();
        ArgumentCaptor<LocationTriggerCommand> command =
                ArgumentCaptor.forClass(LocationTriggerCommand.class);
        verify(evaluateTriggerUseCase).evaluateLocationBasedTriggers(command.capture());
        assertThat(command.getValue().getUserId()).isEqualTo("1");
        assertThat(command.getValue().getSubscriptionId()).isEqualTo(9L);
        assertThat(command.getValue().getDeepLink()).startsWith("/?lat=");
    }
}
