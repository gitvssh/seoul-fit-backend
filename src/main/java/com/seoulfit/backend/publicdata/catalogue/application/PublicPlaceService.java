package com.seoulfit.backend.publicdata.catalogue.application;

import com.seoulfit.backend.publicdata.catalogue.adapter.in.web.dto.PublicPlacePageResponse;
import com.seoulfit.backend.publicdata.catalogue.adapter.in.web.dto.PublicPlaceResponse;
import com.seoulfit.backend.publicdata.catalogue.adapter.in.web.dto.PublicPlaceSitemapEntry;
import com.seoulfit.backend.publicdata.catalogue.adapter.in.web.dto.PublicPlaceSummaryResponse;
import com.seoulfit.backend.publicdata.catalogue.domain.PublicPlaceCategory;
import com.seoulfit.backend.publicdata.culture.domain.CulturalEvent;
import com.seoulfit.backend.publicdata.culture.domain.CulturalReservation;
import com.seoulfit.backend.publicdata.facilities.domain.CoolingCenter;
import com.seoulfit.backend.publicdata.facilities.domain.Library;
import com.seoulfit.backend.publicdata.park.domain.Park;
import com.seoulfit.backend.publicdata.restaurant.domain.Restaurant;
import com.seoulfit.backend.search.application.port.out.PublicDataRepository;
import com.seoulfit.backend.search.application.port.out.SearchIndexRepository;
import com.seoulfit.backend.search.domain.PoiSearchIndex;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Read-only SEO/public catalogue boundary over the existing POI search index.
 * Public URLs use a source record ID, never the rebuilt POI index ID.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicPlaceService {

    private static final int MAX_PAGE_SIZE = 100;

    private final SearchIndexRepository searchIndexRepository;
    private final PublicDataRepository publicDataRepository;

    public PublicPlacePageResponse list(String categorySlug, int page, int size) {
        PublicPlaceCategory category = PublicPlaceCategory.fromSlug(categorySlug);
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.ASC, "name")
        );
        Page<PoiSearchIndex> places = searchIndexRepository.findByRefTable(category.getRefTable(), pageable);

        List<PublicPlaceSummaryResponse> content = places.getContent().stream()
                .map(index -> toSummary(category, index))
                .toList();

        return new PublicPlacePageResponse(
                content,
                places.getNumber(),
                places.getSize(),
                places.getTotalElements(),
                places.getTotalPages(),
                places.hasNext()
        );
    }

    public Optional<PublicPlaceResponse> find(String categorySlug, Long sourceId) {
        PublicPlaceCategory category = PublicPlaceCategory.fromSlug(categorySlug);
        return searchIndexRepository.findByRefTableAndRefId(category.getRefTable(), sourceId)
                .map(index -> sourceFor(category, sourceId)
                        .map(source -> toResponse(category, index, source))
                        .orElseGet(() -> fallbackResponse(category, index)));
    }

    /**
     * Only categories with stable, evergreen content are published in the
     * sitemap. Event and seasonal categories remain browseable but require a
     * later editorial freshness policy before automatic indexing.
     */
    public List<PublicPlaceSitemapEntry> sitemapEntries(String categorySlug) {
        PublicPlaceCategory category = PublicPlaceCategory.fromSlug(categorySlug);
        if (!category.isIncludedInSitemap()) {
            return List.of();
        }
        return searchIndexRepository.findAllByRefTable(category.getRefTable()).stream()
                .map(index -> new PublicPlaceSitemapEntry(index.getRefId(), index.getUpdatedAt()))
                .toList();
    }

    private PublicPlaceSummaryResponse toSummary(PublicPlaceCategory category, PoiSearchIndex index) {
        return new PublicPlaceSummaryResponse(
                index.getRefId(),
                category.getSlug(),
                category.getDisplayName(),
                index.getName(),
                index.getAddress(),
                index.getRemark()
        );
    }

    private PublicPlaceResponse fallbackResponse(PublicPlaceCategory category, PoiSearchIndex index) {
        return response(
                category,
                index.getRefId(),
                index.getName(),
                index.getAddress(),
                index.getRemark(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                index.getUpdatedAt()
        );
    }

    private Optional<Object> sourceFor(PublicPlaceCategory category, Long sourceId) {
        return switch (category) {
            case PARK -> publicDataRepository.findParkById(sourceId);
            case LIBRARY -> publicDataRepository.findLibraryById(sourceId);
            case RESTAURANT -> publicDataRepository.findRestaurantById(sourceId);
            case CULTURAL_EVENT -> publicDataRepository.findCulturalEventById(sourceId);
            case CULTURAL_RESERVATION -> publicDataRepository.findCulturalReservationById(sourceId);
            case COOLING_CENTER -> publicDataRepository.findCoolingCenterById(sourceId);
        };
    }

    private PublicPlaceResponse toResponse(PublicPlaceCategory category, PoiSearchIndex index, Object source) {
        if (source instanceof Park park) {
            return response(category, index.getRefId(), park.getName(), park.getAddress(), park.getContent(),
                    park.getAdminTel(), park.getTemplateUrl(), null, park.getImageUrl(), park.getLatitude(),
                    park.getLongitude(), park.getZone(), null, null, false, park.getUpdatedAt());
        }
        if (source instanceof Library library) {
            String description = joinNonBlank(library.getLbrrySeName(), library.getFdrmCloseDate());
            return response(category, index.getRefId(), library.getLbrryName(), library.getAdres(), description,
                    library.getTelNo(), library.getHmpgUrl(), library.getOpTime(), null, library.getXcnts(),
                    library.getYdnts(), library.getCodeValue(), null, null, false, library.getUpdatedAt());
        }
        if (source instanceof Restaurant restaurant) {
            String description = joinNonBlank(restaurant.getRepresentativeMenu(), restaurant.getSubwayInfo());
            return response(category, index.getRefId(), restaurant.getName(), firstNonBlank(restaurant.getNewAddress(), restaurant.getAddress()),
                    description, restaurant.getPhone(), firstNonBlank(restaurant.getWebsite(), restaurant.getPostUrl()),
                    restaurant.getOperatingHours(), null, restaurant.getLatitude(), restaurant.getLongitude(), null,
                    null, null, false, restaurant.getUpdatedAt());
        }
        if (source instanceof CulturalEvent event) {
            return response(category, index.getRefId(), event.getTitle(), event.getPlace(),
                    firstNonBlank(event.getProgram(), event.getEtcDesc()), null,
                    firstNonBlank(event.getHomepageAddr(), event.getOrgLink()), event.getEventDate(), event.getMainImg(),
                    toDouble(event.getLatitude()), toDouble(event.getLongitude()), event.getDistrict(),
                    formatDate(event.getStartDate()), formatDate(event.getEndDate()), false, event.getUpdatedAt());
        }
        if (source instanceof CulturalReservation reservation) {
            return response(category, index.getRefId(), reservation.getSvcNm(), reservation.getPlaceNm(),
                    reservation.getDtlCont(), reservation.getTelNo(), reservation.getSvcUrl(),
                    joinNonBlank(reservation.getVMin(), reservation.getVMax()), reservation.getImgUrl(),
                    parseCoordinate(reservation.getY()), parseCoordinate(reservation.getX()), reservation.getAreaNm(),
                    formatDateTime(reservation.getSvcOpnBgnDt()), formatDateTime(reservation.getSvcOpnEndDt()), true, null);
        }
        if (source instanceof CoolingCenter center) {
            return response(category, index.getRefId(), center.getName(), firstNonBlank(center.getRoadAddress(), center.getLotAddress()),
                    joinNonBlank(center.getFacilityType1(), center.getRemarks()), null, null, null, null,
                    center.getLatitude(), center.getLongitude(), center.getAreaCode(), null, null, false, center.getUpdatedAt());
        }
        throw new IllegalStateException("지원하지 않는 공개 장소 데이터 유형입니다.");
    }

    @SuppressWarnings("java:S107") // One response projection intentionally contains every public place attribute.
    private PublicPlaceResponse response(
            PublicPlaceCategory category,
            Long id,
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
        return new PublicPlaceResponse(id, category.getSlug(), category.getDisplayName(), name, address,
                description, phone, website, openingHours, imageUrl, latitude, longitude, district,
                eventStart, eventEnd, reservable, lastModified);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String joinNonBlank(String first, String second) {
        if (first == null || first.isBlank()) {
            return second;
        }
        if (second == null || second.isBlank()) {
            return first;
        }
        return first + " · " + second;
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private static Double parseCoordinate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String formatDate(LocalDate value) {
        return value == null ? null : value.toString();
    }

    private static String formatDateTime(LocalDateTime value) {
        return value == null ? null : value.toString();
    }
}
