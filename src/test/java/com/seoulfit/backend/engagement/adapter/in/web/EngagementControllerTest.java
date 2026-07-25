package com.seoulfit.backend.engagement.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.seoulfit.backend.engagement.adapter.in.web.EngagementDtos.EvaluationResponse;
import com.seoulfit.backend.engagement.adapter.in.web.EngagementDtos.PlaceRequest;
import com.seoulfit.backend.engagement.adapter.in.web.EngagementDtos.SubscriptionRequest;
import com.seoulfit.backend.engagement.adapter.in.web.EngagementDtos.ZoneRequest;
import com.seoulfit.backend.engagement.application.EngagementService;
import com.seoulfit.backend.engagement.domain.AlertRuleType;
import com.seoulfit.backend.engagement.domain.AlertSubscription;
import com.seoulfit.backend.engagement.domain.SavedZone;
import com.seoulfit.backend.engagement.domain.UserPlace;
import com.seoulfit.backend.user.infrastructure.security.CustomUserDetails;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("생활권 API 컨트롤러")
class EngagementControllerTest {

    private static final long USER_ID = 7L;

    @Mock private EngagementService engagementService;
    @Mock private CustomUserDetails principal;
    @Mock private UserPlace place;
    @Mock private SavedZone zone;
    @Mock private AlertSubscription subscription;

    private EngagementController controller;
    private PlaceRequest placeRequest;
    private ZoneRequest zoneRequest;
    private SubscriptionRequest subscriptionRequest;

    @BeforeEach
    void setUp() {
        controller = new EngagementController(engagementService);
        when(principal.getUserId()).thenReturn(USER_ID);

        LocalDateTime now = LocalDateTime.of(2026, 7, 26, 9, 0);
        when(place.getId()).thenReturn(11L);
        when(place.getPlaceKey()).thenReturn("park:11");
        when(place.getSourceId()).thenReturn("11");
        when(place.getCategory()).thenReturn("park");
        when(place.getName()).thenReturn("서울숲");
        when(place.getAddress()).thenReturn("서울 성동구");
        when(place.getLatitude()).thenReturn(37.5444);
        when(place.getLongitude()).thenReturn(127.0374);
        when(place.isFavorite()).thenReturn(true);
        when(place.getSavedAt()).thenReturn(now);
        when(place.getLastViewedAt()).thenReturn(now);

        when(zone.getId()).thenReturn(22L);
        when(zone.getLabel()).thenReturn("집 주변");
        when(zone.getLatitude()).thenReturn(37.55);
        when(zone.getLongitude()).thenReturn(127.04);
        when(zone.getRadiusMeters()).thenReturn(500);
        when(zone.getCreatedAt()).thenReturn(now);

        when(subscription.getId()).thenReturn(33L);
        when(subscription.getZoneId()).thenReturn(22L);
        when(subscription.getAlertType()).thenReturn(AlertRuleType.AIR_QUALITY);
        when(subscription.getActiveDaySet()).thenReturn(Set.of(DayOfWeek.MONDAY));
        when(subscription.getActiveStart()).thenReturn(LocalTime.of(9, 0));
        when(subscription.getActiveEnd()).thenReturn(LocalTime.of(18, 0));
        when(subscription.getQuietStart()).thenReturn(LocalTime.of(22, 0));
        when(subscription.getQuietEnd()).thenReturn(LocalTime.of(7, 0));
        when(subscription.getCooldownMinutes()).thenReturn(60);
        when(subscription.isActive()).thenReturn(true);
        when(subscription.getLastTriggeredAt()).thenReturn(now);
        when(subscription.getCreatedAt()).thenReturn(now);

        placeRequest = new PlaceRequest("park:11", "11", "park", "서울숲", "서울 성동구", 37.5444, 127.0374);
        zoneRequest = new ZoneRequest("집 주변", 37.55, 127.04, 500);
        subscriptionRequest = new SubscriptionRequest(
                22L, AlertRuleType.AIR_QUALITY, Set.of(DayOfWeek.MONDAY),
                LocalTime.of(9, 0), LocalTime.of(18, 0), LocalTime.of(22, 0), LocalTime.of(7, 0), 60, true);
    }

    @Test
    @DisplayName("장소 저장·조회·최근 조회·삭제 요청을 현재 사용자로 위임한다")
    void delegatesPlaceEndpointsAndMapsResponse() {
        when(engagementService.favorites(USER_ID)).thenReturn(List.of(place));
        when(engagementService.saveFavorite(USER_ID, placeRequest)).thenReturn(place);
        when(engagementService.recentPlaces(USER_ID)).thenReturn(List.of(place));
        when(engagementService.markRecentlyViewed(USER_ID, placeRequest)).thenReturn(place);

        assertThat(controller.favorites(principal)).singleElement().satisfies(response ->
                assertThat(response).extracting("id", "favorite").containsExactly(11L, true));

        ResponseEntity<?> saved = controller.saveFavorite(principal, placeRequest);
        assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(saved.getHeaders().getLocation()).hasToString("/api/me/places/favorites/11");

        assertThat(controller.recentPlaces(principal)).singleElement().satisfies(response ->
                assertThat(response.placeKey()).isEqualTo("park:11"));
        assertThat(controller.markRecentlyViewed(principal, placeRequest).name()).isEqualTo("서울숲");
        assertThat(controller.removeFavorite(principal, 11L).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        verify(engagementService).removeFavorite(USER_ID, 11L);
    }

    @Test
    @DisplayName("생활권 생성·수정·조회·삭제 요청을 위임하고 응답을 매핑한다")
    void delegatesZoneEndpointsAndMapsResponse() {
        when(engagementService.zones(USER_ID)).thenReturn(List.of(zone));
        when(engagementService.createZone(USER_ID, zoneRequest)).thenReturn(zone);
        when(engagementService.updateZone(USER_ID, 22L, zoneRequest)).thenReturn(zone);

        assertThat(controller.zones(principal)).singleElement().satisfies(response ->
                assertThat(response).extracting("id", "label", "radiusMeters").containsExactly(22L, "집 주변", 500));

        ResponseEntity<?> created = controller.createZone(principal, zoneRequest);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).hasToString("/api/me/zones/22");
        assertThat(controller.updateZone(principal, 22L, zoneRequest).label()).isEqualTo("집 주변");
        assertThat(controller.deleteZone(principal, 22L).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        verify(engagementService).deleteZone(USER_ID, 22L);
    }

    @Test
    @DisplayName("알림 구독 CRUD와 평가 요청을 위임하고 응답을 매핑한다")
    void delegatesSubscriptionEndpointsAndMapsResponse() {
        when(engagementService.subscriptions(USER_ID)).thenReturn(List.of(subscription));
        when(engagementService.createSubscription(USER_ID, subscriptionRequest)).thenReturn(subscription);
        when(engagementService.updateSubscription(USER_ID, 33L, subscriptionRequest)).thenReturn(subscription);
        EvaluationResponse evaluation = new EvaluationResponse(3, 1, 2);
        when(engagementService.evaluateActiveSubscriptions(USER_ID)).thenReturn(evaluation);

        assertThat(controller.subscriptions(principal)).singleElement().satisfies(response ->
                assertThat(response).extracting("id", "zoneId", "active").containsExactly(33L, 22L, true));

        ResponseEntity<?> created = controller.createSubscription(principal, subscriptionRequest);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).hasToString("/api/me/alert-subscriptions/33");
        assertThat(controller.updateSubscription(principal, 33L, subscriptionRequest).cooldownMinutes()).isEqualTo(60);
        assertThat(controller.evaluateSubscriptions(principal)).isEqualTo(evaluation);
        assertThat(controller.deleteSubscription(principal, 33L).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        verify(engagementService).deleteSubscription(USER_ID, 33L);
    }
}
