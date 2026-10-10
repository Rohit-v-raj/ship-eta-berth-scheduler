package com.port.scheduler.repository;

import com.port.scheduler.entity.topology.Berth;
import com.port.scheduler.entity.visit.AllocationStatus;
import com.port.scheduler.entity.visit.BerthAllocation;
import com.port.scheduler.entity.visit.VesselVisit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BerthAllocationRepository extends JpaRepository<BerthAllocation, Long> {

    Optional<BerthAllocation> findByBerthAndStatusIn(Berth berth, Collection<AllocationStatus> statuses);

    Optional<BerthAllocation> findByVisitAndStatusIn(VesselVisit visit, Collection<AllocationStatus> statuses);

    /** Allocations in the given statuses with berth, visit and vessel loaded in one query. */
    @Query("""
            select a from BerthAllocation a
            join fetch a.berth
            join fetch a.visit v
            join fetch v.vessel
            where a.status in :statuses
            """)
    List<BerthAllocation> findWithVesselByStatusIn(@Param("statuses") Collection<AllocationStatus> statuses);
}
