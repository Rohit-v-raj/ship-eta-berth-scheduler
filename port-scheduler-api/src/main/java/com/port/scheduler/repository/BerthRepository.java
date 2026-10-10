package com.port.scheduler.repository;

import com.port.scheduler.entity.topology.Berth;
import com.port.scheduler.entity.topology.OperationalStatus;
import com.port.scheduler.entity.visit.AllocationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BerthRepository extends JpaRepository<Berth, Long> {

    Optional<Berth> findByCode(String code);

    List<Berth> findAllByOrderBySeqAsc();

    /**
     * Berths in service with no open allocation, in the order they are offered.
     * The rows are locked for the rest of the transaction so two concurrent
     * requests cannot both pick the same berth.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select b from Berth b
            where b.status = :inService
              and not exists (select 1 from BerthAllocation a
                              where a.berth = b and a.status in :open)
            order by b.seq
            """)
    List<Berth> findFreeForUpdate(@Param("inService") OperationalStatus inService,
                                  @Param("open") Collection<AllocationStatus> open);
}
