package com.seoulfit.backend.publicdata.catalogue.adapter.in.web.dto;

import java.time.LocalDateTime;

/**
 * Normalized public-detail payload. It intentionally excludes internal IDs,
 * user information, and raw provider payloads.
 */
public record PublicPlaceResponse(
        Long id,
        String category,
        String categoryLabel,
        String name,
        String address,
        String description,
        String phone,
        String website,
        String openingHours,
        String imageUrl,
        Double latitude,
        Double longitude,
        String district,
        String eventStart,
        String eventEnd,
        Boolean reservable,
        LocalDateTime lastModified
) {
}
