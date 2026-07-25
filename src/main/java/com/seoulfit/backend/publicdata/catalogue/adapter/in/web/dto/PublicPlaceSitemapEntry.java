package com.seoulfit.backend.publicdata.catalogue.adapter.in.web.dto;

import java.time.LocalDateTime;

/** Stable ID required to generate a canonical public sitemap URL. */
public record PublicPlaceSitemapEntry(Long id, LocalDateTime lastModified) {
}
