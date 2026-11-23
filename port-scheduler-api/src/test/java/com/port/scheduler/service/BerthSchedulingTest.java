package com.port.scheduler.service;

import com.port.scheduler.entity.vessel.Vessel;
import com.port.scheduler.entity.vessel.VesselType;
import com.port.scheduler.entity.visit.AllocationStatus;
import com.port.scheduler.entity.visit.BerthAllocation;
import com.port.scheduler.entity.visit.EventSource;
import com.port.scheduler.entity.visit.Milestone;
import com.port.scheduler.entity.visit.PredictionSource;
import com.port.scheduler.entity.visit.TimeClassifier;
import com.port.scheduler.entity.visit.VesselVisit;
import com.port.scheduler.entity.visit.VisitEvent;
import com.port.scheduler.entity.visit.VisitPhase;
import com.port.scheduler.exceptions.BerthNotFoundException;
import com.port.scheduler.exceptions.VisitConflictException;
import com.port.scheduler.exceptions.VisitNotFoundException;
import com.port.scheduler.model.view.VisitView;
import com.port.scheduler.repository.BerthAllocationRepository;
import com.port.scheduler.repository.BerthMaintenanceRepository;
import com.port.scheduler.repository.EtaPredictionRepository;
import com.port.scheduler.repository.VesselRepository;
import com.port.scheduler.repository.VesselVisitRepository;
import com.port.scheduler.repository.VisitEventRepository;
import com.port.scheduler.service.BerthService.Assignment;
import com.port.scheduler.service.VesselVisitService.ReportedPosition;
import com.port.scheduler.service.VesselVisitService.Scheduled;
import com.port.scheduler.service.VesselVisitService.VesselSize;
import com.port.scheduler.support.PersistenceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

/**
 * Scheduling behaviour end to end through the services and real MySQL, on the
 * four seeded berths B1 to B4. A vessel is scheduled the one way there is: its
 * operator declares an estimated arrival.
 */
@PersistenceTest
class BerthSchedulingTest {

    private static final Duration SAILING = Duration.ofMinutes(120);
    /** How long a vessel of no stated type is expected to stay. */
    private static final Duration STAY = PlanningRules.typicalStay(VesselType.OTHER);
    private static final ReportedPosition POSITION = new ReportedPosition(61.50, 4.90, 8.5);
    /** A size that fits every berth: a call opens only for a vessel of known length and beam. */
    private static final VesselSize ANY_SIZE = new VesselSize(120.0, 20.0, null);

    @Autowired VesselVisitService visitService;
    @Autowired BerthService berthService;
    @Autowired PortViewService views;
    @Autowired VesselRepository vessels;
    @Autowired VesselVisitRepository visits;
    @Autowired BerthAllocationRepository allocations;
    @Autowired EtaPredictionRepository predictions;
    @Autowired VisitEventRepository events;
    @Autowired BerthMaintenanceRepository maintenance;

    @Test
    void berthsFillInOrderThenVesselsAreBookedBehindTheShipsThere() {
        assertThat(schedule("V001")).isEqualTo(new Assignment("BERTHED", "B1", "Berth 1", null));
        assertThat(schedule("V002").berthId()).isEqualTo("B2");
        assertThat(schedule("V003").berthId()).isEqualTo("B3");
        assertThat(schedule("V004").berthId()).isEqualTo("B4");

        // each berth now holds a sequence: the fifth and sixth ships are booked after the first to leave
        assertThat(schedule("V005")).isEqualTo(new Assignment("BERTHED", "B1", "Berth 1", null));
        assertThat(schedule("V006")).isEqualTo(new Assignment("BERTHED", "B2", "Berth 2", null));
        assertThat(lineOf("B1")).containsExactly("V001", "V005");
        assertThat(windowOf("V005").getPlannedStart()).isEqualTo(windowOf("V001").getPlannedEnd());
    }

    @Test
    void eachBerthIsHeldByTheVesselItWasGivenTo() {
        schedule("V001");
        schedule("V002");

        assertThat(holderOf("B1")).isEqualTo("V001");
        assertThat(holderOf("B2")).isEqualTo("V002");
        assertThat(holderOf("B3")).isNull();
        assertThat(holderOf("B4")).isNull();
    }

    @Test
    void releasedBerthGoesToTheNextRequest() {
        List.of("V001", "V002", "V003", "V004").forEach(this::schedule);
        visitService.arrived("V002", Instant.now());

        assertThat(berthService.release("B2")).isTrue();

        assertThat(schedule("V006").berthId()).isEqualTo("B2");
    }

    @Test
    void releaseClosesTheAllocationAndTheVisit() {
        schedule("V001");
        visitService.arrived("V001", Instant.now());

        berthService.release("B1");

        VesselVisit visit = visits.findAll().get(0);
        BerthAllocation allocation = allocations.findAll().get(0);
        assertThat(visit.getPhase()).isEqualTo(VisitPhase.DEPARTED);
        assertThat(visit.getAtdBerth()).isNotNull();
        assertThat(allocation.getStatus()).isEqualTo(AllocationStatus.COMPLETED);
        assertThat(allocation.getActualEnd()).isNotNull();
    }

    @Test
    void releasingABerthWithOnlyABookingRecordsNoDeparture() {
        schedule("V001");   // booked on B1, not arrived

        assertThat(berthService.release("B1")).isFalse();

        assertThat(visitOf("V001").getPhase()).isEqualTo(VisitPhase.INBOUND);
        assertThat(visitOf("V001").getAtdBerth()).isNull();
        assertThat(holderOf("B1")).isEqualTo("V001");
        assertThat(events.findLedger(visitOf("V001")))
                .noneMatch(e -> e.getMilestone() == Milestone.DEPARTURE_BERTH);
    }

    @Test
    void releasingAFreeBerthReportsNothingReleased() {
        assertThat(berthService.release("B3")).isFalse();
    }

    @Test
    void releasingAnUnknownBerthIsAnError() {
        assertThatThrownBy(() -> berthService.release("B99"))
                .isInstanceOf(BerthNotFoundException.class);
    }

    @Test
    void anEstimateOpensTheVisitAndIsAnsweredWithARequestedArrival() {
        Instant before = Instant.now();

        schedule("V001");

        VesselVisit visit = visits.findAll().get(0);
        assertThat(visit.getVisitRef()).startsWith("VV-");
        assertThat(visit.getPhase()).isEqualTo(VisitPhase.INBOUND);
        assertThat(visit.getVessel().getCode()).isEqualTo("V001");
        assertThat(visit.getEtaBerth()).isCloseTo(before.plus(SAILING), within(30, ChronoUnit.SECONDS));
        // the terminal requests an arrival time; only the vessel can confirm it as planned
        assertThat(visit.getRtaBerth()).isEqualTo(visit.getEtaBerth());
        assertThat(visit.getPtaBerth()).isNull();
        assertThat(visit.getTerminal().getCode()).isEqualTo("FRO-CT");

        // the position the vessel gave is kept with the estimate it came with
        assertThat(predictions.findAll()).singleElement().satisfies(p -> {
            assertThat(p.getSource()).isEqualTo(PredictionSource.VESSEL_DECLARED);
            assertThat(p.getLat()).isEqualTo(61.50);
            assertThat(p.getLon()).isEqualTo(4.90);
            assertThat(p.getDistanceNm()).isEqualTo(8.5);
            assertThat(p.getPredictedArrival()).isEqualTo(visit.getEtaBerth());
            assertThat(p.getEtaMinutes()).isCloseTo(120.0, within(1.0));
        });
        assertThat(events.findAll())
                .allSatisfy(e -> assertThat(e.getMilestone()).isEqualTo(Milestone.ARRIVAL_BERTH))
                .extracting(VisitEvent::getClassifier, VisitEvent::getSource)
                .containsExactlyInAnyOrder(
                        tuple(TimeClassifier.ESTIMATED, EventSource.VESSEL),
                        tuple(TimeClassifier.REQUESTED, EventSource.TERMINAL));
    }

    @Test
    void anEstimateWithoutAPositionStoresNoPositionReport() {
        visitService.declareEta("V001", Instant.now().plus(SAILING), null, ANY_SIZE);

        assertThat(visits.count()).isEqualTo(1);
        assertThat(predictions.count()).isZero();
    }

    @Test
    void aSecondEstimateReplansTheSameVisitInsteadOfTakingASecondBerth() {
        Assignment first = schedule("V001");
        Assignment again = schedule("V001");

        assertThat(again).isEqualTo(first);
        assertThat(vessels.count()).isEqualTo(1);
        assertThat(visits.count()).isEqualTo(1);
        assertThat(allocations.count()).isEqualTo(1);
        assertThat(predictions.count()).isEqualTo(2);
    }

    @Test
    void vesselsBeyondTheHorizonKeepTheirPlaceInLineUntilTheyAreBooked() {
        assertThat(scheduledIn("V005", Duration.ofDays(10)).assignment()).isEqualTo(new Assignment("QUEUED", null, null, 1));
        assertThat(scheduledIn("V006", Duration.ofDays(10)).assignment().queueNumber()).isEqualTo(2);

        assertThat(scheduledIn("V005", Duration.ofHours(2)).assignment())
                .isEqualTo(new Assignment("BERTHED", "B1", "Berth 1", null));
        assertThat(scheduledIn("V006", Duration.ofDays(10)).assignment().queueNumber()).isEqualTo(1);
    }

    @Test
    void aVesselCanCallAgainAfterItHasDeparted() {
        schedule("V001");
        visitService.arrived("V001", Instant.now());
        berthService.release("B1");

        assertThat(schedule("V001").berthId()).isEqualTo("B1");

        Vessel vessel = vessels.findByCode("V001").orElseThrow();
        assertThat(visits.findAll()).hasSize(2).allSatisfy(v -> assertThat(v.getVessel()).isEqualTo(vessel));
    }

    @Test
    void aVesselWithAFreeBerthIsAskedToArriveAtItsOwnEstimate() {
        Scheduled berthed = scheduled("V001", null);

        VesselVisit visit = visits.findAll().get(0);
        assertThat(berthed.requestedArrival()).isEqualTo(visit.getEtaBerth());
    }

    @Test
    void theAllocationEndsOneTypicalStayForTheVesselTypeAfterItStarts() {
        scheduled("V001", VesselType.CONTAINER);

        BerthAllocation allocation = allocations.findAll().get(0);
        assertThat(Duration.between(allocation.getPlannedStart(), allocation.getPlannedEnd()))
                .isEqualTo(PlanningRules.typicalStay(VesselType.CONTAINER));
        assertThat(vessels.findByCode("V001").orElseThrow().getType()).isEqualTo(VesselType.CONTAINER);
    }

    @Test
    void aVesselBookedBehindAnotherIsAskedToArriveWhenItsBerthIsExpectedFree() {
        Instant before = Instant.now();
        List.of("V001", "V002", "V003", "V004").forEach(this::schedule);

        Scheduled behind = scheduled("V005", null);

        // each berth is planned free one typical stay after its vessel's ETA; the first to free is B1
        Instant berthFree = before.plus(SAILING).plus(PlanningRules.typicalStay(VesselType.OTHER));
        assertThat(behind.assignment()).isEqualTo(new Assignment("BERTHED", "B1", "Berth 1", null));
        assertThat(behind.requestedArrival()).isCloseTo(berthFree, within(30, ChronoUnit.SECONDS));

        VesselVisit visit = visitOf("V005");
        assertThat(visit.getRtaBerth()).isEqualTo(behind.requestedArrival());
        assertThat(visit.getPtaBerth()).isNull();
        assertThat(requestsTo("V005")).singleElement().satisfies(e -> {
            assertThat(e.getBerth().getCode()).isEqualTo("B1");
            assertThat(e.getRemarks()).startsWith("Berth expected free ");
            assertThat(Instant.parse(e.getRemarks().substring("Berth expected free ".length())))
                    .isCloseTo(windowOf("V001").getPlannedEnd(), within(1, ChronoUnit.MILLIS));
        });
    }

    @Test
    void aBerthThatComesFreeTakesTheFirstBookedShipItCanServeMuchSooner() {
        List.of("V001", "V002", "V003", "V004").forEach(this::schedule);
        schedule("V005");   // behind V001 on B1
        schedule("V006");   // behind V002 on B2
        visitService.arrived("V003", Instant.now());

        berthService.release("B3");

        // B3 can take V005 a whole stay sooner, more than the tolerance; V006 would gain nothing there
        assertThat(lineOf("B3")).containsExactly("V005");
        assertThat(visitOf("V005").getRtaBerth()).isEqualTo(visitOf("V005").getEtaBerth());
        assertThat(requestsTo("V005")).last().satisfies(e -> {
            assertThat(e.getBerth().getCode()).isEqualTo("B3");
            assertThat(e.getRemarks()).startsWith("Moved from B1 to B3, which can take it 23 h");
        });
        assertThat(lineOf("B1")).containsExactly("V001");
        assertThat(lineOf("B2")).containsExactly("V002", "V006");
    }

    @Test
    void confirmingTurnsTheRequestedArrivalIntoThePlannedOne() {
        Scheduled scheduled = scheduled("V001", null);

        Instant planned = visitService.confirm("V001", scheduled.requestedArrival());
        visitService.confirm("V001", null);   // repeating it adds nothing

        VesselVisit visit = visitOf("V001");
        assertThat(planned).isEqualTo(visit.getRtaBerth());
        assertThat(visit.getPtaBerth()).isEqualTo(visit.getRtaBerth());
        assertThat(events.findAll()).filteredOn(e -> e.getClassifier() == TimeClassifier.PLANNED)
                .singleElement().satisfies(e -> {
                    assertThat(e.getSource()).isEqualTo(EventSource.VESSEL);
                    // accepted at the berth the request named
                    assertThat(e.getBerth()).isNotNull().isEqualTo(requestsTo("V001").getLast().getBerth());
                });
    }

    @Test
    void aWaitingVesselAcceptsATimeWithNoBerthYet() {
        Scheduled far = scheduledIn("V005", Duration.ofDays(10));   // beyond the booking horizon

        visitService.confirm("V005", far.requestedArrival());

        VesselVisit visit = visitOf("V005");
        assertThat(visit.getPtaBerth()).isEqualTo(far.requestedArrival());
        assertThat(events.findLedger(visit)).filteredOn(e -> e.getClassifier() == TimeClassifier.PLANNED)
                .singleElement().satisfies(e -> assertThat(e.getBerth()).isNull());
    }

    @Test
    void aConfirmationOfASupersededTimeOrAnUnknownVesselIsRefused() {
        Scheduled scheduled = scheduled("V001", null);

        assertThatThrownBy(() -> visitService.confirm("V001", scheduled.requestedArrival().plus(Duration.ofHours(1))))
                .isInstanceOf(VisitConflictException.class);
        assertThatThrownBy(() -> visitService.confirm("NOBODY", null))
                .isInstanceOf(VisitNotFoundException.class);
        assertThat(visitOf("V001").getPtaBerth()).isNull();
    }

    @Test
    void aNewRequestCancelsTheEarlierConfirmation() {
        schedule("V001");
        visitService.arrived("V001", Instant.now());
        List.of("V002", "V003", "V004").forEach(this::schedule);
        schedule("V005");                     // behind V001 on B1
        visitService.confirm("V005", null);   // confirmed for when B1 was expected free

        berthService.release("B1");           // free now, so the terminal asks for the vessel's own ETA

        VesselVisit visit = visitOf("V005");
        assertThat(visit.getRtaBerth()).isEqualTo(visit.getEtaBerth());
        assertThat(visit.getPtaBerth()).isNull();
    }

    @Test
    void omittingACallFreesItsBerthForTheHeadOfTheQueue() {
        List.of("V001", "V002", "V003", "V004").forEach(this::schedule);
        schedule("V005");

        visitService.omit("V002");

        assertThat(visitOf("V002").getPhase()).isEqualTo(VisitPhase.CANCELLED);
        assertThat(visitOf("V002").getCancelledBy()).isEqualTo(EventSource.VESSEL);
        assertThat(holderOf("B2")).isEqualTo("V005");
    }

    @Test
    void omittingAQueuedCallTakesItOutOfTheQueue() {
        List.of("V001", "V002", "V003", "V004").forEach(this::schedule);
        scheduledIn("V005", Duration.ofDays(10));
        scheduledIn("V006", Duration.ofDays(10));

        visitService.omit("V005");

        assertThat(scheduledIn("V006", Duration.ofDays(10)).assignment().queueNumber()).isEqualTo(1);
        assertThat(List.of("B1", "B2", "B3", "B4")).extracting(this::holderOf)
                .containsExactly("V001", "V002", "V003", "V004");
    }

    @Test
    void whenAShipAheadOnItsBerthOmitsItsCallTheNextShipIsAskedToArriveEarlier() {
        Instant before = Instant.now();
        berthsComeFreeHoursApart();
        scheduledIn("V005", Duration.ofHours(1));   // behind V001 on B1
        scheduledIn("V006", Duration.ofHours(1));   // behind V002 on B2, which frees before B1 is free again
        assertThat(lineOf("B2")).containsExactly("V002", "V006");
        visitService.omit("V002");

        // V006 is now first on B2, so it is asked for its own estimate, a stay and more sooner
        VesselVisit visit = visitOf("V006");
        assertThat(visit.getRtaBerth()).isCloseTo(before.plus(Duration.ofHours(1)), within(30, ChronoUnit.SECONDS));
        assertThat(visit.getPtaBerth()).isNull();
        assertThat(lineOf("B2")).containsExactly("V006");
    }

    @Test
    void aWaitingShipsTimeIsLeftAloneWhenItWouldMoveByLessThanTheThreshold() {
        scheduledIn("V001", Duration.ofHours(2));
        scheduledIn("V002", Duration.ofMinutes(130));   // its berth comes free ten minutes after the first
        scheduledIn("V003", Duration.ofHours(8));
        scheduledIn("V004", Duration.ofHours(9));
        scheduledIn("V005", Duration.ofHours(1));
        Instant asked = scheduledIn("V006", Duration.ofHours(1)).requestedArrival();
        visitService.confirm("V006", null);

        visitService.omit("V005");

        VesselVisit visit = visitOf("V006");
        assertThat(visit.getRtaBerth()).isEqualTo(asked);
        assertThat(visit.getPtaBerth()).isEqualTo(asked);
        assertThat(requestsTo("V006")).hasSize(1);
    }

    @Test
    void aReleasedBerthMovesUpTheShipBookedBehind() {
        berthsComeFreeHoursApart();
        scheduledIn("V005", Duration.ofHours(1));   // behind V001 on B1
        visitService.arrived("V001", Instant.now());

        berthService.release("B1");

        // V005 is first on B1 now, from its own estimate
        assertThat(lineOf("B1")).containsExactly("V005");
        assertThat(visitOf("V005").getRtaBerth()).isEqualTo(visitOf("V005").getEtaBerth());
    }

    @Test
    void aBerthsPlannedEndFollowsTheActualArrivalAndSoDoTheWaitingShips() {
        berthsComeFreeHoursApart();
        scheduledIn("V005", Duration.ofHours(1));
        // an actual time cannot be in the future, so the arrival differs from the estimate by being early
        Instant early = Instant.now().truncatedTo(ChronoUnit.SECONDS);   // two hours before V001's estimate

        visitService.arrived("V001", early);

        BerthAllocation allocation = allocations.findAll().get(0);
        assertThat(allocation.getStatus()).isEqualTo(AllocationStatus.ACTIVE);
        assertThat(allocation.getPlannedEnd()).isEqualTo(early.plus(STAY));
        assertThat(visitOf("V005").getRtaBerth()).isEqualTo(early.plus(STAY));
        assertThat(requestsTo("V005")).hasSize(2);
    }

    @Test
    void theTerminalCancelsACallAndItsBerthGoesToAShipItCanServeMuchSooner() {
        List.of("V001", "V002", "V003", "V004").forEach(this::schedule);
        schedule("V005");   // behind V001 on B1

        visitService.cancelByTerminal(visitOf("V002").getVisitRef(), " no answer to the requested arrival ");

        VesselVisit cancelled = visitOf("V002");
        assertThat(cancelled.getPhase()).isEqualTo(VisitPhase.CANCELLED);
        assertThat(cancelled.getCancelledBy()).isEqualTo(EventSource.TERMINAL);
        assertThat(cancelled.getRemarks()).isEqualTo("Cancelled by the terminal: no answer to the requested arrival");
        assertThat(holderOf("B2")).isEqualTo("V005");
        // the vessel may announce itself again; with every berth taken it is booked after a ship there
        assertThat(schedule("V002").status()).isEqualTo("BERTHED");
        assertThat(lineOf("B2")).doesNotContain("V002").startsWith("V005");
    }

    @Test
    void theTerminalCannotCancelACallThatIsAlongsideOverOrUnknown() {
        schedule("V001");
        schedule("V002");
        String alongside = visitOf("V001").getVisitRef();
        String over = visitOf("V002").getVisitRef();
        visitService.arrived("V001", Instant.now());
        visitService.omit("V002");

        assertThatThrownBy(() -> visitService.cancelByTerminal(alongside, null))
                .isInstanceOf(VisitConflictException.class).hasMessageContaining("alongside");
        assertThatThrownBy(() -> visitService.cancelByTerminal(over, null))
                .isInstanceOf(VisitConflictException.class).hasMessageContaining("already over");
        assertThatThrownBy(() -> visitService.cancelByTerminal("VV-NOT-THERE", null))
                .isInstanceOf(VisitNotFoundException.class);
        assertThat(visitOf("V001").getPhase()).isEqualTo(VisitPhase.BERTHED);
        assertThat(visitOf("V002").getCancelledBy()).isEqualTo(EventSource.VESSEL);
    }

    // ---- events checked against the state of the call (gaps found by live testing, 2026-10-14)

    @Test
    void aVesselAlongsideCannotOmitItsCall() {
        schedule("V001");
        visitService.arrived("V001", Instant.now());

        assertThatThrownBy(() -> visitService.omit("V001"))
                .isInstanceOf(VisitConflictException.class).hasMessageContaining("is alongside");
        assertThat(visitOf("V001").getPhase()).isEqualTo(VisitPhase.BERTHED);
        assertThat(holderOf("B1")).isEqualTo("V001");
        // so a ship that comes next is not booked onto the berth it lies at
        schedule("V002");
        assertThat(holderOf("B2")).isEqualTo("V002");
    }

    @Test
    void aDepartureNeedsAnArrival() {
        schedule("V001");

        assertThatThrownBy(() -> visitService.departed("V001", Instant.now()))
                .isInstanceOf(VisitConflictException.class).hasMessageContaining("has not arrived");
        assertThat(visitOf("V001").getPhase()).isEqualTo(VisitPhase.INBOUND);
        assertThat(holderOf("B1")).isEqualTo("V001");
    }

    @Test
    void aDepartureKeepsTheTimeTheVesselSent() {
        schedule("V001");
        Instant arrivedAt = Instant.now().minus(Duration.ofHours(3)).truncatedTo(ChronoUnit.SECONDS);
        Instant leftAt = arrivedAt.plus(Duration.ofHours(2));
        visitService.arrived("V001", arrivedAt);

        visitService.departed("V001", leftAt);

        VesselVisit visit = visitOf("V001");
        assertThat(visit.getAtdBerth()).isEqualTo(leftAt);
        assertThat(events.findLedger(visit)).filteredOn(e -> e.getMilestone() == Milestone.DEPARTURE_BERTH)
                .singleElement().satisfies(e -> {
                    assertThat(e.getEventTime()).isEqualTo(leftAt);
                    assertThat(e.getSource()).isEqualTo(EventSource.VESSEL);
                    assertThat(e.getBerth().getCode()).isEqualTo("B1");
                });
        assertThat(holderOf("B1")).isNull();
    }

    @Test
    void aDepartureBeforeTheArrivalIsRefused() {
        schedule("V001");
        Instant arrivedAt = Instant.now().minus(Duration.ofHours(1));
        visitService.arrived("V001", arrivedAt);

        assertThatThrownBy(() -> visitService.departed("V001", arrivedAt.minus(Duration.ofMinutes(30))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("before the arrival");
        assertThat(holderOf("B1")).isEqualTo("V001");
    }

    @Test
    void anActualTimeInTheFutureIsRefused() {
        schedule("V001");
        Instant later = Instant.now().plus(Duration.ofHours(5));

        assertThatThrownBy(() -> visitService.arrived("V001", later))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be in the future");
        assertThat(visitOf("V001").getAtaBerth()).isNull();

        visitService.arrived("V001", Instant.now().plus(Duration.ofMinutes(2))); // a clock a little ahead is fine
        assertThatThrownBy(() -> visitService.departed("V001", later))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be in the future");
        assertThat(visitOf("V001").getPhase()).isEqualTo(VisitPhase.BERTHED);
    }

    @Test
    void anEstimateFromAVesselAlongsideIsRefused() {
        schedule("V001");
        visitService.arrived("V001", Instant.now());
        Instant eta = visitOf("V001").getEtaBerth();

        assertThatThrownBy(() -> visitService.declareEta("V001", Instant.now().plus(Duration.ofHours(8)), POSITION))
                .isInstanceOf(VisitConflictException.class).hasMessageContaining("is alongside");
        assertThat(visitOf("V001").getEtaBerth()).isEqualTo(eta);
    }

    @Test
    void aBookedVesselSendingItsEstimateAgainKeepsItsAcceptance() {
        Instant eta = Instant.now().plus(SAILING).truncatedTo(ChronoUnit.SECONDS);
        visitService.resolveVessel(null, null, "V001", null);
        Scheduled booked = visitService.declareEta("V001", eta, POSITION, ANY_SIZE);
        visitService.confirm("V001", booked.requestedArrival());

        visitService.declareEta("V001", eta, POSITION);                              // the same estimate again
        visitService.declareEta("V001", eta.plus(Duration.ofMinutes(10)), POSITION); // and a small change

        assertThat(visitOf("V001").getPtaBerth()).isEqualTo(booked.requestedArrival());
        assertThat(requestsTo("V001")).hasSize(1);
        assertThat(holderOf("B1")).isEqualTo("V001");

        // a change of an hour is told to the vessel, which then has to accept again
        visitService.declareEta("V001", eta.plus(Duration.ofHours(1)), POSITION);
        assertThat(visitOf("V001").getPtaBerth()).isNull();
        assertThat(requestsTo("V001")).hasSize(2);
    }

    // ---- overstays

    @Test
    void aVesselStillAlongsideAfterItsPlannedEndKeepsItsBerthAndIsFlagged() {
        Instant arrived = overstayingOnB1();
        Scheduled next = scheduledIn("V005", Duration.ofMinutes(10));

        // V001 is taken to leave one overstay step from now, so V005 is not told to come into an occupied berth
        assertThat(lineOf("B1")).containsExactly("V001", "V005");
        assertThat(next.assignment()).isEqualTo(new Assignment("BERTHED", "B1", "Berth 1", null));
        assertThat(next.requestedArrival()).isCloseTo(Instant.now().plus(Duration.ofMinutes(60)),
                within(1, ChronoUnit.MINUTES));
        assertThat(views.visits("V001", false).get(0).overstayingSince()).isEqualTo(arrived.plus(STAY));
        assertThat(views.visits("V005", false).get(0).overstayingSince()).isNull();
    }

    @Test
    void thePlannersExpectedDepartureMovesThePlannedEndAndTheWaitingShip() {
        overstayingOnB1();
        scheduledIn("V005", Duration.ofMinutes(10));
        Instant leaves = Instant.now().plus(Duration.ofHours(3)).truncatedTo(ChronoUnit.SECONDS);

        visitService.expectCompletion(visitOf("V001").getVisitRef(), leaves, " Crane breakdown ");

        assertThat(allocations.findByVisitAndStatusIn(visitOf("V001"), AllocationStatus.OPEN).orElseThrow()
                .getPlannedEnd()).isEqualTo(leaves);
        assertThat(events.findLedger(visitOf("V001")))
                .filteredOn(e -> e.getMilestone() == Milestone.CARGO_OPS_COMPLETE).singleElement()
                .satisfies(e -> {
                    assertThat(e.getClassifier()).isEqualTo(TimeClassifier.ESTIMATED);
                    assertThat(e.getSource()).isEqualTo(EventSource.TERMINAL);
                    assertThat(e.getEventTime()).isEqualTo(leaves);
                    assertThat(e.getBerth().getCode()).isEqualTo("B1");
                    assertThat(e.getRemarks()).isEqualTo("Crane breakdown");
                });
        assertThat(visitOf("V005").getRtaBerth()).isEqualTo(leaves);
        assertThat(requestsTo("V005")).hasSize(2);
        assertThat(views.visits("V001", false).get(0).overstayingSince()).isNull();
    }

    @Test
    void anExpectedDepartureIsForAVesselAlongsideAndInTheFuture() {
        schedule("V001");
        String visitRef = visitOf("V001").getVisitRef();
        Instant later = Instant.now().plus(Duration.ofHours(3));

        assertThatThrownBy(() -> visitService.expectCompletion(visitRef, later, null))
                .isInstanceOf(VisitConflictException.class).hasMessageContaining("is not alongside");
        visitService.arrived("V001", Instant.now());
        assertThatThrownBy(() -> visitService.expectCompletion(visitRef, Instant.now().minus(Duration.ofHours(1)), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be in the future");
        assertThatThrownBy(() -> visitService.expectCompletion("VV-NOT-THERE", later, null))
                .isInstanceOf(VisitNotFoundException.class);
    }

    // ---- booking horizon

    @Test
    void aVesselDueBeyondSevenDaysKeepsItsPlaceButHoldsNoBerthUntilItIsCloser() {
        Scheduled far = scheduledIn("V001", Duration.ofDays(10));

        assertThat(far.assignment()).isEqualTo(new Assignment("QUEUED", null, null, 1));
        assertThat(far.requestedArrival()).isCloseTo(Instant.now().plus(Duration.ofDays(10)), within(1, ChronoUnit.MINUTES));
        assertThat(allocations.findByVisitAndStatusIn(visitOf("V001"), AllocationStatus.OPEN)).isEmpty();
        assertThat(requestsTo("V001")).singleElement()
                .satisfies(e -> assertThat(e.getRemarks()).isEqualTo("A berth is given within 7 days of arrival"));

        // a ship due soon is booked at once: the far one blocks no berth
        schedule("V002");
        assertThat(holderOf("B1")).isEqualTo("V002");

        // once its estimate is inside seven days it is booked, on a berth that is free, not the occupied one
        Scheduled closer = scheduledIn("V001", Duration.ofDays(3));
        assertThat(closer.assignment().status()).isEqualTo("BERTHED");
        assertThat(closer.assignment().berthId()).isEqualTo("B2");
    }

    @Test
    void aBookedVesselWhoseEstimateMovesBeyondSevenDaysGivesItsBerthUp() {
        scheduledIn("V001", Duration.ofHours(2));
        assertThat(holderOf("B1")).isEqualTo("V001");

        Scheduled moved = scheduledIn("V001", Duration.ofDays(9));

        assertThat(moved.assignment()).isEqualTo(new Assignment("QUEUED", null, null, 1));
        assertThat(holderOf("B1")).isNull();
        assertThat(requestsTo("V001")).last().satisfies(e -> assertThat(e.getRemarks()).isEqualTo(
                "Berth B1 withdrawn: the new estimate is more than 7 days away. A berth is given within 7 days of arrival"));
    }

    // ---- fit

    @Test
    void aVesselTooLongForTheSmallBerthsIsBookedOnALargeOneAndASmallOneBehindItGoesFirst() {
        Instant before = Instant.now();
        scheduledIn("V001", Duration.ofHours(2));
        scheduledIn("V002", Duration.ofHours(5));          // B1 and B2, the 190 m berths, are taken

        Scheduled large = sized("V003", Duration.ofHours(1), new VesselSize(180.0, 28.0, 9.0));

        // B3 and B4 are free but take 140 m at most, so it is booked on B1 after V001
        assertThat(large.assignment()).isEqualTo(new Assignment("BERTHED", "B1", "Berth 1", null));
        assertThat(large.requestedArrival()).isCloseTo(before.plus(Duration.ofHours(2)).plus(STAY),
                within(30, ChronoUnit.SECONDS));
        assertThat(holderOf("B3")).isNull();
        // a small vessel behind it in line takes a berth the large one could not
        assertThat(sized("V004", Duration.ofHours(1), new VesselSize(120.0, 20.0, 6.0)).assignment().berthId())
                .isEqualTo("B3");
    }

    @Test
    void aVesselThatFitsNoBerthIsRefusedAndNoCallIsOpened() {
        visitService.resolveVessel(null, null, "GIANT", null);

        assertThatThrownBy(() -> visitService.declareEta("GIANT", Instant.now().plus(SAILING), POSITION,
                new VesselSize(250.0, 40.0, 12.0)))
                .isInstanceOf(VisitConflictException.class)
                .hasMessageContaining("length 250 m")
                .hasMessageContaining("the largest berth here takes length 190 m, beam 32 m, draft 10.5 m");
        assertThat(visits.count()).isZero();
    }

    // ---- maintenance

    @Test
    void noVesselIsBookedForAStayThatRunsIntoMaintenance() {
        Instant now = Instant.now();
        berthService.scheduleMaintenance("B1", now.plus(Duration.ofHours(4)), now.plus(Duration.ofHours(10)), "Crane rail");

        // due in two hours, a stay of almost a day would run into B1's window
        assertThat(schedule("V001").berthId()).isEqualTo("B2");
        assertThat(holderOf("B1")).isNull();
    }

    @Test
    void waitingVesselsArePlannedPastMaintenance() {
        Instant before = Instant.now();
        berthsComeFreeHoursApart();
        Instant b1Free = before.plus(Duration.ofHours(2)).plus(STAY);
        berthService.scheduleMaintenance("B1", b1Free.plus(Duration.ofMinutes(5)), b1Free.plus(Duration.ofHours(6)), null);

        Scheduled waiting = scheduledIn("V005", Duration.ofHours(1));

        // B1 comes free first, but not for long enough: V005 is asked for when B2 comes free instead
        assertThat(waiting.requestedArrival()).isCloseTo(before.plus(Duration.ofHours(5)).plus(STAY),
                within(30, ChronoUnit.SECONDS));
    }

    @Test
    void maintenanceOverABookedShipMovesItAroundTheWindow() {
        List.of("V001", "V002", "V003", "V004").forEach(this::schedule);   // every berth booked from two hours out
        Instant now = Instant.now();
        Instant from = now.plus(Duration.ofHours(3)).truncatedTo(ChronoUnit.SECONDS);
        Instant to = now.plus(Duration.ofHours(5)).truncatedTo(ChronoUnit.SECONDS);

        berthService.scheduleMaintenance("B1", from, to, "Fenders");

        // V001 loses its window on B1 and is planned again: B1 after the window is the soonest
        assertThat(lineOf("B1")).containsExactly("V001");
        assertThat(visitOf("V001").getRtaBerth()).isEqualTo(to);
        assertThat(requestsTo("V001")).last().satisfies(e ->
                assertThat(e.getRemarks()).isEqualTo("Moved for maintenance on B1, " + from + " to " + to));
        assertThat(maintenance.count()).isEqualTo(1);
    }

    @Test
    void maintenanceOverAShipAlongsideOrAnotherWindowIsRefused() {
        schedule("V001");
        visitService.arrived("V001", Instant.now());
        Instant now = Instant.now();

        assertThatThrownBy(() -> berthService.scheduleMaintenance("B1", now.plus(Duration.ofHours(3)),
                now.plus(Duration.ofHours(5)), null))
                .isInstanceOf(VisitConflictException.class).hasMessageContaining("has V001 alongside until");
        berthService.scheduleMaintenance("B2", now.plus(Duration.ofHours(3)), now.plus(Duration.ofHours(5)), "Fenders");
        assertThatThrownBy(() -> berthService.scheduleMaintenance("B2", now.plus(Duration.ofHours(4)),
                now.plus(Duration.ofHours(6)), null))
                .isInstanceOf(VisitConflictException.class).hasMessageContaining("already has maintenance");
        // after the vessel alongside is expected to leave is free time
        berthService.scheduleMaintenance("B1", now.plus(STAY).plus(Duration.ofHours(1)),
                now.plus(STAY).plus(Duration.ofHours(2)), null);

        assertThat(maintenance.count()).isEqualTo(2);
    }

    @Test
    void removingAWindowPlansTheBookedVesselsWithoutIt() {
        Instant before = Instant.now();
        scheduledIn("V001", Duration.ofHours(2));
        scheduledIn("V002", Duration.ofHours(9));
        scheduledIn("V003", Duration.ofHours(10));
        scheduledIn("V004", Duration.ofHours(11));
        Instant b1Free = before.plus(Duration.ofHours(2)).plus(STAY);
        long id = berthService.scheduleMaintenance("B1", b1Free.plus(Duration.ofMinutes(5)),
                b1Free.plus(Duration.ofHours(8)), null).getId();
        scheduledIn("V005", Duration.ofHours(1));
        assertThat(lineOf("B2")).containsExactly("V002", "V005");   // B1 is not free long enough before the window

        assertThat(berthService.removeMaintenance("B1", id)).isTrue();
        assertThat(berthService.removeMaintenance("B1", id)).isFalse();

        // without the window B1 takes V005 seven hours sooner, so it moves there
        assertThat(lineOf("B1")).containsExactly("V001", "V005");
        assertThat(visitOf("V005").getRtaBerth()).isCloseTo(b1Free, within(30, ChronoUnit.SECONDS));
    }

    // ---- a booked vessel that falls behind

    @Test
    void aBookedVesselFarBehindGivesItsBerthToAShipThatCanComeSooner() {
        Instant agreed = threeBerthsTakenAndV004BookedOnB4();
        scheduledIn("V005", Duration.ofHours(1));   // booked behind V001 on B1, a day out

        Scheduled afterwards = visitService.declareEta("V004", agreed.plus(Duration.ofHours(7)), POSITION);

        assertThat(holderOf("B4")).isEqualTo("V005");
        assertThat(afterwards.assignment().status()).isEqualTo("BERTHED");   // planned again, after V005
        VesselVisit visit = visitOf("V004");
        assertThat(visit.getPtaBerth()).isNull();
        assertThat(visit.getRtaBerth()).isAfterOrEqualTo(agreed.plus(Duration.ofHours(7)));
        assertThat(requestsTo("V004")).last().satisfies(e -> assertThat(e.getRemarks())
                .startsWith("Berth B4 withdrawn: this estimate is 7 h after the agreed arrival"));
        assertThat(requestsTo("V005")).last().satisfies(e -> {
            assertThat(e.getBerth().getCode()).isEqualTo("B4");
            assertThat(e.getRemarks()).isEqualTo("Given up by V004, now expected 7 h after its agreed arrival");
        });
    }

    @Test
    void aBookedVesselWithinTheToleranceKeepsItsBerthAtTheNewTime() {
        Instant agreed = threeBerthsTakenAndV004BookedOnB4();
        scheduledIn("V005", Duration.ofHours(1));

        visitService.declareEta("V004", agreed.plus(Duration.ofHours(3)), POSITION);

        assertThat(holderOf("B4")).isEqualTo("V004");
        assertThat(visitOf("V004").getRtaBerth()).isEqualTo(agreed.plus(Duration.ofHours(3)));
    }

    @Test
    void aBookedVesselFarBehindKeepsItsBerthWhenNobodyWaitingCanComeSooner() {
        Instant agreed = threeBerthsTakenAndV004BookedOnB4();

        visitService.declareEta("V004", agreed.plus(Duration.ofHours(7)), POSITION);

        assertThat(holderOf("B4")).isEqualTo("V004");
        assertThat(visitOf("V004").getRtaBerth()).isEqualTo(agreed.plus(Duration.ofHours(7)));
    }

    @Test
    void aShipDueSoonUsesTheGapBeforeABookingWithoutDelayingIt() {
        Instant before = Instant.now();
        berthsComeFreeHoursApart();
        Scheduled later = scheduledIn("V005", Duration.ofHours(60));   // B1, after V001 and a gap of a day and a half
        assertThat(later.assignment().berthId()).isEqualTo("B1");
        Instant laterAsked = visitOf("V005").getRtaBerth();

        Scheduled soon = scheduledIn("V006", Duration.ofHours(1));

        // V006 fits between V001 and V005 on B1; V005 keeps its time
        assertThat(lineOf("B1")).containsExactly("V001", "V006", "V005");
        assertThat(soon.requestedArrival()).isCloseTo(before.plus(Duration.ofHours(2)).plus(STAY),
                within(30, ChronoUnit.SECONDS));
        assertThat(visitOf("V005").getRtaBerth()).isEqualTo(laterAsked);
        assertThat(requestsTo("V005")).hasSize(1);
    }

    /** B1 to B3 taken; V004, due in three hours, books B4 and accepts. Returns the arrival agreed for V004. */
    private Instant threeBerthsTakenAndV004BookedOnB4() {
        scheduledIn("V001", Duration.ofHours(2));
        scheduledIn("V002", Duration.ofHours(5));
        scheduledIn("V003", Duration.ofHours(8));
        Instant agreed = scheduledIn("V004", Duration.ofHours(3)).requestedArrival();
        visitService.confirm("V004", null);
        return agreed;
    }

    /** As {@link #scheduledIn}, for a vessel of the given size. */
    private Scheduled sized(String vesselId, Duration sailing, VesselSize size) {
        visitService.resolveVessel(null, null, vesselId, null);
        return visitService.declareEta(vesselId, Instant.now().plus(sailing), POSITION, size);
    }

    /** Four vessels take the four berths, due three hours apart, so the berths come free three hours apart. */
    private void berthsComeFreeHoursApart() {
        scheduledIn("V001", Duration.ofHours(2));
        scheduledIn("V002", Duration.ofHours(5));
        scheduledIn("V003", Duration.ofHours(8));
        scheduledIn("V004", Duration.ofHours(11));
    }

    /** Every arrival time the terminal has requested of the vessel, oldest first. */
    private List<VisitEvent> requestsTo(String vesselId) {
        VesselVisit visit = visitOf(vesselId);
        return events.findLedger(visit).stream()
                .filter(e -> e.getClassifier() == TimeClassifier.REQUESTED).toList();
    }

    /**
     * V001 on B1, alongside since 30 hours ago: longer than a typical stay, so
     * its planned end has passed. B2 to B4 are booked by ships due in two hours.
     * Returns when V001 arrived.
     */
    private Instant overstayingOnB1() {
        Instant arrived = Instant.now().minus(Duration.ofHours(30)).truncatedTo(ChronoUnit.SECONDS);
        scheduledIn("V001", Duration.ofHours(-30));
        visitService.arrived("V001", arrived);
        List.of("V002", "V003", "V004").forEach(this::schedule);
        return arrived;
    }

    private VesselVisit visitOf(String vesselId) {
        return visits.findAll().stream()
                .filter(v -> v.getVessel().getCode().equals(vesselId)).findFirst().orElseThrow();
    }

    /**
     * The vessels on a berth, read the way the console reads them: the one
     * alongside, then those booked, in the order they are planned.
     */
    private List<String> lineOf(String berthCode) {
        return views.visits(null, false).stream()
                .filter(v -> v.berth() != null && v.berth().berthCode().equals(berthCode))
                .sorted(Comparator.comparing((VisitView v) -> v.berth().status() != AllocationStatus.ACTIVE)
                        .thenComparing(v -> v.berth().plannedStart()))
                .map(v -> v.vessel().code())
                .toList();
    }

    /** The first vessel on the berth: the one alongside, or the next booked; null if the berth has none. */
    private String holderOf(String berthCode) {
        List<String> line = lineOf(berthCode);
        return line.isEmpty() ? null : line.get(0);
    }

    /** The vessel's open allocation. */
    private BerthAllocation windowOf(String vesselId) {
        return allocations.findByVisitAndStatusIn(visitOf(vesselId), AllocationStatus.OPEN).orElseThrow();
    }

    private Assignment schedule(String vesselId) {
        return scheduled(vesselId, null).assignment();
    }

    /** The vessel's operator declares an estimated arrival two hours out, as a DCSA estimate would. */
    private Scheduled scheduled(String vesselId, VesselType type) {
        visitService.resolveVessel(null, null, vesselId, type);
        return visitService.declareEta(vesselId, Instant.now().plus(SAILING), POSITION, ANY_SIZE);
    }

    /** As {@link #scheduled}, for a vessel of no stated type that is this far from arriving. */
    private Scheduled scheduledIn(String vesselId, Duration sailing) {
        visitService.resolveVessel(null, null, vesselId, null);
        return visitService.declareEta(vesselId, Instant.now().plus(sailing), POSITION, ANY_SIZE);
    }
}
