package com.seoulfit.backend.engagement.application;

import com.seoulfit.backend.engagement.adapter.in.web.EngagementDtos.EvaluationResponse;
import com.seoulfit.backend.engagement.adapter.in.web.EngagementDtos.PlaceRequest;
import com.seoulfit.backend.engagement.adapter.in.web.EngagementDtos.SubscriptionRequest;
import com.seoulfit.backend.engagement.adapter.in.web.EngagementDtos.ZoneRequest;
import com.seoulfit.backend.engagement.domain.AlertSubscription;
import com.seoulfit.backend.engagement.domain.SavedZone;
import com.seoulfit.backend.engagement.domain.UserPlace;
import com.seoulfit.backend.engagement.infrastructure.AlertSubscriptionRepository;
import com.seoulfit.backend.engagement.infrastructure.SavedZoneRepository;
import com.seoulfit.backend.engagement.infrastructure.UserPlaceRepository;
import com.seoulfit.backend.trigger.application.port.in.EvaluateTriggerUseCase;
import com.seoulfit.backend.trigger.application.port.in.dto.LocationTriggerCommand;
import com.seoulfit.backend.trigger.application.port.in.dto.TriggerEvaluationResult;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EngagementService {

    private static final ZoneId APPLICATION_ZONE = ZoneId.systemDefault();

    private final UserPlaceRepository userPlaceRepository;
    private final SavedZoneRepository savedZoneRepository;
    private final AlertSubscriptionRepository alertSubscriptionRepository;
    private final EvaluateTriggerUseCase evaluateTriggerUseCase;

    public List<UserPlace> favorites(Long userId) {
        return userPlaceRepository.findByUserIdAndFavoriteTrueOrderBySavedAtDesc(userId);
    }

    public List<UserPlace> recentPlaces(Long userId) {
        return userPlaceRepository.findTop50ByUserIdAndLastViewedAtIsNotNullOrderByLastViewedAtDesc(userId);
    }

    @Transactional
    public UserPlace saveFavorite(Long userId, PlaceRequest request) {
        LocalDateTime now = LocalDateTime.now(APPLICATION_ZONE);
        UserPlace place = upsertPlace(userId, request, now);
        place.saveAsFavorite(now);
        return userPlaceRepository.save(place);
    }

    @Transactional
    public UserPlace markRecentlyViewed(Long userId, PlaceRequest request) {
        LocalDateTime now = LocalDateTime.now(APPLICATION_ZONE);
        UserPlace place = upsertPlace(userId, request, now);
        place.markViewed(now);
        return userPlaceRepository.save(place);
    }

    private UserPlace upsertPlace(Long userId, PlaceRequest request, LocalDateTime now) {
        UserPlace place = userPlaceRepository.findByUserIdAndPlaceKey(userId, request.placeKey())
                .orElseGet(() -> UserPlace.create(
                        userId,
                        request.placeKey(),
                        request.sourceId(),
                        request.category(),
                        request.name(),
                        request.address(),
                        request.latitude(),
                        request.longitude(),
                        now));
        place.updateDetails(
                request.sourceId(),
                request.category(),
                request.name(),
                request.address(),
                request.latitude(),
                request.longitude(),
                now);
        return place;
    }

    @Transactional
    public void removeFavorite(Long userId, Long placeId) {
        UserPlace place = userPlaceRepository.findById(placeId)
                .filter(candidate -> candidate.belongsTo(userId))
                .orElseThrow(() -> new IllegalArgumentException("저장한 장소를 찾을 수 없습니다."));
        if (place.getLastViewedAt() == null) {
            userPlaceRepository.delete(place);
        } else {
            place.removeFavorite(LocalDateTime.now(APPLICATION_ZONE));
        }
    }

    public List<SavedZone> zones(Long userId) {
        return savedZoneRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public SavedZone createZone(Long userId, ZoneRequest request) {
        if (savedZoneRepository.existsByUserIdAndLabel(userId, request.label())) {
            throw new IllegalArgumentException("같은 이름의 생활권이 이미 있습니다.");
        }
        return savedZoneRepository.save(SavedZone.create(
                userId,
                request.label(),
                request.latitude(),
                request.longitude(),
                request.radiusMeters(),
                LocalDateTime.now(APPLICATION_ZONE)));
    }

    @Transactional
    public SavedZone updateZone(Long userId, Long zoneId, ZoneRequest request) {
        SavedZone zone = ownedZone(userId, zoneId);
        if (savedZoneRepository.existsByUserIdAndLabelAndIdNot(
                userId, request.label(), zoneId)) {
            throw new IllegalArgumentException("같은 이름의 생활권이 이미 있습니다.");
        }
        zone.update(
                request.label(),
                request.latitude(),
                request.longitude(),
                request.radiusMeters(),
                LocalDateTime.now(APPLICATION_ZONE));
        return zone;
    }

    @Transactional
    public void deleteZone(Long userId, Long zoneId) {
        SavedZone zone = ownedZone(userId, zoneId);
        alertSubscriptionRepository.deleteByUserIdAndZoneId(userId, zoneId);
        savedZoneRepository.delete(zone);
    }

    public List<AlertSubscription> subscriptions(Long userId) {
        return alertSubscriptionRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public AlertSubscription createSubscription(Long userId, SubscriptionRequest request) {
        ownedZone(userId, request.zoneId());
        if (alertSubscriptionRepository.existsByUserIdAndZoneIdAndAlertType(
                userId, request.zoneId(), request.alertType())) {
            throw new IllegalArgumentException("같은 생활권에 동일한 알림 규칙이 이미 있습니다.");
        }
        return alertSubscriptionRepository.save(AlertSubscription.create(
                userId,
                request.zoneId(),
                request.alertType(),
                request.activeDays(),
                request.activeStart(),
                request.activeEnd(),
                request.quietStart(),
                request.quietEnd(),
                request.cooldownMinutes(),
                request.active(),
                LocalDateTime.now(APPLICATION_ZONE)));
    }

    @Transactional
    public AlertSubscription updateSubscription(
            Long userId, Long subscriptionId, SubscriptionRequest request) {
        AlertSubscription subscription = ownedSubscription(userId, subscriptionId);
        if (!subscription.getZoneId().equals(request.zoneId())) {
            throw new IllegalArgumentException("알림 규칙의 생활권은 변경할 수 없습니다.");
        }
        subscription.update(
                request.activeDays(),
                request.activeStart(),
                request.activeEnd(),
                request.quietStart(),
                request.quietEnd(),
                request.cooldownMinutes(),
                request.active(),
                LocalDateTime.now(APPLICATION_ZONE));
        return subscription;
    }

    @Transactional
    public void deleteSubscription(Long userId, Long subscriptionId) {
        alertSubscriptionRepository.delete(ownedSubscription(userId, subscriptionId));
    }

    @Transactional
    public EvaluationResponse evaluateActiveSubscriptions(Long userId) {
        LocalDateTime now = LocalDateTime.now(APPLICATION_ZONE);
        int evaluated = 0;
        int generated = 0;
        int deferred = 0;

        for (AlertSubscription subscription :
                alertSubscriptionRepository.findByUserIdAndActiveTrueOrderByCreatedAtAsc(userId)) {
            if (!subscription.canTriggerAt(now)) {
                deferred++;
                continue;
            }
            SavedZone zone = ownedZone(userId, subscription.getZoneId());
            evaluated++;
            long bucket = now.toEpochSecond(ZoneOffset.UTC)
                    / Math.max(900, subscription.getCooldownMinutes() * 60L);
            String dedupKey = "subscription:" + subscription.getId() + ":" + bucket;
            String deepLink = "/?lat=" + zone.getLatitude() + "&lng=" + zone.getLongitude();
            TriggerEvaluationResult result = evaluateTriggerUseCase.evaluateLocationBasedTriggers(
                    LocationTriggerCommand.ofSubscription(
                            String.valueOf(userId),
                            zone.getLatitude(),
                            zone.getLongitude(),
                            zone.getRadiusMeters(),
                            subscription.getAlertType().triggerStrategy(),
                            subscription.getId(),
                            "저장한 생활권 '" + zone.getLabel() + "'의 알림 조건과 일치했습니다.",
                            deepLink,
                            now,
                            dedupKey));
            if (result.isTriggered()) {
                subscription.markTriggered(now);
                generated++;
            }
        }
        return new EvaluationResponse(evaluated, generated, deferred);
    }

    private SavedZone ownedZone(Long userId, Long zoneId) {
        return savedZoneRepository.findByIdAndUserId(zoneId, userId)
                .orElseThrow(() -> new IllegalArgumentException("생활권을 찾을 수 없습니다."));
    }

    private AlertSubscription ownedSubscription(Long userId, Long subscriptionId) {
        return alertSubscriptionRepository.findByIdAndUserId(subscriptionId, userId)
                .orElseThrow(() -> new IllegalArgumentException("알림 규칙을 찾을 수 없습니다."));
    }
}
