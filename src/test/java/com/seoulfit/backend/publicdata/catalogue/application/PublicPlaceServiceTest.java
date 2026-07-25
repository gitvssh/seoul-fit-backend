package com.seoulfit.backend.publicdata.catalogue.application;

import com.seoulfit.backend.publicdata.catalogue.adapter.in.web.dto.PublicPlacePageResponse;
import com.seoulfit.backend.publicdata.catalogue.adapter.in.web.dto.PublicPlaceResponse;
import com.seoulfit.backend.publicdata.catalogue.adapter.in.web.dto.PublicPlaceSitemapEntry;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
}
