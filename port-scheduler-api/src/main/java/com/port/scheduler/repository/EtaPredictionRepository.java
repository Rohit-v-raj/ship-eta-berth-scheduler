package com.port.scheduler.repository;

import com.port.scheduler.entity.visit.EtaPrediction;
import com.port.scheduler.entity.visit.VesselVisit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface EtaPredictionRepository extends JpaRepository<EtaPrediction, Long> {

    List<EtaPrediction> findByVisitOrderByIdAsc(VesselVisit visit);

    /** The latest prediction of each of the given visits. Rows are only ever added, so the highest id is the latest. */
    @Query("""
            select p from EtaPrediction p
            where p.id in (select max(p2.id) from EtaPrediction p2
                           where p2.visit in :visits
                           group by p2.visit.id)
            """)
    List<EtaPrediction> findLatestByVisitIn(@Param("visits") Collection<VesselVisit> visits);
}
