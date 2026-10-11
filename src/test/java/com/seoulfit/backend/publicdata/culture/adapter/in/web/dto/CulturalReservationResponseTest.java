package com.seoulfit.backend.publicdata.culture.adapter.in.web.dto;

import com.seoulfit.backend.publicdata.culture.domain.CulturalReservation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CulturalReservationResponse 변환 테스트")
class CulturalReservationResponseTest {

    @Test
    @DisplayName("from은 문자열 x/y를 유지하고 latitude/longitude를 파싱한다")
    void from_parsesLatitudeAndLongitude() {
        CulturalReservation reservation = CulturalReservation.builder()
                .svcId("SVC-1")
                .x("126.9780")
                .y("37.5665")
                .build();

        CulturalReservationResponse response = CulturalReservationResponse.from(reservation);

        assertThat(response.getX()).isEqualTo("126.9780");
        assertThat(response.getY()).isEqualTo("37.5665");
        assertThat(response.getLatitude()).isEqualTo(37.5665);
        assertThat(response.getLongitude()).isEqualTo(126.978);
    }

    @Test
    @DisplayName("빈 x/y는 latitude/longitude null이고 원본 문자열은 유지된다")
    void from_blankCoordinates_yieldNullParsedFields() {
        CulturalReservation reservation = CulturalReservation.builder()
                .svcId("SVC-2")
                .x("")
                .y("")
                .build();

        CulturalReservationResponse response = CulturalReservationResponse.from(reservation);

        assertThat(response.getX()).isEmpty();
        assertThat(response.getY()).isEmpty();
        assertThat(response.getLatitude()).isNull();
        assertThat(response.getLongitude()).isNull();
    }
}
