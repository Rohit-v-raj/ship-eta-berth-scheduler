package com.port.scheduler;

import com.port.scheduler.entity.topology.Berth;
import com.port.scheduler.entity.vessel.Vessel;
import com.port.scheduler.entity.visit.BerthAllocation;
import com.port.scheduler.entity.visit.VesselVisit;
import com.port.scheduler.entity.visit.VisitPhase;
import com.port.scheduler.repository.BerthAllocationRepository;
import com.port.scheduler.repository.BerthRepository;
import com.port.scheduler.repository.VesselRepository;
import com.port.scheduler.repository.VesselVisitRepository;
import com.port.scheduler.support.PersistenceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The schema itself: migrations apply from empty, the entities match what they
 * create (Hibernate validates on startup, so a mismatch fails every test
 * here), and the database enforces the allocation invariants on its own.
 */
@PersistenceTest
class SchemaMigrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired BerthRepository berths;
    @Autowired VesselRepository vessels;
    @Autowired VesselVisitRepository visits;
    @Autowired BerthAllocationRepository allocations;

    @Test
    void migrationsApplyFromEmptyAndSeedTheDemoPort() {
        assertThat(jdbc.queryForList(
                "select version from flyway_schema_history where success = 1 order by installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5");
        assertThat(berths.findAllByOrderBySeqAsc()).extracting(Berth::getCode)
                .containsExactly("B1", "B2", "B3", "B4");
    }

    @Test
    void auditColumnsAreFilledOnInsert() {
        Vessel vessel = vessels.saveAndFlush(new Vessel("AUDIT-1"));

        assertThat(vessel.getId()).isNotNull();
        assertThat(vessel.getCreatedAt()).isNotNull();
        assertThat(vessel.getUpdatedAt()).isNotNull();
    }

    @Test
    void imoNumberColumnHoldsEightCharacters() {
        Vessel vessel = new Vessel("IMO-8");
        vessel.setImoNumber("12345678");

        vessels.saveAndFlush(vessel);

        assertThat(jdbc.queryForObject("select imo_number from vessel where code = 'IMO-8'", String.class))
                .isEqualTo("12345678");
    }

    @Test
    void databaseRefusesASecondOpenAllocationOnTheSameBerth() {
        Berth b1 = berths.findByCode("B1").orElseThrow();
        allocations.saveAndFlush(new BerthAllocation(openVisit("DB-1"), b1, Instant.now()));

        BerthAllocation doubleBooking = new BerthAllocation(openVisit("DB-2"), b1, Instant.now());

        assertThatThrownBy(() -> allocations.saveAndFlush(doubleBooking))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_alloc_one_open_per_berth");
    }

    @Test
    void databaseRefusesASecondOpenVisitForTheSameVessel() {
        Vessel vessel = vessels.save(new Vessel("DB-3"));
        visits.saveAndFlush(new VesselVisit("REF-A", vessel, VisitPhase.INBOUND));

        VesselVisit second = new VesselVisit("REF-B", vessel, VisitPhase.INBOUND);

        assertThatThrownBy(() -> visits.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_visit_one_open_per_vessel");
    }

    private VesselVisit openVisit(String vesselCode) {
        Vessel vessel = vessels.save(new Vessel(vesselCode));
        return visits.save(new VesselVisit("REF-" + vesselCode, vessel, VisitPhase.INBOUND));
    }
}
