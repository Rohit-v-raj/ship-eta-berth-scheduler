package com.port.scheduler.repository;

import com.port.scheduler.entity.visit.EventSource;
import com.port.scheduler.entity.visit.Milestone;
import com.port.scheduler.entity.visit.TimeClassifier;
import com.port.scheduler.entity.visit.VisitEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface VisitEventRepository extends JpaRepository<VisitEvent, Long> {

    /**
     * Events for the DCSA Port Call feed, oldest first, after the given id.
     * Every filter except the milestones is optional: pass null to skip it.
     */
    @Query("""
            select e from VisitEvent e
            join fetch e.visit v
            join fetch v.vessel s
            left join fetch e.berth
            where e.id > :after
              and e.source <> :excluded
              and e.milestone in :milestones
              and (:classifier is null or e.classifier = :classifier)
              and (:imo is null or s.imoNumber = :imo)
              and (:mmsi is null or s.mmsi = :mmsi)
              and (:name is null or s.name = :name or s.code = :name)
              and (:min is null or e.eventTime >= :min)
              and (:max is null or e.eventTime <= :max)
            order by e.id
            """)
    List<VisitEvent> findForPublishing(@Param("after") long after,
                                       @Param("excluded") EventSource excluded,
                                       @Param("milestones") Collection<Milestone> milestones,
                                       @Param("classifier") TimeClassifier classifier,
                                       @Param("imo") String imo,
                                       @Param("mmsi") String mmsi,
                                       @Param("name") String name,
                                       @Param("min") Instant min,
                                       @Param("max") Instant max,
                                       Pageable page);
}
