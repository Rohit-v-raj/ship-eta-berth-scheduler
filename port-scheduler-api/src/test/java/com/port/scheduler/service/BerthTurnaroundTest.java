package com.port.scheduler.service;

import com.port.scheduler.entity.vessel.VesselType;
import com.port.scheduler.entity.visit.AllocationStatus;
import com.port.scheduler.entity.visit.BerthAllocation;
import com.port.scheduler.entity.visit.TimeClassifier;
import com.port.scheduler.entity.visit.VesselVisit;
import com.port.scheduler.entity.visit.VisitEvent;
import com.port.scheduler.repository.BerthAllocationRepository;
import com.port.scheduler.repository.VesselVisitRepository;
import com.port.scheduler.repository.VisitEventRepository;
import com.port.scheduler.service.VesselVisitService.ReportedPosition;
import com.port.scheduler.service.VesselVisitService.Scheduled;
import com.port.scheduler.support.PersistenceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The turnaround between one vessel leaving a berth and the next arriving
 * (unmooring, mooring, clearance), set here to 60 minutes as in production.
 * The other scheduling tests run without it, so their times stay plain sums.
 */
@PersistenceTest
@TestPropertySource(properties = "port.planning.berth-turnaround-minutes=60")
class BerthTurnaroundTest {

    private static final Duration TURNAROUND = Duration.ofMinutes(60);
    private static final Duration STAY = PlanningRules.typicalStay(VesselType.OTHER);
    private static final ReportedPosition POSITION = new ReportedPosition(61.50, 4.90, 8.5);

    @Autowired VesselVisitService visitService;
    @Autowired PortViewService views;
    @Autowired VesselVisitRepository visits;
    @Autowired BerthAllocationRepository allocations;
    @Autowired VisitEventRepository events;

    @Test
    void aWaitingShipIsAskedForTheBerthsPlannedEndPlusTheTurnaround() {
        Instant before = Instant.now();
        scheduledIn("V001", Duration.ofHours(2));
        scheduledIn("V002", Duration.ofHours(5));
        scheduledIn("V003", Duration.ofHours(8));
        scheduledIn("V004", Duration.ofHours(11));

        Scheduled waiting = scheduledIn("V005", Duration.ofHours(1));

        // B1 is planned free one stay after V001's estimate, and taken again an hour after that
        Instant v001Leaves = visitOf("V001").getEtaBerth().plus(STAY);
        assertThat(waiting.requestedArrival()).isCloseTo(before.plus(Duration.ofHours(2)).plus(STAY).plus(TURNAROUND),
                within(30, ChronoUnit.SECONDS));
        assertThat(waiting.requestedArrival()).isEqualTo(v001Leaves.plus(TURNAROUND));
        // the remark names when the berth is expected free; the request is the turnaround after it
        assertThat(lastRequestTo("V005").getRemarks()).isEqualTo("Berth expected free " + v001Leaves);
    }

    @Test
    void afterADepartureTheNextShipIsBookedNoEarlierThanTheTurnaround() {
        scheduledIn("V001", Duration.ofHours(-2));
        visitService.arrived("V001", Instant.now().minus(Duration.ofHours(2)));
        List.of("V002", "V003", "V004").forEach(v -> scheduledIn(v, Duration.ofHours(2)));
        scheduledIn("V005", Duration.ofMinutes(10));   // waiting, and could be alongside in ten minutes
        Instant left = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        visitService.departed("V001", left);

        assertThat(holderOf("B1")).isEqualTo("V005");
        BerthAllocation booking = allocations.findByVisitAndStatusIn(visitOf("V005"), AllocationStatus.OPEN).orElseThrow();
        assertThat(booking.getPlannedStart()).isEqualTo(left.plus(TURNAROUND));
        assertThat(visitOf("V005").getRtaBerth()).isEqualTo(left.plus(TURNAROUND));
    }

    private Scheduled scheduledIn(String vesselId, Duration sailing) {
        visitService.resolveVessel(null, null, vesselId, null);
        return visitService.declareEta(vesselId, Instant.now().plus(sailing), POSITION);
    }

    private VisitEvent lastRequestTo(String vesselId) {
        return events.findLedger(visitOf(vesselId)).stream()
                .filter(e -> e.getClassifier() == TimeClassifier.REQUESTED)
                .reduce((first, second) -> second).orElseThrow();
    }

    private VesselVisit visitOf(String vesselId) {
        return visits.findAll().stream()
                .filter(v -> v.getVessel().getCode().equals(vesselId)).findFirst().orElseThrow();
    }

    private String holderOf(String berthCode) {
        return views.visits(null, false).stream()
                .filter(v -> v.berth() != null && v.berth().berthCode().equals(berthCode))
                .map(v -> v.vessel().code())
                .findFirst().orElse(null);
    }
}
