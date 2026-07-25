package com.seoulfit.backend.publicdata.catalogue.domain;

import java.util.Arrays;

/**
 * Stable public URL categories backed by the existing POI search index.
 *
 * <p>The slug is part of the public URL contract. The search-index table name
 * is deliberately kept internal so a future index implementation can change
 * without changing published URLs.</p>
 */
public enum PublicPlaceCategory {
    PARK("park", "park", "공원", true),
    LIBRARY("library", "libraries", "도서관", true),
    RESTAURANT("restaurant", "restaurants", "맛집", true),
    CULTURAL_EVENT("cultural-event", "cultural_events", "문화행사", false),
    CULTURAL_RESERVATION("cultural-reservation", "cultural_reservation", "문화예약", false),
    COOLING_CENTER("cooling-center", "cooling_centers", "무더위쉼터", false);

    private final String slug;
    private final String refTable;
    private final String displayName;
    private final boolean includedInSitemap;

    PublicPlaceCategory(String slug, String refTable, String displayName, boolean includedInSitemap) {
        this.slug = slug;
        this.refTable = refTable;
        this.displayName = displayName;
        this.includedInSitemap = includedInSitemap;
    }

    public String getSlug() {
        return slug;
    }

    public String getRefTable() {
        return refTable;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isIncludedInSitemap() {
        return includedInSitemap;
    }

    public static PublicPlaceCategory fromSlug(String slug) {
        return Arrays.stream(values())
                .filter(category -> category.slug.equals(slug))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("지원하지 않는 공개 장소 카테고리입니다."));
    }
}
