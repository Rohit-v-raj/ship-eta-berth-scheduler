package com.port.scheduler.service;

import com.port.scheduler.entity.vessel.Vessel;
import com.port.scheduler.entity.visit.AllocationStatus;
import com.port.scheduler.model.view.VisitView;
import com.port.scheduler.repository.BerthAllocationRepository;
import com.port.scheduler.repository.VesselRepository;
import com.port.scheduler.repository.VesselVisitRepository;
import com.port.scheduler.service.VesselVisitService.ReportedPosition;
import com.port.scheduler.service.VesselVisitService.Scheduled;
import com.port.scheduler.service.VesselVisitService.VesselSize;
import com.port.scheduler.support.PersistenceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A berth is given only to a vessel known to fit it (owner's decision,
 * 2026-11-23): the length overall and beam are needed once per vessel, to open
 * its first call, and stay on the vessel record. The draft for a call is
 * optional until a day before arrival; until it comes, the largest draft the
 * vessel has declared is used. B1 and B2 take a 10.5 m draft, B3 and B4 7.5 m.
 */
@PersistenceTest
class VesselSizeTest {

    private static final ReportedPosition POSITION = new ReportedPosition(61.50, 4.90, 8.5);
    /** Fits every berth. */
    private static final VesselSize ANY_SIZE = new VesselSize(120.0, 20.0, null);

    @Autowired VesselVisitService visitService;
    @Autowired PortViewService views;
    @Autowired VesselRepository vessels;
    @Autowired VesselVisitRepository visits;
    @Autowired BerthAllocationRepository allocations;

    @Test
    void aFirstCallNeedsTheShipsLengthAndBeam() {
        visitService.resolveVessel(null, null, "NOSIZE", null);

        assertThatThrownBy(() -> visitService.declareEta("NOSIZE", in(Duration.ofHours(2)), POSITION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The terminal holds no length overall or beam for NOSIZE; send lengthOverall and width"
                        + " (in vesselSizeUnit) with its estimate. A berth is given only to a vessel known to fit it");
        assertThatThrownBy(() -> visitService.declareEta("NOSIZE", in(Duration.ofHours(2)), POSITION,
                new VesselSize(150.0, null, 8.0)))
                .hasMessageStartingWith("The terminal holds no beam for NOSIZE; send width");

        assertThat(visits.count()).isZero();
        Vessel vessel = vessels.findByCode("NOSIZE").orElseThrow();
        assertThat(vessel.getLoaMeters()).isNull();       // a refused estimate records nothing
        assertThat(vessel.getMaxDraftMeters()).isNull();
    }

    @Test
    void aShipKnownFromAnEarlierCallNeedsNoSizeForTheNext() {
        open("KNOWN", ANY_SIZE, in(Duration.ofHours(2)));
        visitService.omit("KNOWN");

        Scheduled again = visitService.declareEta("KNOWN", in(Duration.ofHours(3)), POSITION);

        assertThat(again.assignment().status()).isEqualTo("BERTHED");
        assertThat(visits.count()).isEqualTo(2);
    }

    @Test
    void aCallOpenedBeforeSizesWereRequiredCanStillBePlannedAgain() {
        open("OLDCALL", ANY_SIZE, in(Duration.ofHours(2)));
        Vessel vessel = vessels.findByCode("OLDCALL").orElseThrow();
        vessel.setLoaMeters(null);   // as a vessel on record from before the rule
        vessel.setBeamMeters(null);
        vessels.save(vessel);

        Scheduled replanned = visitService.declareEta("OLDCALL", in(Duration.ofHours(5)), POSITION);

        assertThat(replanned.assignment().status()).isEqualTo("BERTHED");
    }

    @Test
    void theLargestDraftDeclaredIsKeptAndUsedUntilACallGivesItsOwn() {
        open("DEEP", new VesselSize(120.0, 20.0, 9.0), in(Duration.ofHours(2)));
        visitService.omit("DEEP");
        open("DEEP", new VesselSize(null, null, 6.5), in(Duration.ofHours(2)));
        visitService.omit("DEEP");
        assertThat(vessels.findByCode("DEEP").orElseThrow().getMaxDraftMeters()).isEqualByComparingTo("9.00");

        open("V001", ANY_SIZE, in(Duration.ofHours(2)));   // B1
        open("V002", ANY_SIZE, in(Duration.ofHours(2)));   // B2
        visitService.declareEta("DEEP", in(Duration.ofHours(3)), POSITION);   // no draft for this call
        open("SHALLOW", ANY_SIZE, in(Duration.ofHours(3)));

        // 9 m is too deep for B3 and B4, so DEEP waits for a large berth; SHALLOW, of no known draft, takes B3
        assertThat(berthOf("DEEP")).isIn("B1", "B2");
        assertThat(berthOf("SHALLOW")).isEqualTo("B3");
    }

    @Test
    void aDraftIsDueADayBeforeArrivalUntilTheCallDeclaresOne() {
        open("FAR", ANY_SIZE, in(Duration.ofHours(30)));
        assertThat(view("FAR").draftDueSince()).isNull();

        Instant closer = in(Duration.ofHours(20)).truncatedTo(ChronoUnit.SECONDS);
        visitService.declareEta("FAR", closer, POSITION);
        VisitView due = view("FAR");
        assertThat(due.draftDueSince()).isEqualTo(due.rtaBerth().minus(Duration.ofHours(24)));

        visitService.declareEta("FAR", closer, POSITION, new VesselSize(null, null, 7.0));
        assertThat(view("FAR").draftDueSince()).isNull();
        assertThat(view("FAR").arrivalDraftMeters()).isEqualByComparingTo("7.00");
    }

    private Scheduled open(String vesselId, VesselSize size, Instant eta) {
        visitService.resolveVessel(null, null, vesselId, null);
        return visitService.declareEta(vesselId, eta, POSITION, size);
    }

    private VisitView view(String vesselId) {
        return views.visits(vesselId, false).get(0);
    }

    private String berthOf(String vesselId) {
        return allocations.findByVisitAndStatusIn(
                visits.findAll().stream().filter(v -> v.getVessel().getCode().equals(vesselId)
                        && v.getAtdBerth() == null && v.getCancelledBy() == null).findFirst().orElseThrow(),
                AllocationStatus.OPEN).orElseThrow().getBerth().getCode();
    }

    private static Instant in(Duration duration) {
        return Instant.now().plus(duration);
    }
}
