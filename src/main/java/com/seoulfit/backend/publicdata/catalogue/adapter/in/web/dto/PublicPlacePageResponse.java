package com.seoulfit.backend.publicdata.catalogue.adapter.in.web.dto;

import java.util.List;

/** Page envelope for public place directory results. */
public record PublicPlacePageResponse(
        List<PublicPlaceSummaryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {
}
