package com.seoulfit.backend.engagement.infrastructure;

import com.seoulfit.backend.engagement.domain.SavedZone;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SavedZoneRepository extends JpaRepository<SavedZone, Long> {
    List<SavedZone> findByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<SavedZone> findByIdAndUserId(Long id, Long userId);
    boolean existsByUserIdAndLabel(Long userId, String label);
    boolean existsByUserIdAndLabelAndIdNot(Long userId, String label, Long id);
}
