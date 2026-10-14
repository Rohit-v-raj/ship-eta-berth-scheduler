package com.port.scheduler.service;

import com.port.scheduler.entity.visit.AllocationStatus;
import com.port.scheduler.entity.visit.EventSource;
import com.port.scheduler.entity.visit.TimeClassifier;
import com.port.scheduler.entity.visit.VisitPhase;
import com.port.scheduler.exceptions.VisitNotFoundException;
import com.port.scheduler.model.view.BerthPlanView;
import com.port.scheduler.model.view.BerthPlanView.Lane;
import com.port.scheduler.model.view.BerthPlanView.Window;
import com.port.scheduler.model.view.PortView;
import com.port.scheduler.model.view.PortView.BerthView;
import com.port.scheduler.model.view.PortView.TerminalView;
import com.port.scheduler.model.view.VisitDetailView;
import com.port.scheduler.model.view.VisitDetailView.EventView;
import com.port.scheduler.model.view.VisitView;
import com.port.scheduler.service.VesselVisitService.ReportedPosition;
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

/** The console's read views, after real scheduling against real MySQL. */
@PersistenceTest
class PortViewServiceTest {

    @Autowired PortViewService views;
    @Autowired VesselVisitService visitService;
    @Autowired BerthService berthService;

    @Test
    void thePortIsDescribedWithItsTerminalsAndBerths() {
        PortView port = views.port();

        assertThat(port.unLocode()).isEqualTo("NOFRO");
        assertThat(port.timeZone()).isEqualTo("Europe/Oslo");
        assertThat(port.terminals()).extracting(TerminalView::code).containsExactly("FRO-CT", "FRO-MP");
        assertThat(port.berths()).extracting(BerthView::code, BerthView::terminalCode)
                .containsExactly(tuple("B1", "FRO-CT"), tuple("B2", "FRO-CT"),
                        tuple("B3", "FRO-MP"), tuple("B4", "FRO-MP"));
        assertThat(port.berths().get(0).latitude()).isEqualTo(61.612);
        assertThat(port.berths().get(0).longitude()).isEqualTo(5.07);
    }

    @Test
    void anOpenVisitShowsItsBerthOrItsQueuePlaceAndWhereTheVesselSaidItWas() {
        List.of("V001", "V002", "V003", "V004").forEach(this::schedule);
        Scheduled queued = schedule("V005");

        List<VisitView> visits = views.visits(null, false);

        assertThat(visits).extracting(v -> v.vessel().code())
                .containsExactly("V001", "V002", "V003", "V004", "V005");
        VisitView berthed = visits.get(0);
        assertThat(berthed.berth().berthCode()).isEqualTo("B1");
        assertThat(berthed.berth().status()).isEqualTo(AllocationStatus.PLANNED);
        assertThat(berthed.queueNumber()).isNull();
        assertThat(berthed.terminalCode()).isEqualTo("FRO-CT");
        assertThat(berthed.rtaBerth()).isEqualTo(berthed.etaBerth());

        VisitView waiting = visits.get(4);
        assertThat(waiting.berth()).isNull();
        assertThat(waiting.queueNumber()).isEqualTo(1);
        assertThat(waiting.rtaBerth()).isEqualTo(queued.requestedArrival());
        assertThat(waiting.lastReport().lat()).isEqualTo(61.50);
        assertThat(waiting.lastReport().lon()).isEqualTo(4.90);
        assertThat(waiting.lastReport().distanceNm()).isEqualTo(8.5);
        assertThat(waiting.lastReport().estimatedArrival()).isEqualTo(waiting.etaBerth());
    }

    @Test
    void aVisitWhoseEstimatesCarriedNoPositionHasNoLastReport() {
        visitService.declareEta("V001", Instant.now().plus(Duration.ofHours(2)), null);

        assertThat(views.visits("V001", false).get(0).lastReport()).isNull();
    }

    @Test
    void anAnswerIsDueOneWindowAfterTheRequestUntilTheVesselAcceptsIt() {
        Instant before = Instant.now();
        schedule("V001");

        VisitView awaiting = views.visits("V001", false).get(0);

        assertThat(awaiting.requestedAt()).isCloseTo(before, within(30, ChronoUnit.SECONDS));
        assertThat(awaiting.answerDueAt()).isEqualTo(awaiting.requestedAt().plus(Duration.ofMinutes(60)));
        assertThat(awaiting.cancelledBy()).isNull();

        visitService.confirm("V001", null);

        VisitView agreed = views.visits("V001", false).get(0);
        assertThat(agreed.requestedAt()).isEqualTo(awaiting.requestedAt());
        assertThat(agreed.answerDueAt()).isNull();
    }

    @Test
    void aClosedVisitOwesNoAnswerAndSaysWhoCancelledIt() {
        schedule("V001");
        schedule("V002");
        visitService.omit("V001");
        visitService.cancelByTerminal(views.visits("V002", false).get(0).visitRef(),
                "no answer to the requested arrival");

        assertThat(views.visits("V001", true)).singleElement().satisfies(v -> {
            assertThat(v.cancelledBy()).isEqualTo(EventSource.VESSEL);
            assertThat(v.remarks()).isEqualTo("Omitted by the vessel operator");
            assertThat(v.answerDueAt()).isNull();
        });
        assertThat(views.visits("V002", true)).singleElement().satisfies(v -> {
            assertThat(v.cancelledBy()).isEqualTo(EventSource.TERMINAL);
            assertThat(v.remarks()).isEqualTo("Cancelled by the terminal: no answer to the requested arrival");
            assertThat(v.answerDueAt()).isNull();
        });
    }

    @Test
    void aVesselHoldingABerthPastItsAgreedTimeIsShownAsNotArrived() {
        visitService.declareEta("V001", Instant.now().minus(Duration.ofHours(1)), null);

        VisitView visit = views.visits("V001", false).get(0);

        assertThat(visit.berth().berthCode()).isEqualTo("B1");
        assertThat(visit.notArrivedSince()).isEqualTo(visit.rtaBerth());
    }

    @Test
    void aVesselFarOutIsToldWhenTheNextEstimateIsExpected() {
        Instant eta = Instant.now().plus(Duration.ofHours(100));
        visitService.declareEta("V001", eta, null);

        VisitView visit = views.visits("V001", false).get(0);

        assertThat(visit.nextEstimateBy()).isEqualTo(eta.minus(Duration.ofHours(72)));
        assertThat(visit.estimateDueSince()).isNull();
        assertThat(visit.notArrivedSince()).isNull();
    }

    @Test
    void maintenanceIsListedAndShownOnItsBerthsLane() {
        Instant now = Instant.now();
        berthService.scheduleMaintenance("B3", now.plus(Duration.ofHours(3)), now.plus(Duration.ofHours(9)), "Dredging");

        assertThat(views.maintenance()).singleElement().satisfies(m -> {
            assertThat(m.berthCode()).isEqualTo("B3");
            assertThat(m.reason()).isEqualTo("Dredging");
        });
        BerthPlanView plan = views.berthPlan(now, now.plus(Duration.ofHours(24)));
        assertThat(plan.berths().get(2).maintenance()).extracting(m -> m.reason()).containsExactly("Dredging");
        assertThat(plan.berths().get(0).maintenance()).isEmpty();
        assertThat(views.berthPlan(now.plus(Duration.ofHours(10)), now.plus(Duration.ofHours(20)))
                .berths().get(2).maintenance()).isEmpty();
    }

    @Test
    void visitsCanBeNarrowedToOneVesselAndClosedOnesAreLeftOutUnlessAskedFor() {
        schedule("V001");
        schedule("V002");
        berthService.release("B1");

        assertThat(views.visits(null, false)).extracting(v -> v.vessel().code()).containsExactly("V002");
        assertThat(views.visits("V002", false)).hasSize(1);
        assertThat(views.visits("V001", false)).isEmpty();
        // newest first once closed visits are included
        assertThat(views.visits(null, true)).extracting(v -> v.vessel().code(), VisitView::phase)
                .containsExactly(tuple("V002", VisitPhase.INBOUND), tuple("V001", VisitPhase.DEPARTED));
        assertThat(views.visits("V001", true).get(0).berth().status()).isEqualTo(AllocationStatus.COMPLETED);
    }

    @Test
    void aVisitsDetailCarriesItsLedgerAndEveryPositionTheVesselGave() {
        schedule("V001");
        schedule("V001");   // a second estimate from the same vessel
        String visitRef = views.visits("V001", false).get(0).visitRef();

        VisitDetailView detail = views.visit(visitRef);

        assertThat(detail.visit().vessel().code()).isEqualTo("V001");
        assertThat(detail.reports()).hasSize(2);
        // each estimate is answered: the berth is already held, so the request follows the new time
        assertThat(detail.events()).extracting(EventView::classifier, EventView::source, EventView::berthCode)
                .containsExactly(
                        tuple(TimeClassifier.ESTIMATED, EventSource.VESSEL, null),
                        tuple(TimeClassifier.REQUESTED, EventSource.TERMINAL, "B1"),
                        tuple(TimeClassifier.ESTIMATED, EventSource.VESSEL, null),
                        tuple(TimeClassifier.REQUESTED, EventSource.TERMINAL, "B1"));
        assertThatThrownBy(() -> views.visit("VV-NOT-THERE")).isInstanceOf(VisitNotFoundException.class);
    }

    @Test
    void theBerthPlanHasALaneForEachBerthWithOpenWindowsAndTheCompletedOnesInThePeriod() {
        schedule("V001");
        schedule("V002");
        berthService.release("B1");
        schedule("V003");   // takes B1 now that V001 has left
        Instant now = Instant.now();

        BerthPlanView plan = views.berthPlan(now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(24)));

        assertThat(plan.berths()).extracting(Lane::berthCode).containsExactly("B1", "B2", "B3", "B4");
        assertThat(plan.berths().get(0).windows()).extracting(Window::vesselCode, Window::status)
                .containsExactly(tuple("V001", AllocationStatus.COMPLETED), tuple("V003", AllocationStatus.PLANNED));
        assertThat(plan.berths().get(1).windows()).extracting(Window::vesselCode).containsExactly("V002");
        assertThat(plan.berths().get(2).windows()).isEmpty();

        // a period that does not reach V001's window still shows whatever holds a berth now
        BerthPlanView earlier = views.berthPlan(now.minus(Duration.ofDays(3)), now.minus(Duration.ofDays(2)));
        assertThat(earlier.berths().get(0).windows()).extracting(Window::vesselCode).containsExactly("V003");
    }

    private Scheduled schedule(String vesselId) {
        return visitService.declareEta(vesselId, Instant.now().plus(Duration.ofHours(2)),
                new ReportedPosition(61.50, 4.90, 8.5));
    }
}
