package com.seoulfit.backend.engagement.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.seoulfit.backend.engagement.adapter.in.web.EngagementDtos.EvaluationResponse;
import com.seoulfit.backend.engagement.adapter.in.web.EngagementDtos.PlaceRequest;
import com.seoulfit.backend.engagement.adapter.in.web.EngagementDtos.SubscriptionRequest;
import com.seoulfit.backend.engagement.adapter.in.web.EngagementDtos.ZoneRequest;
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
import java.time.LocalTime;
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

    @Test
    @DisplayName("즐겨찾기는 새 장소를 만들고 기존 장소도 같은 사용자 키로 갱신한다")
    void savesFavoriteForNewAndExistingPlace() {
        PlaceRequest request = placeRequest();
        when(userPlaceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserPlace created = service.saveFavorite(1L, request);

        assertThat(created.isFavorite()).isTrue();
        assertThat(created.getPlaceKey()).isEqualTo(request.placeKey());
        verify(userPlaceRepository).findByUserIdAndPlaceKey(1L, request.placeKey());

        UserPlace existing = UserPlace.create(
                1L, request.placeKey(), "old", "park", "이전 이름", "이전 주소", 37.5, 127.0,
                LocalDateTime.now());
        when(userPlaceRepository.findByUserIdAndPlaceKey(1L, request.placeKey()))
                .thenReturn(Optional.of(existing));

        UserPlace updated = service.saveFavorite(1L, request);

        assertThat(updated.getName()).isEqualTo(request.name());
        assertThat(updated.isFavorite()).isTrue();
    }

    @Test
    @DisplayName("최근 본 장소는 생성 또는 기존 장소에 조회 시각을 기록한다")
    void marksRecentlyViewed() {
        PlaceRequest request = placeRequest();
        when(userPlaceRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserPlace viewed = service.markRecentlyViewed(1L, request);

        assertThat(viewed.getLastViewedAt()).isNotNull();
        assertThat(viewed.isFavorite()).isFalse();
    }

    @Test
    @DisplayName("최근 본 이력이 있는 즐겨찾기는 삭제 대신 즐겨찾기만 해제한다")
    void removesFavoriteButRetainsRecentlyViewedPlace() {
        LocalDateTime now = LocalDateTime.now();
        UserPlace place = UserPlace.create(
                1L, "park:1", "1", "park", "공원", "서울", 37.56, 126.97, now);
        place.saveAsFavorite(now);
        place.markViewed(now);
        when(userPlaceRepository.findById(7L)).thenReturn(Optional.of(place));

        service.removeFavorite(1L, 7L);

        assertThat(place.isFavorite()).isFalse();
        verify(userPlaceRepository, never()).delete(place);
    }

    @Test
    @DisplayName("생활권은 중복 이름을 거부하고 새 이름은 저장한다")
    void createsZoneWithUniqueLabel() {
        ZoneRequest request = new ZoneRequest("집", 37.56, 126.97, 1500);
        when(savedZoneRepository.existsByUserIdAndLabel(1L, "집")).thenReturn(true);

        assertThatThrownBy(() -> service.createZone(1L, request))
                .isInstanceOf(IllegalArgumentException.class);

        when(savedZoneRepository.existsByUserIdAndLabel(1L, "집")).thenReturn(false);
        when(savedZoneRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SavedZone saved = service.createZone(1L, request);

        assertThat(saved.getLabel()).isEqualTo("집");
        assertThat(saved.getRadiusMeters()).isEqualTo(1500);
    }

    @Test
    @DisplayName("생활권 업데이트는 소유자를 확인하고 이름 충돌을 방지한다")
    void updatesOwnedZoneWhenLabelIsAvailable() {
        SavedZone zone = SavedZone.create(1L, "집", 37.56, 126.97, 1500, LocalDateTime.now());
        ReflectionTestUtils.setField(zone, "id", 8L);
        ZoneRequest request = new ZoneRequest("회사", 37.50, 127.03, 1200);
        when(savedZoneRepository.findByIdAndUserId(8L, 1L)).thenReturn(Optional.of(zone));
        when(savedZoneRepository.existsByUserIdAndLabelAndIdNot(1L, "회사", 8L)).thenReturn(false);

        SavedZone updated = service.updateZone(1L, 8L, request);

        assertThat(updated.getLabel()).isEqualTo("회사");
        assertThat(updated.getRadiusMeters()).isEqualTo(1200);
    }

    @Test
    @DisplayName("알림 규칙은 중복을 거부하고 고유한 규칙은 저장한다")
    void createsSubscriptionOnlyWhenItIsUnique() {
        SavedZone zone = SavedZone.create(1L, "집", 37.56, 126.97, 1500, LocalDateTime.now());
        ReflectionTestUtils.setField(zone, "id", 8L);
        SubscriptionRequest request = subscriptionRequest(8L);
        when(savedZoneRepository.findByIdAndUserId(8L, 1L)).thenReturn(Optional.of(zone));
        when(alertSubscriptionRepository.existsByUserIdAndZoneIdAndAlertType(
                1L, 8L, AlertRuleType.AIR_QUALITY)).thenReturn(true);

        assertThatThrownBy(() -> service.createSubscription(1L, request))
                .isInstanceOf(IllegalArgumentException.class);

        when(alertSubscriptionRepository.existsByUserIdAndZoneIdAndAlertType(
                1L, 8L, AlertRuleType.AIR_QUALITY)).thenReturn(false);
        when(alertSubscriptionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AlertSubscription saved = service.createSubscription(1L, request);

        assertThat(saved.getZoneId()).isEqualTo(8L);
        assertThat(saved.getAlertType()).isEqualTo(AlertRuleType.AIR_QUALITY);
    }

    @Test
    @DisplayName("다른 생활권으로의 알림 규칙 변경은 거부한다")
    void rejectsSubscriptionZoneChange() {
        AlertSubscription subscription = AlertSubscription.create(
                1L, 8L, AlertRuleType.AIR_QUALITY, EnumSet.allOf(DayOfWeek.class),
                null, null, null, null, 60, true, LocalDateTime.now());
        when(alertSubscriptionRepository.findByIdAndUserId(9L, 1L)).thenReturn(Optional.of(subscription));

        assertThatThrownBy(() -> service.updateSubscription(1L, 9L, subscriptionRequest(10L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("평가 대상이 쿨다운이나 시간 조건을 만족하지 않으면 외부 평가를 호출하지 않는다")
    void defersIneligibleSubscriptions() {
        AlertSubscription subscription = AlertSubscription.create(
                1L, 8L, AlertRuleType.AIR_QUALITY, EnumSet.noneOf(DayOfWeek.class),
                null, null, null, null, 60, false, LocalDateTime.now());
        when(alertSubscriptionRepository.findByUserIdAndActiveTrueOrderByCreatedAtAsc(1L))
                .thenReturn(List.of(subscription));

        EvaluationResponse response = service.evaluateActiveSubscriptions(1L);

        assertThat(response).isEqualTo(new EvaluationResponse(0, 0, 1));
        verify(evaluateTriggerUseCase, never()).evaluateLocationBasedTriggers(any());
    }

    private PlaceRequest placeRequest() {
        return new PlaceRequest("park:1", "1", "park", "서울숲", "서울 성동구", 37.56, 126.97);
    }

    private SubscriptionRequest subscriptionRequest(Long zoneId) {
        return new SubscriptionRequest(
                zoneId,
                AlertRuleType.AIR_QUALITY,
                EnumSet.allOf(DayOfWeek.class),
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
                null,
                null,
                60,
                true);
    }
}
