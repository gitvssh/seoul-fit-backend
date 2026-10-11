package com.seoulfit.backend.publicdata.culture.adapter.out.query;

import com.seoulfit.backend.publicdata.culture.adapter.out.repository.CulturalReservationRepository;
import com.seoulfit.backend.publicdata.culture.application.port.out.query.QueryCulturalReservationPort;
import com.seoulfit.backend.publicdata.culture.domain.CulturalReservation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

@RequiredArgsConstructor
@Repository
public class QueryCulturalReservationAdapter implements QueryCulturalReservationPort {
    private final CulturalReservationRepository culturalReservationRepository;

    @Override
    public List<CulturalReservation> getAllCulturalReservation() {
        return culturalReservationRepository.findAll();
    }

    @Override
    public List<CulturalReservation> getCulturalReservationLocation(String latitude, String longitude) {
        double lat = parseCoordinate(latitude);
        double lng = parseCoordinate(longitude);
        return culturalReservationRepository.findWithInRadius(lat, lng, 2.0);
    }

    private static double parseCoordinate(String value) {
        if (value == null) {
            throw new IllegalArgumentException("latitude/longitude must be decimal degrees");
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("latitude/longitude must be decimal degrees");
        }
    }
}
