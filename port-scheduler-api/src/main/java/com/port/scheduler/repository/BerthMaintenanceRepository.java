package com.port.scheduler.repository;

import com.port.scheduler.entity.topology.Berth;
import com.port.scheduler.entity.topology.BerthMaintenance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BerthMaintenanceRepository extends JpaRepository<BerthMaintenance, Long> {

    /** Windows not yet over, on every berth, earliest first, with their berths loaded. */
    @Query("""
            select m from BerthMaintenance m
            join fetch m.berth b
            where m.endsAt > :after
            order by m.startsAt, b.seq
            """)
    List<BerthMaintenance> findEndingAfter(@Param("after") Instant after);

    /** One berth's windows not yet over, earliest first. */
    List<BerthMaintenance> findByBerthAndEndsAtAfterOrderByStartsAt(Berth berth, Instant after);

    /** Windows that share any time with the period, on every berth, with their berths loaded. */
    @Query("""
            select m from BerthMaintenance m
            join fetch m.berth b
            where m.startsAt < :periodEnd and m.endsAt > :periodStart
            order by b.seq, m.startsAt
            """)
    List<BerthMaintenance> findOverlapping(@Param("periodStart") Instant periodStart,
                                           @Param("periodEnd") Instant periodEnd);

    Optional<BerthMaintenance> findByIdAndBerth(Long id, Berth berth);
}
