package com.seoulfit.backend.publicdata.catalogue.adapter.in.web;

import com.seoulfit.backend.config.TestSecurityConfig;
import com.seoulfit.backend.publicdata.catalogue.adapter.in.web.dto.PublicPlacePageResponse;
import com.seoulfit.backend.publicdata.catalogue.adapter.in.web.dto.PublicPlaceResponse;
import com.seoulfit.backend.publicdata.catalogue.adapter.in.web.dto.PublicPlaceSitemapEntry;
import com.seoulfit.backend.publicdata.catalogue.adapter.in.web.dto.PublicPlaceSummaryResponse;
import com.seoulfit.backend.publicdata.catalogue.application.PublicPlaceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PublicPlaceController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(TestSecurityConfig.class)
@DisplayName("공개 장소 카탈로그 컨트롤러")
class PublicPlaceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicPlaceService publicPlaceService;

    @Test
    @DisplayName("공개 목록은 CDN 재검증용 Cache-Control을 반환한다")
    void listReturnsCacheControl() throws Exception {
        given(publicPlaceService.list("park", 0, 24)).willReturn(new PublicPlacePageResponse(
                List.of(new PublicPlaceSummaryResponse(42L, "park", "공원", "서울숲", "서울 성동구", null)),
                0, 24, 1, 1, false
        ));

        mockMvc.perform(get("/api/public/places").param("category", "park"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("public")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("s-maxage=3600")))
                .andExpect(jsonPath("$.content[0].id").value(42));
    }

    @Test
    @DisplayName("지원하지 않는 카테고리는 400을 반환한다")
    void invalidCategoryReturnsBadRequest() throws Exception {
        given(publicPlaceService.list("unknown", 0, 24))
                .willThrow(new IllegalArgumentException("unsupported"));

        mockMvc.perform(get("/api/public/places").param("category", "unknown"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("공개 sitemap과 상세 URL은 캐시 헤더를 유지하고 없는 상세는 404를 반환한다")
    void returnsSitemapAndDetailResponses() throws Exception {
        given(publicPlaceService.sitemapEntries("park"))
                .willReturn(List.of(new PublicPlaceSitemapEntry(42L, LocalDateTime.of(2026, Month.JULY, 26, 9, 0))));
        given(publicPlaceService.find("park", 42L)).willReturn(Optional.of(new PublicPlaceResponse(
                42L, "park", "공원", "서울숲", "서울 성동구", null, null, null,
                null, null, null, null, null, null, null, false, null)));
        given(publicPlaceService.find("park", 999L)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/public/places/sitemap").param("category", "park"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("public")))
                .andExpect(jsonPath("$[0].id").value(42));
        mockMvc.perform(get("/api/public/places/park/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("서울숲"));
        mockMvc.perform(get("/api/public/places/park/999"))
                .andExpect(status().isNotFound());
    }
}
