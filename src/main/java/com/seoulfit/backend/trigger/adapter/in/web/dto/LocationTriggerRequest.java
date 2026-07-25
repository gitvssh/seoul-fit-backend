package com.seoulfit.backend.trigger.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 위치 기반 트리거 요청 DTO
 * 
 * 사용자의 위치 정보를 기반으로 트리거를 평가하기 위한 요청 객체
 * 
 * @author Seoul Fit
 * @since 1.0.0
 */
@Schema(description = "위치 기반 트리거 평가 요청")
@Getter
@NoArgsConstructor
public class LocationTriggerRequest {

    @Schema(description = "위도", example = "37.5665", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "위도는 필수입니다.")
    @DecimalMin(value = "37.3", message = "위도는 서울 서비스 범위(37.3~37.8) 안이어야 합니다.")
    @DecimalMax(value = "37.8", message = "위도는 서울 서비스 범위(37.3~37.8) 안이어야 합니다.")
    private Double latitude;

    @Schema(description = "경도", example = "126.9780", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "경도는 필수입니다.")
    @DecimalMin(value = "126.6", message = "경도는 서울 서비스 범위(126.6~127.3) 안이어야 합니다.")
    @DecimalMax(value = "127.3", message = "경도는 서울 서비스 범위(126.6~127.3) 안이어야 합니다.")
    private Double longitude;

    @Schema(description = "검색 반경 (미터)", example = "2000", requiredMode = Schema.RequiredMode.REQUIRED)
    @Min(value = 100, message = "반경은 100m 이상이어야 합니다.")
    @Max(value = 20000, message = "반경은 20km 이하여야 합니다.")
    private Integer radius = 2000; // 기본값: 2km

    @Schema(description = "평가할 트리거 타입 목록 (비어있으면 모든 타입)",
            example = "[\"TEMPERATURE\", \"AIR_QUALITY\", \"BIKE_SHARE\"]")
    private List<String> triggerTypes;

    public LocationTriggerRequest(Double latitude, Double longitude, Integer radius,
                                List<String> triggerTypes) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.radius = radius != null ? radius : 2000;
        this.triggerTypes = triggerTypes;
    }
}
