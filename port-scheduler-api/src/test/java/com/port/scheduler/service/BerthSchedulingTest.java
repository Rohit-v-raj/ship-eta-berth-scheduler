package com.port.scheduler.service;

import com.port.scheduler.entity.vessel.Vessel;
import com.port.scheduler.entity.vessel.VesselType;
import com.port.scheduler.entity.visit.AllocationStatus;
import com.port.scheduler.entity.visit.BerthAllocation;
import com.port.scheduler.entity.visit.EventSource;
import com.port.scheduler.entity.visit.Milestone;
import com.port.scheduler.entity.visit.TimeClassifier;
import com.port.scheduler.entity.visit.VesselVisit;
import com.port.scheduler.entity.visit.VisitEvent;
import com.port.scheduler.entity.visit.VisitPhase;
import com.port.scheduler.exceptions.BerthNotFoundException;
import com.port.scheduler.exceptions.VisitConflictException;
import com.port.scheduler.exceptions.VisitNotFoundException;
import com.port.scheduler.model.EtaResponse;
import com.port.scheduler.model.ScheduleRequest;
import com.port.scheduler.repository.BerthAllocationRepository;
import com.port.scheduler.repository.EtaPredictionRepository;
import com.port.scheduler.repository.VesselRepository;
import com.port.scheduler.repository.VesselVisitRepository;
import com.port.scheduler.repository.VisitEventRepository;
import com.port.scheduler.service.BerthService.Assignment;
import com.port.scheduler.service.BerthService.BerthStatus;
import com.port.scheduler.service.VesselVisitService.Scheduled;
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
 * four seeded berths B1 to B4.
 */
@PersistenceTest
class BerthSchedulingTest {

    private static final double ETA_MINUTES = 120.0;

    @Autowired VesselVisitService visitService;
    @Autowired BerthService berthService;
    @Autowired VesselRepository vessels;
    @Autowired VesselVisitRepository visits;
    @Autowired BerthAllocationRepository allocations;
    @Autowired EtaPredictionRepository predictions;
    @Autowired VisitEventRepository events;

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
    void statusShowsWhichVesselHoldsEachBerth() {
        schedule("V001");
        schedule("V002");

        assertThat(berthService.status()).containsExactly(
                new BerthStatus("B1", "Berth 1", true, "V001"),
                new BerthStatus("B2", "Berth 2", true, "V002"),
                new BerthStatus("B3", "Berth 3", false, null),
                new BerthStatus("B4", "Berth 4", false, null));
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
    void schedulingRecordsTheVisitItsEtaAndTheTerminalsRequestedArrival() {
        Instant before = Instant.now();

        schedule("V001");

        VesselVisit visit = visits.findAll().get(0);
        assertThat(visit.getVisitRef()).startsWith("VV-");
        assertThat(visit.getPhase()).isEqualTo(VisitPhase.INBOUND);
        assertThat(visit.getVessel().getCode()).isEqualTo("V001");
        assertThat(visit.getEtaBerth()).isCloseTo(before.plus(Duration.ofMinutes(120)), within(30, ChronoUnit.SECONDS));
        // the terminal requests an arrival time; only the vessel can confirm it as planned
        assertThat(visit.getRtaBerth()).isEqualTo(visit.getEtaBerth());
        assertThat(visit.getPtaBerth()).isNull();
        assertThat(visit.getTerminal().getCode()).isEqualTo("FRO-CT");

        assertThat(predictions.findAll()).singleElement().satisfies(p -> {
            assertThat(p.getModelName()).isEqualTo("eta_xgb");
            assertThat(p.getEtaMinutes()).isEqualTo(ETA_MINUTES);
            assertThat(p.getNaiveEtaMinutes()).isEqualTo(42.5);
            assertThat(p.getSogKnots()).isEqualTo(12.0);
        });
        assertThat(events.findAll())
                .allSatisfy(e -> assertThat(e.getMilestone()).isEqualTo(Milestone.ARRIVAL_BERTH))
                .extracting(VisitEvent::getClassifier, VisitEvent::getSource)
                .containsExactlyInAnyOrder(
                        tuple(TimeClassifier.ESTIMATED, EventSource.ML_MODEL),
                        tuple(TimeClassifier.REQUESTED, EventSource.TERMINAL));
    }

    @Test
    void reschedulingAVesselRefreshesItsVisitInsteadOfTakingASecondBerth() {
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
    void aVesselWithAFreeBerthIsAskedToArriveAtItsEtaWithNoSpeedAdvice() {
        Scheduled berthed = scheduled("V001", null);

        VesselVisit visit = visits.findAll().get(0);
        assertThat(berthed.requestedArrival()).isEqualTo(visit.getEtaBerth());
        assertThat(berthed.advisedSpeedKnots()).isNull();
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
    void aQueuedVesselIsAskedToArriveWhenABerthIsExpectedFreeAndAdvisedToSlowDown() {
        Instant before = Instant.now();
        List.of("V001", "V002", "V003", "V004").forEach(this::schedule);

        Scheduled queued = scheduled("V005", null);

        // each berth is planned free one typical stay after its vessel's ETA; the first to free is B1
        Instant berthFree = before.plus(Duration.ofMinutes(120)).plus(PlanningRules.typicalStay(VesselType.OTHER));
        assertThat(queued.assignment()).isEqualTo(new Assignment("QUEUED", null, null, 1));
        assertThat(queued.requestedArrival()).isCloseTo(berthFree, within(30, ChronoUnit.SECONDS));
        // 8.5 nm with about a day in hand needs under 1 knot, so the advice is the economical floor
        assertThat(queued.advisedSpeedKnots()).isEqualTo(5.0);

        VesselVisit visit = visits.findAll().stream()
                .filter(v -> v.getVessel().getCode().equals("V005")).findFirst().orElseThrow();
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

        assertThat(berthService.status()).contains(new BerthStatus("B3", "Berth 3", true, "V005"));
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
        assertThat(berthService.status()).contains(new BerthStatus("B2", "Berth 2", true, "V005"));
    }

    @Test
    void omittingAQueuedCallTakesItOutOfTheQueue() {
        List.of("V001", "V002", "V003", "V004").forEach(this::schedule);
        schedule("V005");
        schedule("V006");

        visitService.omit("V005");

        assertThat(schedule("V006").queueNumber()).isEqualTo(1);
        assertThat(berthService.status()).extracting(BerthStatus::vesselId)
                .containsExactly("V001", "V002", "V003", "V004");
    }

    private VesselVisit visitOf(String vesselId) {
        return visits.findAll().stream()
                .filter(v -> v.getVessel().getCode().equals(vesselId)).findFirst().orElseThrow();
    }

    private Assignment schedule(String vesselId) {
        return scheduled(vesselId, null).assignment();
    }

    private Scheduled scheduled(String vesselId, VesselType type) {
        ScheduleRequest req = new ScheduleRequest(vesselId, 61.50, 4.90, 12.0, 45.0, 61.612, 5.070, type);
        EtaResponse eta = new EtaResponse(ETA_MINUTES, ETA_MINUTES / 60, 8.5, 42.5);
        return visitService.schedule(req, eta);
    }
}
