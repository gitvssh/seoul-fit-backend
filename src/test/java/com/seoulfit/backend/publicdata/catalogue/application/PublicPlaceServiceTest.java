package com.seoulfit.backend.publicdata.catalogue.application;

import com.seoulfit.backend.publicdata.catalogue.adapter.in.web.dto.PublicPlacePageResponse;
import com.seoulfit.backend.publicdata.catalogue.adapter.in.web.dto.PublicPlaceResponse;
import com.seoulfit.backend.publicdata.catalogue.adapter.in.web.dto.PublicPlaceSitemapEntry;
import com.seoulfit.backend.publicdata.culture.domain.CulturalEvent;
import com.seoulfit.backend.publicdata.culture.domain.CulturalReservation;
import com.seoulfit.backend.publicdata.facilities.domain.CoolingCenter;
import com.seoulfit.backend.publicdata.facilities.domain.Library;
import com.seoulfit.backend.publicdata.park.domain.Park;
import com.seoulfit.backend.publicdata.restaurant.domain.Restaurant;
import com.seoulfit.backend.search.application.port.out.PublicDataRepository;
import com.seoulfit.backend.search.application.port.out.SearchIndexRepository;
import com.seoulfit.backend.search.domain.PoiSearchIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("공개 장소 카탈로그 서비스")
class PublicPlaceServiceTest {

    @Mock
    private SearchIndexRepository searchIndexRepository;

    @Mock
    private PublicDataRepository publicDataRepository;

    @InjectMocks
    private PublicPlaceService publicPlaceService;

    @Test
    @DisplayName("목록은 재생성되는 POI 인덱스 ID가 아닌 원본 refId를 공개한다")
    void listPublishesStableSourceId() {
        PoiSearchIndex index = new PoiSearchIndex(
                "서울숲", "서울 성동구", "공원", "서울숲", "park", 42L
        );
        when(searchIndexRepository.findByRefTable(eq("park"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(index)));

        PublicPlacePageResponse response = publicPlaceService.list("park", 0, 24);

        assertThat(response.content()).singleElement().satisfies(place -> {
            assertThat(place.id()).isEqualTo(42L);
            assertThat(place.category()).isEqualTo("park");
            assertThat(place.name()).isEqualTo("서울숲");
        });
    }

    @Test
    @DisplayName("계절성·이벤트 카테고리는 자동 sitemap에서 제외한다")
    void sitemapExcludesNonEvergreenCategory() {
        List<PublicPlaceSitemapEntry> entries = publicPlaceService.sitemapEntries("cultural-event");

        assertThat(entries).isEmpty();
        verify(searchIndexRepository, never()).findAllByRefTable("cultural_events");
    }

    @Test
    @DisplayName("상시 콘텐츠 sitemap은 원본 refId 목록을 반환한다")
    void sitemapUsesSourceIdsForEvergreenContent() {
        when(searchIndexRepository.findAllByRefTable("libraries")).thenReturn(List.of(
                new PoiSearchIndex("서울도서관", "서울 중구", "", "", "libraries", 101L)
        ));

        List<PublicPlaceSitemapEntry> entries = publicPlaceService.sitemapEntries("library");

        assertThat(entries).extracting(PublicPlaceSitemapEntry::id).containsExactly(101L);
    }

    @Test
    @DisplayName("원본 레코드가 정리돼도 검색 인덱스 상세를 공개해 sitemap URL을 유지한다")
    void findFallsBackToSearchIndexWhenSourceRecordIsMissing() {
        PoiSearchIndex index = new PoiSearchIndex(
                "서울숲", "서울 성동구", "공원 산책", "", "park", 42L
        );
        when(searchIndexRepository.findByRefTableAndRefId("park", 42L)).thenReturn(Optional.of(index));
        when(publicDataRepository.findParkById(42L)).thenReturn(Optional.empty());

        Optional<PublicPlaceResponse> response = publicPlaceService.find("park", 42L);

        assertThat(response).hasValueSatisfying(place -> {
            assertThat(place.id()).isEqualTo(42L);
            assertThat(place.name()).isEqualTo("서울숲");
            assertThat(place.address()).isEqualTo("서울 성동구");
            assertThat(place.description()).isEqualTo("공원 산책");
        });
    }

    @Test
    @DisplayName("원본 유형별 상세 정보를 공개 모델로 투영한다")
    void findMapsEverySupportedSourceType() {
        Park park = mock(Park.class);
        when(park.getName()).thenReturn("서울숲");
        when(park.getAddress()).thenReturn("성동구");
        when(park.getContent()).thenReturn("공원");
        when(park.getAdminTel()).thenReturn("02-1");
        when(park.getTemplateUrl()).thenReturn("https://park.example");
        when(park.getImageUrl()).thenReturn("https://image.example/park");
        when(park.getLatitude()).thenReturn(37.5);
        when(park.getLongitude()).thenReturn(127.0);
        when(park.getZone()).thenReturn("성동구");
        when(park.getUpdatedAt()).thenReturn(LocalDateTime.of(2026, Month.JULY, 26, 9, 0));
        stubIndex("park", 1L);
        when(publicDataRepository.findParkById(1L)).thenReturn(Optional.of((Object) park));

        Library library = mock(Library.class);
        when(library.getLbrryName()).thenReturn("서울도서관");
        when(library.getAdres()).thenReturn("중구");
        when(library.getLbrrySeName()).thenReturn("공공도서관");
        when(library.getFdrmCloseDate()).thenReturn("월요일");
        when(library.getTelNo()).thenReturn("02-2");
        when(library.getHmpgUrl()).thenReturn("https://library.example");
        when(library.getOpTime()).thenReturn("09:00");
        when(library.getXcnts()).thenReturn(37.56);
        when(library.getYdnts()).thenReturn(126.98);
        when(library.getCodeValue()).thenReturn("중구");
        when(library.getUpdatedAt()).thenReturn(LocalDateTime.of(2026, Month.JULY, 26, 9, 0));
        stubIndex("libraries", 2L);
        when(publicDataRepository.findLibraryById(2L)).thenReturn(Optional.of((Object) library));

        Restaurant restaurant = mock(Restaurant.class);
        when(restaurant.getName()).thenReturn("맛집");
        when(restaurant.getNewAddress()).thenReturn("강남 신주소");
        when(restaurant.getAddress()).thenReturn("강남 구주소");
        when(restaurant.getRepresentativeMenu()).thenReturn("비빔밥");
        when(restaurant.getSubwayInfo()).thenReturn("2호선");
        when(restaurant.getPhone()).thenReturn("02-3");
        when(restaurant.getWebsite()).thenReturn("https://restaurant.example");
        when(restaurant.getPostUrl()).thenReturn("https://post.example");
        when(restaurant.getOperatingHours()).thenReturn("11:00");
        when(restaurant.getLatitude()).thenReturn(37.5);
        when(restaurant.getLongitude()).thenReturn(127.1);
        when(restaurant.getUpdatedAt()).thenReturn(LocalDateTime.of(2026, Month.JULY, 26, 9, 0));
        stubIndex("restaurants", 3L);
        when(publicDataRepository.findRestaurantById(3L)).thenReturn(Optional.of((Object) restaurant));

        CulturalEvent event = mock(CulturalEvent.class);
        when(event.getTitle()).thenReturn("전시");
        when(event.getPlace()).thenReturn("미술관");
        when(event.getProgram()).thenReturn("프로그램");
        when(event.getEtcDesc()).thenReturn("기타");
        when(event.getHomepageAddr()).thenReturn("https://event.example");
        when(event.getOrgLink()).thenReturn("https://organization.example");
        when(event.getEventDate()).thenReturn("2026.07.26");
        when(event.getMainImg()).thenReturn("https://image.example/event");
        when(event.getLatitude()).thenReturn(new BigDecimal("37.51"));
        when(event.getLongitude()).thenReturn(new BigDecimal("127.01"));
        when(event.getDistrict()).thenReturn("종로구");
        when(event.getStartDate()).thenReturn(LocalDate.of(2026, Month.JULY, 26));
        when(event.getEndDate()).thenReturn(LocalDate.of(2026, Month.JULY, 27));
        when(event.getUpdatedAt()).thenReturn(LocalDateTime.of(2026, Month.JULY, 26, 9, 0));
        stubIndex("cultural_events", 4L);
        when(publicDataRepository.findCulturalEventById(4L)).thenReturn(Optional.of((Object) event));

        CulturalReservation reservation = mock(CulturalReservation.class);
        when(reservation.getSvcNm()).thenReturn("예약");
        when(reservation.getPlaceNm()).thenReturn("체육관");
        when(reservation.getDtlCont()).thenReturn("상세");
        when(reservation.getTelNo()).thenReturn("02-4");
        when(reservation.getSvcUrl()).thenReturn("https://reservation.example");
        when(reservation.getVMin()).thenReturn("09:00");
        when(reservation.getVMax()).thenReturn("18:00");
        when(reservation.getImgUrl()).thenReturn("https://image.example/reservation");
        when(reservation.getY()).thenReturn("37.52");
        when(reservation.getX()).thenReturn("127.02");
        when(reservation.getAreaNm()).thenReturn("마포구");
        when(reservation.getSvcOpnBgnDt()).thenReturn(LocalDateTime.of(2026, Month.JULY, 26, 9, 0));
        when(reservation.getSvcOpnEndDt()).thenReturn(LocalDateTime.of(2026, Month.JULY, 26, 18, 0));
        stubIndex("cultural_reservation", 5L);
        when(publicDataRepository.findCulturalReservationById(5L)).thenReturn(Optional.of((Object) reservation));

        CoolingCenter center = mock(CoolingCenter.class);
        when(center.getName()).thenReturn("쉼터");
        when(center.getRoadAddress()).thenReturn("송파 도로명");
        when(center.getLotAddress()).thenReturn("송파 지번");
        when(center.getFacilityType1()).thenReturn("주민센터");
        when(center.getRemarks()).thenReturn("시원함");
        when(center.getLatitude()).thenReturn(37.53);
        when(center.getLongitude()).thenReturn(127.03);
        when(center.getAreaCode()).thenReturn("송파구");
        when(center.getUpdatedAt()).thenReturn(LocalDateTime.of(2026, Month.JULY, 26, 9, 0));
        stubIndex("cooling_centers", 6L);
        when(publicDataRepository.findCoolingCenterById(6L)).thenReturn(Optional.of((Object) center));

        assertThat(publicPlaceService.find("park", 1L)).hasValueSatisfying(place ->
                assertThat(place).extracting("name", "district").containsExactly("서울숲", "성동구"));
        assertThat(publicPlaceService.find("library", 2L)).hasValueSatisfying(place ->
                assertThat(place.description()).isEqualTo("공공도서관 · 월요일"));
        assertThat(publicPlaceService.find("restaurant", 3L)).hasValueSatisfying(place ->
                assertThat(place).extracting("address", "description").containsExactly("강남 신주소", "비빔밥 · 2호선"));
        assertThat(publicPlaceService.find("cultural-event", 4L)).hasValueSatisfying(place ->
                assertThat(place).extracting("eventStart", "eventEnd").containsExactly("2026-07-26", "2026-07-27"));
        assertThat(publicPlaceService.find("cultural-reservation", 5L)).hasValueSatisfying(place ->
                assertThat(place).extracting("latitude", "longitude", "reservable").containsExactly(37.52, 127.02, true));
        assertThat(publicPlaceService.find("cooling-center", 6L)).hasValueSatisfying(place ->
                assertThat(place).extracting("address", "description").containsExactly("송파 도로명", "주민센터 · 시원함"));
    }

    @Test
    @DisplayName("상세 원본이 없고 인덱스도 없으면 빈 결과를 반환한다")
    void findReturnsEmptyWhenIndexIsMissing() {
        when(searchIndexRepository.findByRefTableAndRefId("park", 77L)).thenReturn(Optional.empty());

        assertThat(publicPlaceService.find("park", 77L)).isEmpty();
    }

    private void stubIndex(String refTable, Long id) {
        when(searchIndexRepository.findByRefTableAndRefId(refTable, id))
                .thenReturn(Optional.of(new PoiSearchIndex("인덱스", "주소", "", "", refTable, id)));
    }
}
