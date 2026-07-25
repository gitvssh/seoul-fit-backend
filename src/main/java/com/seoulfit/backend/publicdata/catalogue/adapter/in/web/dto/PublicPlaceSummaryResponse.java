package com.seoulfit.backend.publicdata.catalogue.adapter.in.web.dto;

/** Lightweight list representation for public, indexable place pages. */
public record PublicPlaceSummaryResponse(
        Long id,
        String category,
        String categoryLabel,
        String name,
        String address,
        String description
) {
}
