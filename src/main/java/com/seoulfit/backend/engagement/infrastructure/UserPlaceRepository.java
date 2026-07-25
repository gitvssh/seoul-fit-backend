package com.seoulfit.backend.engagement.infrastructure;

import com.seoulfit.backend.engagement.domain.UserPlace;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPlaceRepository extends JpaRepository<UserPlace, Long> {
    Optional<UserPlace> findByUserIdAndPlaceKey(Long userId, String placeKey);
    List<UserPlace> findByUserIdAndFavoriteTrueOrderBySavedAtDesc(Long userId);
    List<UserPlace> findTop50ByUserIdAndLastViewedAtIsNotNullOrderByLastViewedAtDesc(Long userId);
}
