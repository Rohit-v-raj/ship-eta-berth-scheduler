package com.port.scheduler.repository;

import com.port.scheduler.entity.vessel.Vessel;
import com.port.scheduler.entity.visit.AllocationStatus;
import com.port.scheduler.entity.visit.VesselVisit;
import com.port.scheduler.entity.visit.VisitPhase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VesselVisitRepository extends JpaRepository<VesselVisit, Long> {

    /** The vessel's open visit, if it has one. Pass the closed phases to exclude. */
    Optional<VesselVisit> findByVesselAndPhaseNotIn(Vessel vessel, Collection<VisitPhase> closed);

    /**
     * The virtual queue: ids of open visits that hold no berth, oldest first.
     * A visit's queue number is its position in this list.
     */
    @Query("""
            select v.id from VesselVisit v
            where v.phase not in :closed
              and not exists (select 1 from BerthAllocation a
                              where a.visit = v and a.status in :open)
            order by v.id
            """)
    List<Long> findWaitingIds(@Param("closed") Collection<VisitPhase> closed,
                              @Param("open") Collection<AllocationStatus> open);
}
