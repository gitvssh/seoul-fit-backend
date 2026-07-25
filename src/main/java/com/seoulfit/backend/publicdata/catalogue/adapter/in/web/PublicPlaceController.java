package com.seoulfit.backend.publicdata.catalogue.adapter.in.web;

import com.seoulfit.backend.publicdata.catalogue.adapter.in.web.dto.PublicPlacePageResponse;
import com.seoulfit.backend.publicdata.catalogue.adapter.in.web.dto.PublicPlaceResponse;
import com.seoulfit.backend.publicdata.catalogue.adapter.in.web.dto.PublicPlaceSitemapEntry;
import com.seoulfit.backend.publicdata.catalogue.application.PublicPlaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Public, anonymous read API used by server-rendered place pages and sitemaps. */
@RestController
@RequestMapping("/api/public/places")
@RequiredArgsConstructor
public class PublicPlaceController {

    private static final CacheControl PUBLIC_CACHE = CacheControl.maxAge(5, TimeUnit.MINUTES)
            .sMaxAge(1, TimeUnit.HOURS)
            .staleWhileRevalidate(1, TimeUnit.DAYS)
            .cachePublic();

    private final PublicPlaceService publicPlaceService;

    @GetMapping
    public ResponseEntity<PublicPlacePageResponse> list(
            @RequestParam String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "24") int size
    ) {
        return cacheable(publicPlaceService.list(category, page, size));
    }

    @GetMapping("/sitemap")
    public ResponseEntity<List<PublicPlaceSitemapEntry>> sitemap(@RequestParam String category) {
        return cacheable(publicPlaceService.sitemapEntries(category));
    }

    @GetMapping("/{category}/{id}")
    public ResponseEntity<PublicPlaceResponse> detail(
            @PathVariable String category,
            @PathVariable Long id
    ) {
        return publicPlaceService.find(category, id)
                .map(this::cacheable)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Void> handleBadRequest(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
    }

    private <T> ResponseEntity<T> cacheable(T body) {
        return ResponseEntity.ok().cacheControl(PUBLIC_CACHE).body(body);
    }
}
