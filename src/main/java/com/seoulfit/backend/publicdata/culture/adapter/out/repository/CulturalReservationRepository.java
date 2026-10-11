package com.seoulfit.backend.publicdata.culture.adapter.out.repository;

import com.seoulfit.backend.publicdata.culture.domain.CulturalReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CulturalReservationRepository extends JpaRepository<CulturalReservation, Long> {

    @Query(value = """
            SELECT * FROM cultural_reservation cr
            WHERE cr.x_coord IS NOT NULL AND cr.y_coord IS NOT NULL
              AND cr.x_coord <> '' AND cr.y_coord <> ''
              AND (6371 * acos(least(1.0, greatest(-1.0,
                    cos(radians(:latitude)) * cos(radians(CAST(cr.y_coord AS double precision)))
                    * cos(radians(CAST(cr.x_coord AS double precision)) - radians(:longitude))
                    + sin(radians(:latitude)) * sin(radians(CAST(cr.y_coord AS double precision))))))) <= :radiusKm
            ORDER BY (6371 * acos(least(1.0, greatest(-1.0,
                    cos(radians(:latitude)) * cos(radians(CAST(cr.y_coord AS double precision)))
                    * cos(radians(CAST(cr.x_coord AS double precision)) - radians(:longitude))
                    + sin(radians(:latitude)) * sin(radians(CAST(cr.y_coord AS double precision)))))))
            """, nativeQuery = true)
    List<CulturalReservation> findWithInRadius(@Param("latitude") double latitude,
                                               @Param("longitude") double longitude,
                                               @Param("radiusKm") double radiusKm);

}
