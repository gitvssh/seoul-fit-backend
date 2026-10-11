package com.seoulfit.backend.publicdata.culture.adapter.out.query;

import com.seoulfit.backend.publicdata.culture.adapter.out.repository.CulturalReservationRepository;
import com.seoulfit.backend.publicdata.culture.domain.CulturalReservation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("QueryCulturalReservationAdapter 단위 테스트")
class QueryCulturalReservationAdapterTest {

    @Mock
    private CulturalReservationRepository culturalReservationRepository;

    @InjectMocks
    private QueryCulturalReservationAdapter adapter;

    @Test
    @DisplayName("위도·경도 문자열을 파싱해 반경 조회에 위도, 경도 순으로 전달한다")
    void getCulturalReservationLocation_parsesAndCallsRepositoryInOrder() {
        when(culturalReservationRepository.findWithInRadius(37.5665, 126.9780, 2.0))
                .thenReturn(List.of());

        adapter.getCulturalReservationLocation("37.5665", "126.9780");

        verify(culturalReservationRepository).findWithInRadius(37.5665, 126.9780, 2.0);
    }

    @Test
    @DisplayName("잘못된 좌표 문자열이면 IllegalArgumentException을 던진다")
    void getCulturalReservationLocation_invalidCoordinate_throws() {
        assertThatThrownBy(() -> adapter.getCulturalReservationLocation("abc", "126.9780"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("latitude/longitude must be decimal degrees");
    }
}
