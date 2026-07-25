package com.seoulfit.backend.engagement.adapter.in.web;

import com.seoulfit.backend.engagement.adapter.in.web.EngagementDtos.EvaluationResponse;
import com.seoulfit.backend.engagement.adapter.in.web.EngagementDtos.PlaceRequest;
import com.seoulfit.backend.engagement.adapter.in.web.EngagementDtos.PlaceResponse;
import com.seoulfit.backend.engagement.adapter.in.web.EngagementDtos.SubscriptionRequest;
import com.seoulfit.backend.engagement.adapter.in.web.EngagementDtos.SubscriptionResponse;
import com.seoulfit.backend.engagement.adapter.in.web.EngagementDtos.ZoneRequest;
import com.seoulfit.backend.engagement.adapter.in.web.EngagementDtos.ZoneResponse;
import com.seoulfit.backend.engagement.application.EngagementService;
import com.seoulfit.backend.user.infrastructure.security.CustomUserDetails;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class EngagementController {

    private final EngagementService engagementService;

    @GetMapping("/places/favorites")
    public List<PlaceResponse> favorites(@AuthenticationPrincipal CustomUserDetails principal) {
        return engagementService.favorites(principal.getUserId()).stream()
                .map(PlaceResponse::from)
                .toList();
    }

    @PostMapping("/places/favorites")
    public ResponseEntity<PlaceResponse> saveFavorite(
            @AuthenticationPrincipal CustomUserDetails principal,
            @Valid @RequestBody PlaceRequest request) {
        PlaceResponse response =
                PlaceResponse.from(engagementService.saveFavorite(principal.getUserId(), request));
        return ResponseEntity.created(URI.create("/api/me/places/favorites/" + response.id()))
                .body(response);
    }

    @DeleteMapping("/places/favorites/{placeId}")
    public ResponseEntity<Void> removeFavorite(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable Long placeId) {
        engagementService.removeFavorite(principal.getUserId(), placeId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/places/recent")
    public List<PlaceResponse> recentPlaces(@AuthenticationPrincipal CustomUserDetails principal) {
        return engagementService.recentPlaces(principal.getUserId()).stream()
                .map(PlaceResponse::from)
                .toList();
    }

    @PostMapping("/places/recent")
    public PlaceResponse markRecentlyViewed(
            @AuthenticationPrincipal CustomUserDetails principal,
            @Valid @RequestBody PlaceRequest request) {
        return PlaceResponse.from(
                engagementService.markRecentlyViewed(principal.getUserId(), request));
    }

    @GetMapping("/zones")
    public List<ZoneResponse> zones(@AuthenticationPrincipal CustomUserDetails principal) {
        return engagementService.zones(principal.getUserId()).stream()
                .map(ZoneResponse::from)
                .toList();
    }

    @PostMapping("/zones")
    public ResponseEntity<ZoneResponse> createZone(
            @AuthenticationPrincipal CustomUserDetails principal,
            @Valid @RequestBody ZoneRequest request) {
        ZoneResponse response =
                ZoneResponse.from(engagementService.createZone(principal.getUserId(), request));
        return ResponseEntity.created(URI.create("/api/me/zones/" + response.id())).body(response);
    }

    @PatchMapping("/zones/{zoneId}")
    public ZoneResponse updateZone(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable Long zoneId,
            @Valid @RequestBody ZoneRequest request) {
        return ZoneResponse.from(
                engagementService.updateZone(principal.getUserId(), zoneId, request));
    }

    @DeleteMapping("/zones/{zoneId}")
    public ResponseEntity<Void> deleteZone(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable Long zoneId) {
        engagementService.deleteZone(principal.getUserId(), zoneId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/alert-subscriptions")
    public List<SubscriptionResponse> subscriptions(
            @AuthenticationPrincipal CustomUserDetails principal) {
        return engagementService.subscriptions(principal.getUserId()).stream()
                .map(SubscriptionResponse::from)
                .toList();
    }

    @PostMapping("/alert-subscriptions")
    public ResponseEntity<SubscriptionResponse> createSubscription(
            @AuthenticationPrincipal CustomUserDetails principal,
            @Valid @RequestBody SubscriptionRequest request) {
        SubscriptionResponse response = SubscriptionResponse.from(
                engagementService.createSubscription(principal.getUserId(), request));
        return ResponseEntity.created(
                        URI.create("/api/me/alert-subscriptions/" + response.id()))
                .body(response);
    }

    @PatchMapping("/alert-subscriptions/{subscriptionId}")
    public SubscriptionResponse updateSubscription(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable Long subscriptionId,
            @Valid @RequestBody SubscriptionRequest request) {
        return SubscriptionResponse.from(
                engagementService.updateSubscription(
                        principal.getUserId(), subscriptionId, request));
    }

    @DeleteMapping("/alert-subscriptions/{subscriptionId}")
    public ResponseEntity<Void> deleteSubscription(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable Long subscriptionId) {
        engagementService.deleteSubscription(principal.getUserId(), subscriptionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/alert-subscriptions/evaluate")
    public EvaluationResponse evaluateSubscriptions(
            @AuthenticationPrincipal CustomUserDetails principal) {
        return engagementService.evaluateActiveSubscriptions(principal.getUserId());
    }
}
