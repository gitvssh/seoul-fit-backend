package com.seoulfit.backend.engagement.infrastructure;

import com.seoulfit.backend.engagement.domain.AlertSubscription;
import com.seoulfit.backend.engagement.domain.AlertRuleType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertSubscriptionRepository extends JpaRepository<AlertSubscription, Long> {
    List<AlertSubscription> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<AlertSubscription> findByUserIdAndActiveTrueOrderByCreatedAtAsc(Long userId);
    Optional<AlertSubscription> findByIdAndUserId(Long id, Long userId);
    boolean existsByUserIdAndZoneIdAndAlertType(
            Long userId, Long zoneId, AlertRuleType alertType);
    void deleteByUserIdAndZoneId(Long userId, Long zoneId);
}
