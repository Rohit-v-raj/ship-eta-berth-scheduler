package com.port.scheduler.dcsa;

import com.port.scheduler.dcsa.PortCallEventPublisher.Filter;
import com.port.scheduler.dcsa.PortCallMessages.Event;
import com.port.scheduler.dcsa.PortCallMessages.FeedbackElement;
import com.port.scheduler.dcsa.PortCallMessages.GeoCoordinate;
import com.port.scheduler.dcsa.PortCallMessages.Location;
import com.port.scheduler.dcsa.PortCallMessages.PortCall;
import com.port.scheduler.dcsa.PortCallMessages.PortCallService;
import com.port.scheduler.dcsa.PortCallMessages.Timestamp;
import com.port.scheduler.entity.vessel.Vessel;
import com.port.scheduler.entity.vessel.VesselType;
import com.port.scheduler.entity.visit.VesselVisit;
import com.port.scheduler.entity.visit.VisitPhase;
import com.port.scheduler.repository.VesselRepository;
import com.port.scheduler.repository.VesselVisitRepository;
import com.port.scheduler.model.view.VisitView.PositionReport;
import com.port.scheduler.service.PortViewService;
import com.port.scheduler.support.PersistenceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** DCSA Port Call events from a vessel operator, applied to real MySQL. */
@PersistenceTest
class PortCallEventReceiverTest {

    private static final Instant ETA = Instant.now().plus(Duration.ofHours(6)).truncatedTo(ChronoUnit.SECONDS);
    private static final PortCallMessages.Vessel SHIP = new PortCallMessages.Vessel(
            "9321483", null, "King of the Seas", null, "CONT", null, null, null, null, null, null, null, null);

    @Autowired PortCallEventReceiver receiver;
    @Autowired PortCallEventPublisher publisher;
    @Autowired VesselRepository vessels;
    @Autowired VesselVisitRepository visits;
    @Autowired PortViewService views;

    @Test
    void aCarriersEstimateOpensTheVisitAndIsAnsweredWithARequestedArrival() {
        List<FeedbackElement> feedback = receiver.receive(List.of(berth("ARRI", "EST", ETA)));

        assertThat(feedback).isEmpty();
        Vessel vessel = vessels.findByImoNumber("9321483").orElseThrow();
        assertThat(vessel.getCode()).isEqualTo("IMO9321483");
        assertThat(vessel.getType()).isEqualTo(VesselType.CONTAINER);
        VesselVisit visit = visits.findAll().get(0);
        assertThat(visit.getEtaBerth()).isEqualTo(ETA);
        assertThat(visit.getRtaBerth()).isEqualTo(ETA);
        assertThat(holderOf("B1")).isEqualTo("IMO9321483");
        // both sides of the exchange are in the published feed: the carrier's EST, then the terminal's REQ
        assertThat(publisher.find(new Filter(null, null, null, null, null, null, null, null, null, null)).events())
                .extracting(e -> e.timestamp().classifierCode()).containsExactly("EST", "REQ");
    }

    @Test
    void aPlannedArrivalConfirmsTheRequestAndAStaleOneIsReportedAgainstItsEvent() {
        receiver.receive(List.of(berth("ARRI", "EST", ETA)));

        List<FeedbackElement> feedback = receiver.receive(List.of(
                berth("ARRI", "PLN", ETA.plus(Duration.ofHours(2))), berth("ARRI", "PLN", ETA)));

        assertThat(feedback).extracting(FeedbackElement::severity, FeedbackElement::propertyPath)
                .containsExactly(tuple("ERROR", "$.events[0]"));
        assertThat(visits.findAll().get(0).getPtaBerth()).isEqualTo(ETA);
    }

    @Test
    void actualArrivalAndDepartureMoveTheVesselOnAndOffItsBerth() {
        receiver.receive(List.of(berth("ARRI", "EST", ETA)));
        VesselVisit visit = visits.findAll().get(0);

        assertThat(receiver.receive(List.of(berth("ARRI", "ACT", ETA)))).isEmpty();
        assertThat(visit.getPhase()).isEqualTo(VisitPhase.BERTHED);
        assertThat(visit.getAtaBerth()).isEqualTo(ETA);
        assertThat(holderOf("B1")).isEqualTo("IMO9321483");

        assertThat(receiver.receive(List.of(berth("DEPA", "ACT", ETA.plus(Duration.ofHours(20)))))).isEmpty();
        assertThat(visit.getPhase()).isEqualTo(VisitPhase.DEPARTED);
        assertThat(views.visits(null, false)).isEmpty();
    }

    @Test
    void eventsTheTerminalDoesNotActOnAreReportedAndAnOmittedCallIsCancelled() {
        Event pilotage = new Event(null, null, null, null, null,
                new PortCallService(null, "PILOTAGE", "STRT", "INBD", "PBPL", null, null), SHIP,
                new Timestamp("EST", ETA, null));
        Event omitted = new Event(null, null, null, new PortCall(null, null, "NOFRO", true), null, null, SHIP, null);
        receiver.receive(List.of(berth("ARRI", "EST", ETA)));

        List<FeedbackElement> feedback = receiver.receive(List.of(pilotage, berth("ARRI", "REQ", ETA), omitted));

        assertThat(feedback).extracting(FeedbackElement::severity, FeedbackElement::propertyPath)
                .containsExactly(tuple("WARN", "$.events[0]"), tuple("ERROR", "$.events[1]"));
        assertThat(visits.findAll().get(0).getPhase()).isEqualTo(VisitPhase.CANCELLED);
    }

    @Test
    void anAnswerForAVesselWithNoOpenCallIsReportedAgainstItsEvent() {
        Event omitted = new Event(null, null, null, new PortCall(null, null, "NOFRO", true), null, null, SHIP, null);

        List<FeedbackElement> feedback = receiver.receive(List.of(berth("ARRI", "PLN", ETA), omitted));

        assertThat(feedback).extracting(FeedbackElement::severity, FeedbackElement::propertyPath)
                .containsExactly(tuple("ERROR", "$.events[0]"), tuple("ERROR", "$.events[1]"));
        assertThat(visits.findAll()).isEmpty();
    }

    @Test
    void aNewEstimateIsAnsweredWithANewRequestAndCancelsTheEarlierAcceptance() {
        receiver.receive(List.of(berth("ARRI", "EST", ETA), berth("ARRI", "PLN", ETA)));
        Instant later = ETA.plus(Duration.ofHours(3));

        assertThat(receiver.receive(List.of(berth("ARRI", "EST", later)))).isEmpty();

        VesselVisit visit = visits.findAll().get(0);
        assertThat(visit.getEtaBerth()).isEqualTo(later);
        assertThat(visit.getRtaBerth()).isEqualTo(later);
        assertThat(visit.getPtaBerth()).isNull();
        // the vessel keeps its berth; only the time has moved
        assertThat(holderOf("B1")).isEqualTo("IMO9321483");
    }

    @Test
    void anEstimateWithoutATimeIsReportedAgainstItsEvent() {
        Event noTime = new Event(null, null, null, null, null,
                new PortCallService(null, "BERTH", "ARRI", null, "BRTH", null, null), SHIP, new Timestamp("EST", null, null));

        assertThat(receiver.receive(List.of(noTime))).extracting(FeedbackElement::severity).containsExactly("ERROR");
        assertThat(visits.findAll()).isEmpty();
    }

    @Test
    void aPositionGivenWithAnEstimateIsKeptAndAnUnreadableOneIsReportedAgainstItsEvent() {
        assertThat(receiver.receive(List.of(estimateFrom("62.6", "5.4", 60.0)))).isEmpty();

        PositionReport report = views.visits(null, false).get(0).lastReport();
        assertThat(report.lat()).isEqualTo(62.6);
        assertThat(report.lon()).isEqualTo(5.4);
        assertThat(report.distanceNm()).isEqualTo(60.0);
        assertThat(report.estimatedArrival()).isEqualTo(ETA);

        List<FeedbackElement> feedback = receiver.receive(List.of(estimateFrom("north", "5.4", 60.0)));
        assertThat(feedback).extracting(FeedbackElement::severity, FeedbackElement::propertyPath)
                .containsExactly(tuple("ERROR", "$.events[0]"));
    }

    @Test
    void anEstimateThatSaysNothingOfWhereTheVesselIsLeavesNoPosition() {
        receiver.receive(List.of(berth("ARRI", "EST", ETA)));

        assertThat(views.visits(null, false).get(0).lastReport()).isNull();
    }

    @Test
    void aSizeGivenWithAnEstimateIsKeptInMetresAndAVesselThatFitsNoBerthIsRefused() {
        PortCallMessages.Vessel inFeet = new PortCallMessages.Vessel("9321483", null, "King of the Seas", null, "CONT",
                null, null, "FOT", 400.0, 60.0, "MTR", null, 8.5);
        assertThat(receiver.receive(List.of(estimateFor(inFeet)))).isEmpty();

        VesselVisit visit = visits.findAll().get(0);
        assertThat(visit.getVessel().getLoaMeters()).isEqualByComparingTo("121.92");
        assertThat(visit.getVessel().getBeamMeters()).isEqualByComparingTo("18.29");
        assertThat(visit.getArrivalDraftMeters()).isEqualByComparingTo("8.50");

        PortCallMessages.Vessel tooBig = new PortCallMessages.Vessel("9876543", null, "Too Big", null, "CONT",
                null, null, "MTR", 300.0, 48.0, "MTR", null, 14.0);
        PortCallMessages.Vessel oddUnit = new PortCallMessages.Vessel("9876543", null, "Too Big", null, "CONT",
                null, null, "YRD", 100.0, null, null, null, null);
        List<FeedbackElement> feedback = receiver.receive(List.of(estimateFor(tooBig), estimateFor(oddUnit)));

        assertThat(feedback).extracting(FeedbackElement::severity).containsExactly("ERROR", "ERROR");
        assertThat(feedback.get(0).message()).startsWith("No berth here takes").contains("length 300 m");
        assertThat(feedback.get(1).message()).isEqualTo("vesselSizeUnit must be MTR or FOT");
        assertThat(visits.findAll()).hasSize(1);
    }

    private static Event estimateFor(PortCallMessages.Vessel vessel) {
        return new Event(null, null, null, null, null,
                new PortCallService(null, "BERTH", "ARRI", null, "BRTH", null, null), vessel,
                new Timestamp("EST", ETA, null));
    }

    /** The vessel holding the berth, read the way the console reads it; null if the berth is free. */
    private String holderOf(String berthCode) {
        return views.visits(null, false).stream()
                .filter(v -> v.berth() != null && v.berth().berthCode().equals(berthCode))
                .map(v -> v.vessel().code())
                .findFirst().orElse(null);
    }

    private static Event estimateFrom(String latitude, String longitude, Double milesToGo) {
        PortCallMessages.Vessel reporting = new PortCallMessages.Vessel(
                "9321483", null, "King of the Seas", null, "CONT",
                new Location(null, null, new GeoCoordinate(latitude, longitude)), milesToGo,
                null, null, null, null, null, null);
        return new Event(null, null, null, null, null,
                new PortCallService(null, "BERTH", "ARRI", null, "BRTH", null, null), reporting,
                new Timestamp("EST", ETA, null));
    }

    private static Event berth(String eventType, String classifier, Instant time) {
        return new Event(null, null, null, null, null,
                new PortCallService(null, "BERTH", eventType, null, "BRTH", null, null), SHIP,
                new Timestamp(classifier, time, null));
    }
}
