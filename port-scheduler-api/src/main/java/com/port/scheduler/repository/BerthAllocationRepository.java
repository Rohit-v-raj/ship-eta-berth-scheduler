package com.port.scheduler.repository;

import com.port.scheduler.entity.topology.Berth;
import com.port.scheduler.entity.visit.AllocationStatus;
import com.port.scheduler.entity.visit.BerthAllocation;
import com.port.scheduler.entity.visit.VesselVisit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BerthAllocationRepository extends JpaRepository<BerthAllocation, Long> {

    Optional<BerthAllocation> findByBerthAndStatusIn(Berth berth, Collection<AllocationStatus> statuses);

    Optional<BerthAllocation> findByVisitAndStatusIn(VesselVisit visit, Collection<AllocationStatus> statuses);

    List<BerthAllocation> findByStatusIn(Collection<AllocationStatus> statuses);

    /** The allocations of the given visits with their berths loaded, oldest first. */
    @Query("""
            select a from BerthAllocation a
            join fetch a.berth
            where a.visit in :visits
            order by a.id
            """)
    List<BerthAllocation> findWithBerthByVisitIn(@Param("visits") Collection<VesselVisit> visits);

    /**
     * Berth windows for the plan over a period: every open allocation, and the
     * completed ones that overlap the period. A cancelled allocation never
     * occupied its berth and is left out.
     */
    @Query("""
            select a from BerthAllocation a
            join fetch a.berth b
            join fetch a.visit v
            join fetch v.vessel
            where a.status in :open
               or (a.status = :completed
                   and a.actualEnd >= :periodStart
                   and coalesce(a.actualStart, a.plannedStart) <= :periodEnd)
            order by b.seq, a.id
            """)
    List<BerthAllocation> findForPlan(@Param("open") Collection<AllocationStatus> open,
                                      @Param("completed") AllocationStatus completed,
                                      @Param("periodStart") Instant periodStart,
                                      @Param("periodEnd") Instant periodEnd);
}
