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
    void berthsFillInOrderThenVesselsQueue() {
        assertThat(schedule("V001")).isEqualTo(new Assignment("BERTHED", "B1", "Berth 1", null));
        assertThat(schedule("V002").berthId()).isEqualTo("B2");
        assertThat(schedule("V003").berthId()).isEqualTo("B3");
        assertThat(schedule("V004").berthId()).isEqualTo("B4");

        assertThat(schedule("V005")).isEqualTo(new Assignment("QUEUED", null, null, 1));
        assertThat(schedule("V006")).isEqualTo(new Assignment("QUEUED", null, null, 2));
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

        assertThat(berthService.release("B2")).isTrue();

        assertThat(schedule("V006").berthId()).isEqualTo("B2");
    }

    @Test
    void releaseClosesTheAllocationAndTheVisit() {
        schedule("V001");

        berthService.release("B1");

        VesselVisit visit = visits.findAll().get(0);
        BerthAllocation allocation = allocations.findAll().get(0);
        assertThat(visit.getPhase()).isEqualTo(VisitPhase.DEPARTED);
        assertThat(visit.getAtdBerth()).isNotNull();
        assertThat(allocation.getStatus()).isEqualTo(AllocationStatus.COMPLETED);
        assertThat(allocation.getActualEnd()).isNotNull();
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
        visitService.declareEta("V001", Instant.now().plus(SAILING), null);

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
    void queuedVesselKeepsItsPlaceAndGetsABerthWhenItAsksAgainAfterARelease() {
        List.of("V001", "V002", "V003", "V004").forEach(this::schedule);
        schedule("V005");
        schedule("V006");

        assertThat(schedule("V006").queueNumber()).isEqualTo(2);

        berthService.release("B1");

        assertThat(schedule("V005")).isEqualTo(new Assignment("BERTHED", "B1", "Berth 1", null));
        assertThat(schedule("V006").queueNumber()).isEqualTo(1);
    }

    @Test
    void aVesselCanCallAgainAfterItHasDeparted() {
        schedule("V001");
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
    void aQueuedVesselIsAskedToArriveWhenABerthIsExpectedFree() {
        Instant before = Instant.now();
        List.of("V001", "V002", "V003", "V004").forEach(this::schedule);

        Scheduled queued = scheduled("V005", null);

        // each berth is planned free one typical stay after its vessel's ETA; the first to free is B1
        Instant berthFree = before.plus(SAILING).plus(PlanningRules.typicalStay(VesselType.OTHER));
        assertThat(queued.assignment()).isEqualTo(new Assignment("QUEUED", null, null, 1));
        assertThat(queued.requestedArrival()).isCloseTo(berthFree, within(30, ChronoUnit.SECONDS));

        VesselVisit visit = visitOf("V005");
        assertThat(visit.getRtaBerth()).isEqualTo(queued.requestedArrival());
        assertThat(visit.getPtaBerth()).isNull();
        assertThat(events.findAll()).filteredOn(e -> e.getVisit().equals(visit))
                .filteredOn(e -> e.getClassifier() == TimeClassifier.REQUESTED)
                .singleElement().satisfies(e -> assertThat(e.getRemarks()).startsWith("Berth expected free"));
    }

    @Test
    void aReleasedBerthGoesToTheHeadOfTheQueueWithoutItAsking() {
        List.of("V001", "V002", "V003", "V004").forEach(this::schedule);
        schedule("V005");
        schedule("V006");

        berthService.release("B3");

        assertThat(holderOf("B3")).isEqualTo("V005");
        assertThat(schedule("V006").queueNumber()).isEqualTo(1);
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
                .singleElement().satisfies(e -> assertThat(e.getSource()).isEqualTo(EventSource.VESSEL));
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
        List.of("V001", "V002", "V003", "V004").forEach(this::schedule);
        schedule("V005");
        visitService.confirm("V005", null);   // confirmed for when a berth was expected free

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
        schedule("V005");
        schedule("V006");

        visitService.omit("V005");

        assertThat(schedule("V006").queueNumber()).isEqualTo(1);
        assertThat(List.of("B1", "B2", "B3", "B4")).extracting(this::holderOf)
                .containsExactly("V001", "V002", "V003", "V004");
    }

    @Test
    void whenAShipAheadOmitsItsCallTheNextWaitingShipIsAskedToArriveEarlier() {
        Instant before = Instant.now();
        berthsComeFreeHoursApart();
        scheduledIn("V005", Duration.ofHours(1));
        scheduledIn("V006", Duration.ofHours(1));
        visitService.confirm("V006", null);   // accepted for when the second berth comes free

        visitService.omit("V005");

        // V006 is now first in line, so it is asked for when the first berth comes free: three hours sooner
        VesselVisit visit = visitOf("V006");
        assertThat(visit.getRtaBerth()).isCloseTo(before.plus(Duration.ofHours(2)).plus(STAY),
                within(30, ChronoUnit.SECONDS));
        assertThat(visit.getPtaBerth()).isNull();
        assertThat(requestsTo("V006")).hasSize(2)
                .allSatisfy(e -> assertThat(e.getRemarks()).startsWith("Berth expected free"));
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
    void aReleasedBerthMovesUpTheShipsStillWaiting() {
        Instant before = Instant.now();
        berthsComeFreeHoursApart();
        scheduledIn("V005", Duration.ofHours(1));
        scheduledIn("V006", Duration.ofHours(1));

        berthService.release("B1");

        // V005 takes B1 from its own estimate, and V006 is next for it one stay later
        assertThat(holderOf("B1")).isEqualTo("V005");
        assertThat(visitOf("V006").getRtaBerth()).isCloseTo(before.plus(Duration.ofHours(1)).plus(STAY),
                within(30, ChronoUnit.SECONDS));
    }

    @Test
    void aBerthsPlannedEndFollowsTheActualArrivalAndSoDoTheWaitingShips() {
        berthsComeFreeHoursApart();
        scheduledIn("V005", Duration.ofHours(1));
        Instant late = Instant.now().plus(Duration.ofHours(4));   // two hours after V001's estimate

        visitService.arrived("V001", late);

        BerthAllocation allocation = allocations.findAll().get(0);
        assertThat(allocation.getStatus()).isEqualTo(AllocationStatus.ACTIVE);
        assertThat(allocation.getPlannedEnd()).isEqualTo(late.plus(STAY));
        assertThat(visitOf("V005").getRtaBerth()).isEqualTo(late.plus(STAY));
        assertThat(requestsTo("V005")).hasSize(2);
    }

    @Test
    void theTerminalCancelsACallAndItsBerthGoesToTheHeadOfTheQueue() {
        List.of("V001", "V002", "V003", "V004").forEach(this::schedule);
        schedule("V005");

        visitService.cancelByTerminal(visitOf("V002").getVisitRef(), " no answer to the requested arrival ");

        VesselVisit cancelled = visitOf("V002");
        assertThat(cancelled.getPhase()).isEqualTo(VisitPhase.CANCELLED);
        assertThat(cancelled.getCancelledBy()).isEqualTo(EventSource.TERMINAL);
        assertThat(cancelled.getRemarks()).isEqualTo("Cancelled by the terminal: no answer to the requested arrival");
        assertThat(holderOf("B2")).isEqualTo("V005");
        // the vessel may announce itself again; with the port full it waits like anyone else
        assertThat(schedule("V002")).isEqualTo(new Assignment("QUEUED", null, null, 1));
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

    // ---- fit

    @Test
    void aVesselTooLongForTheSmallBerthsWaitsForALargeOneAndASmallOneBehindItGoesFirst() {
        Instant before = Instant.now();
        scheduledIn("V001", Duration.ofHours(2));
        scheduledIn("V002", Duration.ofHours(5));          // B1 and B2, the 190 m berths, are taken

        Scheduled large = sized("V003", Duration.ofHours(1), new VesselSize(180.0, 28.0, 9.0));

        // B3 and B4 are free but take 140 m at most, so it waits for B1
        assertThat(large.assignment()).isEqualTo(new Assignment("QUEUED", null, null, 1));
        assertThat(large.requestedArrival()).isCloseTo(before.plus(Duration.ofHours(2)).plus(STAY),
                within(30, ChronoUnit.SECONDS));
        assertThat(holderOf("B3")).isNull();
        // a small vessel behind it in line takes a berth the large one could not
        assertThat(sized("V004", Duration.ofHours(1), new VesselSize(120.0, 20.0, 6.0)).assignment().berthId())
                .isEqualTo("B3");
        assertThat(views.visits("V003", false).get(0).queueNumber()).isEqualTo(1);
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
    void maintenanceOverABookingOrAnotherWindowIsRefused() {
        schedule("V001");   // B1 booked from two hours out, for one stay
        Instant now = Instant.now();

        assertThatThrownBy(() -> berthService.scheduleMaintenance("B1", now.plus(Duration.ofHours(3)),
                now.plus(Duration.ofHours(5)), null))
                .isInstanceOf(VisitConflictException.class).hasMessageContaining("booked for V001");
        berthService.scheduleMaintenance("B2", now.plus(Duration.ofHours(3)), now.plus(Duration.ofHours(5)), "Fenders");
        assertThatThrownBy(() -> berthService.scheduleMaintenance("B2", now.plus(Duration.ofHours(4)),
                now.plus(Duration.ofHours(6)), null))
                .isInstanceOf(VisitConflictException.class).hasMessageContaining("already has maintenance");
        // before the booking starts is free time
        berthService.scheduleMaintenance("B1", now, now.plus(Duration.ofHours(1)), null);

        assertThat(maintenance.count()).isEqualTo(2);
    }

    @Test
    void removingAWindowPlansTheWaitingVesselsWithoutIt() {
        Instant before = Instant.now();
        berthsComeFreeHoursApart();
        Instant b1Free = before.plus(Duration.ofHours(2)).plus(STAY);
        long id = berthService.scheduleMaintenance("B1", b1Free.plus(Duration.ofMinutes(5)),
                b1Free.plus(Duration.ofHours(6)), null).getId();
        scheduledIn("V005", Duration.ofHours(1));

        assertThat(berthService.removeMaintenance("B1", id)).isTrue();
        assertThat(berthService.removeMaintenance("B1", id)).isFalse();

        assertThat(visitOf("V005").getRtaBerth()).isCloseTo(b1Free, within(30, ChronoUnit.SECONDS));
    }

    // ---- a booked vessel that falls behind

    @Test
    void aBookedVesselFarBehindGivesItsBerthToAWaitingVesselThatCanComeSooner() {
        Instant agreed = threeBerthsTakenAndV004BookedOnB4();
        scheduledIn("V005", Duration.ofHours(1));   // waits for B1

        Scheduled afterwards = visitService.declareEta("V004", agreed.plus(Duration.ofHours(7)), POSITION);

        assertThat(holderOf("B4")).isEqualTo("V005");
        assertThat(afterwards.assignment().queueNumber()).isEqualTo(1);   // it keeps its place in line
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
    void aLateWaitingVesselKeepsItsPlaceButIsSkippedForOneThatCanComeSooner() {
        Instant before = Instant.now();
        berthsComeFreeHoursApart();
        Scheduled late = scheduledIn("V005", Duration.ofHours(40));
        Scheduled soon = scheduledIn("V006", Duration.ofHours(1));

        // V005 could not use B1 until 40 hours from now, so B1 goes to V006, behind it in line
        assertThat(soon.requestedArrival()).isCloseTo(before.plus(Duration.ofHours(2)).plus(STAY),
                within(30, ChronoUnit.SECONDS));
        assertThat(late.assignment().queueNumber()).isEqualTo(1);
        assertThat(visitOf("V005").getRtaBerth()).isEqualTo(visitOf("V005").getEtaBerth());
        assertThat(views.visits("V005", false).get(0).queueNumber()).isEqualTo(1);
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

    private VesselVisit visitOf(String vesselId) {
        return visits.findAll().stream()
                .filter(v -> v.getVessel().getCode().equals(vesselId)).findFirst().orElseThrow();
    }

    /** The vessel holding the berth, read the way the console reads it; null if the berth is free. */
    private String holderOf(String berthCode) {
        return views.visits(null, false).stream()
                .filter(v -> v.berth() != null && v.berth().berthCode().equals(berthCode))
                .map(v -> v.vessel().code())
                .findFirst().orElse(null);
    }

    private Assignment schedule(String vesselId) {
        return scheduled(vesselId, null).assignment();
    }

    /** The vessel's operator declares an estimated arrival two hours out, as a DCSA estimate would. */
    private Scheduled scheduled(String vesselId, VesselType type) {
        visitService.resolveVessel(null, null, vesselId, type);
        return visitService.declareEta(vesselId, Instant.now().plus(SAILING), POSITION);
    }

    /** As {@link #scheduled}, for a vessel of no stated type that is this far from arriving. */
    private Scheduled scheduledIn(String vesselId, Duration sailing) {
        visitService.resolveVessel(null, null, vesselId, null);
        return visitService.declareEta(vesselId, Instant.now().plus(sailing), POSITION);
    }
}
