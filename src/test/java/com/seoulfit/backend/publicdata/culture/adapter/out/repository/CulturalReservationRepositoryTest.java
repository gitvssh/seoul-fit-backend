package com.seoulfit.backend.publicdata.culture.adapter.out.repository;

import com.seoulfit.backend.publicdata.culture.domain.CulturalReservation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@DisplayName("CulturalReservationRepository 반경 조회 테스트")
class CulturalReservationRepositoryTest {

    @Autowired
    private CulturalReservationRepository culturalReservationRepository;

    private CulturalReservation reservationA;
    private CulturalReservation reservationB;
    private CulturalReservation reservationC;

    @BeforeEach
    void setUp() {
        culturalReservationRepository.deleteAll();

        reservationA = CulturalReservation.builder()
                .svcId("SVC-A")
                .svcNm("서울시청 행사")
                .x("126.9780")
                .y("37.5665")
                .build();

        reservationB = CulturalReservation.builder()
                .svcId("SVC-B")
                .svcNm("강남역 행사")
                .x("127.0276")
                .y("37.4979")
                .build();

        reservationC = CulturalReservation.builder()
                .svcId("SVC-C")
                .svcNm("좌표 없음")
                .x("")
                .y("")
                .build();

        culturalReservationRepository.saveAll(List.of(reservationA, reservationB, reservationC));
    }

    @Test
    @DisplayName("반경 2km 이내에는 서울시청 예약만 조회된다")
    void findWithInRadius_within2km_returnsOnlyCityHall() {
        List<CulturalReservation> results = culturalReservationRepository.findWithInRadius(37.5665, 126.9780, 2.0);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getSvcId()).isEqualTo("SVC-A");
    }

    @Test
    @DisplayName("반경 20km 이내는 거리순으로 서울시청 다음 강남역이 조회된다")
    void findWithInRadius_within20km_returnsOrderedByDistance() {
        List<CulturalReservation> results = culturalReservationRepository.findWithInRadius(37.5665, 126.9780, 20.0);

        assertThat(results).hasSize(2);
        assertThat(results).extracting(CulturalReservation::getSvcId)
                .containsExactly("SVC-A", "SVC-B");
    }
}
