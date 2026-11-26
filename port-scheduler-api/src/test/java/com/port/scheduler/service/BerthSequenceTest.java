package com.port.scheduler.service;

import com.port.scheduler.entity.visit.AllocationStatus;
import com.port.scheduler.entity.visit.BerthAllocation;
import com.port.scheduler.entity.visit.TimeClassifier;
import com.port.scheduler.entity.visit.VesselVisit;
import com.port.scheduler.entity.visit.VisitEvent;
import com.port.scheduler.entity.visit.VisitPhase;
import com.port.scheduler.entity.vessel.VesselType;
import com.port.scheduler.exceptions.VisitConflictException;
import com.port.scheduler.model.view.VisitView;
import com.port.scheduler.model.view.VisitView.Ahead;
import com.port.scheduler.repository.BerthAllocationRepository;
import com.port.scheduler.repository.VesselVisitRepository;
import com.port.scheduler.repository.VisitEventRepository;
import com.port.scheduler.service.VesselVisitService.ReportedPosition;
import com.port.scheduler.service.VesselVisitService.Scheduled;
import com.port.scheduler.service.VesselVisitService.VesselSize;
import com.port.scheduler.support.PersistenceTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * A berth holds a sequence of bookings across the planning week (corner case
 * 5, owner's decisions of 2026-11-23): a ship due soon uses the gap before a
 * booking without delaying it, a booking held up by the ship before it moves
 * to a berth that can take it much sooner, and a ship reports its arrival
 * only at its turn: at a berth no one else lies at, and before a ship booked
 * ahead of it only if it is gone by then. A ship that is early anchors, and
 * is brought in as soon as it can be without passing a ship booked before it
 * or delaying a booking. On the four seeded berths: B1 and B2
 * take ships up to 190 m, B3 and B4 up to 140 m.
 */
@PersistenceTest
@ExtendWith(OutputCaptureExtension.class)
class BerthSequenceTest {

    private static final Duration STAY = PlanningRules.typicalStay(VesselType.OTHER);
    private static final ReportedPosition POSITION = new ReportedPosition(61.50, 4.90, 8.5);
    /** Too long for B3 and B4, so only B1 and B2 take it. */
    private static final VesselSize LARGE = new VesselSize(180.0, 28.0, 9.0);
    /** A size that fits every berth: a call opens only for a vessel of known length and beam. */
    private static final VesselSize ANY_SIZE = new VesselSize(120.0, 20.0, null);

    @Autowired VesselVisitService visitService;
    @Autowired BerthService berthService;
    @Autowired PortViewService views;
    @Autowired VesselVisitRepository visits;
    @Autowired BerthAllocationRepository allocations;
    @Autowired VisitEventRepository events;

    // ---- the gaps between bookings

    @Test
    void aShipDueSoonIsBookedInTheGapBeforeShipsDueDaysAway() {
        Instant before = Instant.now();
        sized("ALDA", Duration.ofDays(5));                    // B1, five days out
        sized("BRIS", Duration.ofDays(5).plusHours(2));       // B2
        Instant aldaAsked = visitOf("ALDA").getRtaBerth();
        Instant brisAsked = visitOf("BRIS").getRtaBerth();

        Scheduled cora = sized("CORA", Duration.ofHours(1));

        // CORA is done in about a day, long before ALDA is due, so it uses B1 now instead of waiting five days
        assertThat(cora.assignment().berthId()).isEqualTo("B1");
        assertThat(cora.requestedArrival()).isCloseTo(before.plus(Duration.ofHours(1)), within(30, ChronoUnit.SECONDS));
        assertThat(lineOf("B1")).containsExactly("CORA", "ALDA");
        assertThat(visitOf("ALDA").getRtaBerth()).isEqualTo(aldaAsked);
        assertThat(visitOf("BRIS").getRtaBerth()).isEqualTo(brisAsked);
        assertThat(requestsTo("ALDA")).hasSize(1);
        assertThat(requestsTo("BRIS")).hasSize(1);
    }

    @Test
    void aShipWhoseStayDoesNotFitTheGapIsBookedAfterAndDelaysNoOne() {
        Instant before = Instant.now();
        sized("ALDA", Duration.ofHours(20));   // B1
        sized("BRIS", Duration.ofHours(20));   // B2
        Instant aldaAsked = visitOf("ALDA").getRtaBerth();

        Scheduled cora = sized("CORA", Duration.ofHours(1));

        // nineteen hours free before ALDA is not a day's stay, so CORA comes after it
        assertThat(lineOf("B1")).containsExactly("ALDA", "CORA");
        assertThat(cora.requestedArrival()).isCloseTo(before.plus(Duration.ofHours(20)).plus(STAY),
                within(30, ChronoUnit.SECONDS));
        assertThat(visitOf("ALDA").getRtaBerth()).isEqualTo(aldaAsked);
        assertThat(requestsTo("ALDA")).hasSize(1);
    }

    // ---- a booking held up by the ship before it

    @Test
    void aBookingBehindAnOverstayIsToldItsNewTimeOnlyWhenItMovesByTheThreshold() {
        overstayingOnB1AndTheOthersBooked();
        scheduledIn("V005", Duration.ofMinutes(10));   // B1, one overstay step from now
        assertThat(lineOf("B1")).containsExactly("V001", "V005");

        Instant soon = Instant.now().plus(Duration.ofMinutes(70)).truncatedTo(ChronoUnit.SECONDS);
        visitService.expectCompletion(visitOf("V001").getVisitRef(), soon, null);
        assertThat(requestsTo("V005")).hasSize(1);   // ten minutes later is not worth telling

        Instant leaves = Instant.now().plus(Duration.ofHours(3)).truncatedTo(ChronoUnit.SECONDS);
        visitService.expectCompletion(visitOf("V001").getVisitRef(), leaves, null);

        // the other berths are a day away, so V005 stays on B1 and is told the later time
        assertThat(lineOf("B1")).containsExactly("V001", "V005");
        assertThat(visitOf("V005").getRtaBerth()).isEqualTo(leaves);
        assertThat(requestsTo("V005")).hasSize(2).last()
                .satisfies(e -> assertThat(e.getRemarks()).isEqualTo("Berth expected free " + leaves));
    }

    @Test
    void aBookingHeldUpMoreThanTheToleranceMovesToABerthThatCanTakeItSooner(CapturedOutput log) {
        Instant before = Instant.now();
        alongsideB1AndTheOthersBooked();
        scheduledIn("V005", Duration.ofHours(1));   // B1, after V001
        assertThat(lineOf("B1")).containsExactly("V001", "V005");

        // V001 will stay eight hours longer: B2 can take V005 about six hours sooner than B1 now can
        Instant leaves = Instant.now().plus(STAY).plus(Duration.ofHours(8)).truncatedTo(ChronoUnit.SECONDS);
        visitService.expectCompletion(visitOf("V001").getVisitRef(), leaves, "Crane breakdown");

        assertThat(lineOf("B1")).containsExactly("V001");
        assertThat(lineOf("B2")).containsExactly("V002", "V005");
        assertThat(visitOf("V005").getRtaBerth()).isCloseTo(before.plus(Duration.ofHours(2)).plus(STAY),
                within(30, ChronoUnit.SECONDS));
        assertThat(requestsTo("V005")).last().satisfies(e -> {
            assertThat(e.getBerth().getCode()).isEqualTo("B2");
            assertThat(e.getRemarks()).startsWith("Moved from B1 to B2, which can take it ").endsWith(" sooner");
        });
        // the decision is in the log too
        String v005 = "V005 (" + visitOf("V005").getVisitRef() + ")";
        assertThat(log).contains("Moved " + v005 + " from B1 to B2, ")
                .contains("Requested arrival for " + v005 + ": " + visitOf("V005").getRtaBerth() + " on B2 (Moved from B1");
    }

    @Test
    void aBookingHeldUpByLessThanTheToleranceStaysOnItsBerth() {
        alongsideB1AndTheOthersBooked();
        scheduledIn("V005", Duration.ofHours(1));

        // five hours longer: B2 would be only about three hours sooner
        Instant leaves = Instant.now().plus(STAY).plus(Duration.ofHours(5)).truncatedTo(ChronoUnit.SECONDS);
        visitService.expectCompletion(visitOf("V001").getVisitRef(), leaves, null);

        assertThat(lineOf("B1")).containsExactly("V001", "V005");
        assertThat(visitOf("V005").getRtaBerth()).isEqualTo(leaves);
    }

    // ---- maintenance

    @Test
    void aShipMovedForMaintenanceKeepsItsClaimOverAShipBookedElsewhere() {
        alongsideB1AndTheOthersBooked();
        scheduledIn("V005", Duration.ofHours(1));   // B1, after V001, a day out
        Instant from = Instant.now().plus(Duration.ofHours(3)).truncatedTo(ChronoUnit.SECONDS);
        Instant to = Instant.now().plus(Duration.ofHours(5)).truncatedTo(ChronoUnit.SECONDS);

        berthService.scheduleMaintenance("B3", from, to, "Fenders");

        // V003 loses its window to the maintenance and is planned again first: B3 right after the window.
        // V005 would also gain by moving there, but V003's claim comes first.
        assertThat(lineOf("B3")).containsExactly("V003");
        assertThat(visitOf("V003").getRtaBerth()).isEqualTo(to);
        assertThat(lineOf("B1")).containsExactly("V001", "V005");
    }

    // ---- a booked ship that falls behind

    @Test
    void aShipFarBehindItsAgreedTimeGivesItsPlaceToTheShipBookedBehindIt() {
        Instant before = Instant.now();
        sized("ALDA", Duration.ofHours(2));            // B1
        visitService.confirm("ALDA", null);
        sized("BRIS", Duration.ofHours(2));            // B2
        sized("CORA", Duration.ofHours(3));            // B1, after ALDA
        assertThat(lineOf("B1")).containsExactly("ALDA", "CORA");

        visitService.declareEta("ALDA", Instant.now().plus(Duration.ofHours(12)), POSITION);

        // ten hours late, and CORA can be alongside B1 sooner: CORA goes first, ALDA is planned again
        assertThat(lineOf("B1")).containsExactly("CORA");
        assertThat(visitOf("CORA").getRtaBerth()).isCloseTo(before.plus(Duration.ofHours(3)),
                within(30, ChronoUnit.SECONDS));
        assertThat(requestsTo("CORA")).last().satisfies(e ->
                assertThat(e.getRemarks()).isEqualTo("Given up by ALDA, now expected 10 h after its agreed arrival"));
        assertThat(lineOf("B2")).containsExactly("BRIS", "ALDA");
        assertThat(visitOf("ALDA").getPtaBerth()).isNull();
        assertThat(requestsTo("ALDA")).last().satisfies(e -> assertThat(e.getRemarks())
                .startsWith("Berth B1 withdrawn: this estimate is 10 h after the agreed arrival"));
    }

    // ---- arrivals

    @Test
    void anArrivalAtABerthWhereAnotherShipStillLiesIsRefusedNamingIt() {
        alongsideB1AndTheOthersBooked();
        scheduledIn("V005", Duration.ofMinutes(10));   // B1, after V001
        assertThat(lineOf("B1")).containsExactly("V001", "V005");

        assertThatThrownBy(() -> visitService.arrived("V005", Instant.now()))
                .isInstanceOf(VisitConflictException.class)
                .hasMessage("B1 still has V001 alongside; its departure is recorded first");
        assertThat(visitOf("V005").getPhase()).isEqualTo(VisitPhase.INBOUND);
        assertThat(visitOf("V005").getAtaBerth()).isNull();
    }

    @Test
    void aShipBookedBehindOneAlongsideIsShownItWaitsForItsDeparture() {
        alongsideB1AndTheOthersBooked();
        scheduledIn("V005", Duration.ofMinutes(10));   // B1, after V001

        assertThat(viewOf("V005").aheadOnBerth()).extracting(Ahead::vesselCode, Ahead::status)
                .containsExactly("V001", AllocationStatus.ACTIVE);
        assertThat(viewOf("V002").aheadOnBerth()).isNull();   // first in line on B2

        berthService.release("B1");

        assertThat(viewOf("V005").aheadOnBerth()).isNull();
    }

    @Test
    void aShipThatArrivesBeforeItsTurnIsRefusedNamingTheShipAhead() {
        List.of("V001", "V002", "V003", "V004").forEach(v -> scheduledIn(v, Duration.ofHours(2)));
        scheduledIn("V005", Duration.ofHours(1));   // B1, after V001
        assertThat(lineOf("B1")).containsExactly("V001", "V005");
        Instant v001Turn = windowOf("V001").getPlannedStart();
        Instant v005Turn = windowOf("V005").getPlannedStart();
        assertThat(viewOf("V005").aheadOnBerth()).extracting(Ahead::vesselCode, Ahead::status, Ahead::plannedStart)
                .containsExactly("V001", AllocationStatus.PLANNED, v001Turn);

        assertThatThrownBy(() -> visitService.arrived("V005", Instant.now()))
                .isInstanceOf(VisitConflictException.class)
                .hasMessage("B1 is booked for V001 from " + v001Turn + ", ahead of this vessel's turn at " + v005Turn
                        + "; a vessel comes in out of turn only when its stay ends before the next booking");

        // nothing moves: V005 is still on its way, and V001 keeps its turn and is told nothing new
        assertThat(visitOf("V005").getPhase()).isEqualTo(VisitPhase.INBOUND);
        assertThat(visitOf("V005").getAtaBerth()).isNull();
        assertThat(lineOf("B1")).containsExactly("V001", "V005");
        assertThat(requestsTo("V001")).hasSize(1);
        assertThat(viewOf("V001").aheadOnBerth()).isNull();
    }

    @Test
    void anEarlyShipWhoseStayEndsBeforeTheNextBookingIsTakenIn() {
        sized("ALDA", Duration.ofDays(5));   // B1, five days out
        sized("BRIS", Duration.ofDays(5));   // B2
        sized("CORA", Duration.ofDays(6));   // after one of them
        String berth = viewOf("CORA").berth().berthCode();
        String passed = lineOf(berth).get(0);
        assertThat(lineOf(berth)).containsExactly(passed, "CORA");
        Instant passedAsked = visitOf(passed).getRtaBerth();
        // CORA would be gone days before the ship booked ahead of it is due
        assertThat(viewOf("CORA").aheadOnBerth()).isNull();

        visitService.arrived("CORA", Instant.now());

        assertThat(windowOf("CORA").getStatus()).isEqualTo(AllocationStatus.ACTIVE);
        assertThat(lineOf(berth)).containsExactly("CORA", passed);
        assertThat(visitOf(passed).getRtaBerth()).isEqualTo(passedAsked);
        assertThat(requestsTo(passed)).hasSize(1);
    }

    // ---- the anchorage

    @Test
    void aShipAtAnchorIsBroughtInRightAfterTheShipBookedBeforeIt(CapturedOutput log) {
        sized("HELL", Duration.ZERO);
        visitService.arrived("HELL", Instant.now());          // alongside B1 or B2
        sized("ASKV", Duration.ofHours(72));                  // the other, three days out
        visitService.confirm("ASKV", null);
        sized("DALE", Duration.ofHours(1));                   // ahead of ASKV, in the gap before it
        String berth = viewOf("ASKV").berth().berthCode();
        assertThat(lineOf(berth)).containsExactly("DALE", "ASKV");
        Instant daleAsked = visitOf("DALE").getRtaBerth();
        Instant daleLeaves = windowOf("DALE").getPlannedEnd();

        Instant anchored = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        visitService.anchored("ASKV", anchored);

        // ASKV is here: it comes in once DALE is done, not in three days, and DALE does not move
        assertThat(lineOf(berth)).containsExactly("DALE", "ASKV");
        assertThat(windowOf("ASKV").getPlannedStart()).isEqualTo(daleLeaves);
        assertThat(visitOf("DALE").getRtaBerth()).isEqualTo(daleAsked);
        assertThat(requestsTo("DALE")).hasSize(1);
        VesselVisit askv = visitOf("ASKV");
        assertThat(askv.getPhase()).isEqualTo(VisitPhase.ARRIVED);
        assertThat(askv.getAtaAnchorage()).isEqualTo(anchored);
        assertThat(askv.getRtaBerth()).isEqualTo(daleLeaves);
        assertThat(askv.getPtaBerth()).isNull();   // a new requested time, which ASKV accepts again
        assertThat(requestsTo("ASKV")).last().satisfies(e ->
                assertThat(e.getRemarks()).isEqualTo("At the anchorage since " + anchored + ", ready to come in"));
        // it is here, so no further estimate is expected of it
        assertThat(viewOf("ASKV").nextEstimateBy()).isNull();
        assertThat(viewOf("ASKV").estimateDueSince()).isNull();
        assertThat(viewOf("ASKV").ataAnchorage()).isEqualTo(anchored);
        // and in the log, in the order it happened
        String named = "ASKV (" + askv.getVisitRef() + ")";
        assertThat(log).containsSubsequence(named + " at the anchorage since " + anchored,
                "Requested arrival for " + named + ": " + daleLeaves + " on " + berth
                        + " (At the anchorage since " + anchored + ", ready to come in)");
    }

    @Test
    void aShipAtAnchorMovesToABerthThatCanTakeItMuchSoonerWithoutDelayingItsBooking() {
        sized("HELL", Duration.ZERO);
        visitService.arrived("HELL", Instant.now());          // alongside one large berth for a day
        sized("DALE", Duration.ofHours(1));                   // the other, from an hour out
        sized("GULL", Duration.ofHours(60));                  // after HELL, whose berth is free first
        sized("ASKV", Duration.ofHours(72));                  // after DALE: GULL is in the way on the first
        String first = viewOf("HELL").berth().berthCode();
        String second = viewOf("DALE").berth().berthCode();
        assertThat(lineOf(first)).containsExactly("HELL", "GULL");
        assertThat(lineOf(second)).containsExactly("DALE", "ASKV");
        Instant hellLeaves = Instant.now().plus(Duration.ofHours(2)).truncatedTo(ChronoUnit.SECONDS);
        visitService.expectCompletion(visitOf("HELL").getVisitRef(), hellLeaves, null);
        Instant gullAsked = visitOf("GULL").getRtaBerth();
        assertThat(lineOf(second)).containsExactly("DALE", "ASKV");   // not here yet: nothing to gain

        visitService.anchored("ASKV", Instant.now());

        // HELL's berth is free in two hours, and ASKV's stay ends long before GULL is due there
        assertThat(lineOf(first)).containsExactly("HELL", "ASKV", "GULL");
        assertThat(lineOf(second)).containsExactly("DALE");
        assertThat(windowOf("ASKV").getPlannedStart()).isEqualTo(hellLeaves);
        assertThat(visitOf("GULL").getRtaBerth()).isEqualTo(gullAsked);
        assertThat(requestsTo("ASKV")).last().satisfies(e -> assertThat(e.getRemarks())
                .startsWith("Moved from " + second + " to " + first + ", which can take it "));
    }

    @Test
    void anchorageReportsAreCheckedAgainstTheCall() {
        sized("ALDA", Duration.ofHours(5));
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        assertThatThrownBy(() -> visitService.leftAnchorage("ALDA", now))
                .isInstanceOf(VisitConflictException.class).hasMessage("Vessel ALDA is not at the anchorage");
        assertThatThrownBy(() -> visitService.anchored("ALDA", now.plus(Duration.ofHours(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("An arrival at the anchorage cannot be in the future");

        visitService.anchored("ALDA", now);
        assertThatThrownBy(() -> visitService.anchored("ALDA", now))
                .isInstanceOf(VisitConflictException.class)
                .hasMessage("Vessel ALDA has been at the anchorage since " + now + "; it reports leaving it first");
        assertThatThrownBy(() -> visitService.leftAnchorage("ALDA", now.minus(Duration.ofMinutes(5))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Leaving the anchorage");

        visitService.leftAnchorage("ALDA", now);
        assertThat(visitOf("ALDA").getAtdAnchorage()).isEqualTo(now);
        visitService.arrived("ALDA", Instant.now());
        assertThatThrownBy(() -> visitService.anchored("ALDA", Instant.now()))
                .isInstanceOf(VisitConflictException.class)
                .hasMessage("Vessel ALDA is alongside; it cannot be at the anchorage");
    }

    /** V001 alongside B1 since now, for a typical stay; V002 to V004 booked on B2 to B4 from two hours out. */
    private void alongsideB1AndTheOthersBooked() {
        scheduledIn("V001", Duration.ofHours(2));
        visitService.arrived("V001", Instant.now());
        List.of("V002", "V003", "V004").forEach(v -> scheduledIn(v, Duration.ofHours(2)));
    }

    /** V001 alongside B1 since 30 hours ago, past its planned end; V002 to V004 booked from two hours out. */
    private void overstayingOnB1AndTheOthersBooked() {
        scheduledIn("V001", Duration.ofHours(-30));
        visitService.arrived("V001", Instant.now().minus(Duration.ofHours(30)).truncatedTo(ChronoUnit.SECONDS));
        List.of("V002", "V003", "V004").forEach(v -> scheduledIn(v, Duration.ofHours(2)));
    }

    /** The vessels on a berth: the one alongside, then those booked, in the order they are planned. */
    private List<String> lineOf(String berthCode) {
        return views.visits(null, false).stream()
                .filter(v -> v.berth() != null && v.berth().berthCode().equals(berthCode))
                .sorted(Comparator.comparing((VisitView v) -> v.berth().status() != AllocationStatus.ACTIVE)
                        .thenComparing(v -> v.berth().plannedStart()))
                .map(v -> v.vessel().code())
                .toList();
    }

    private BerthAllocation windowOf(String vesselId) {
        return allocations.findByVisitAndStatusIn(visitOf(vesselId), AllocationStatus.OPEN).orElseThrow();
    }

    private List<VisitEvent> requestsTo(String vesselId) {
        return events.findLedger(visitOf(vesselId)).stream()
                .filter(e -> e.getClassifier() == TimeClassifier.REQUESTED).toList();
    }

    private VisitView viewOf(String vesselId) {
        return views.visits(vesselId, false).get(0);
    }

    private VesselVisit visitOf(String vesselId) {
        return visits.findAll().stream()
                .filter(v -> v.getVessel().getCode().equals(vesselId)).findFirst().orElseThrow();
    }

    private Scheduled sized(String vesselId, Duration sailing) {
        visitService.resolveVessel(null, null, vesselId, null);
        return visitService.declareEta(vesselId, Instant.now().plus(sailing), POSITION, LARGE);
    }

    private Scheduled scheduledIn(String vesselId, Duration sailing) {
        visitService.resolveVessel(null, null, vesselId, null);
        return visitService.declareEta(vesselId, Instant.now().plus(sailing), POSITION, ANY_SIZE);
    }
}
