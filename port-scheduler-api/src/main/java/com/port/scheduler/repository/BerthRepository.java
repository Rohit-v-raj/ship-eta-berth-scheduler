package com.port.scheduler.repository;

import com.port.scheduler.entity.topology.Berth;
import com.port.scheduler.entity.topology.OperationalStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BerthRepository extends JpaRepository<Berth, Long> {

    Optional<Berth> findByCode(String code);

    List<Berth> findAllByOrderBySeqAsc();

    /** Every berth with its quay, terminal and port loaded, in the order berths are offered. */
    @Query("""
            select b from Berth b
            join fetch b.quay q
            join fetch q.terminal t
            join fetch t.port
            order by b.seq
            """)
    List<Berth> findAllWithTopology();

    /**
     * Berths in service, in the order they are offered, with their quay and
     * terminal loaded. The rows are locked for the rest of the transaction, so
     * two changes to the plan arriving together are worked out one after the
     * other and cannot both give a berth away.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select b from Berth b
            join fetch b.quay q
            join fetch q.terminal
            where b.status = :inService
            order by b.seq
            """)
    List<Berth> findInServiceForUpdate(@Param("inService") OperationalStatus inService);

    /** One berth, locked as {@link #findInServiceForUpdate} locks them. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Berth b where b.code = :code")
    Optional<Berth> findByCodeForUpdate(@Param("code") String code);
}
