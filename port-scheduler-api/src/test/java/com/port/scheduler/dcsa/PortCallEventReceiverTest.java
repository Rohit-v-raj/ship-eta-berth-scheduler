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
import com.port.scheduler.model.view.VisitView;
import com.port.scheduler.model.view.VisitView.PositionReport;
import com.port.scheduler.service.PortViewService;
import com.port.scheduler.support.PersistenceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** DCSA Port Call events from a vessel operator, applied to real MySQL. */
@PersistenceTest
class PortCallEventReceiverTest {

    private static final Instant ETA = Instant.now().plus(Duration.ofHours(6)).truncatedTo(ChronoUnit.SECONDS);
    /** A ship that gives its length and beam, as a call opens only for a vessel of known size. */
    private static final PortCallMessages.Vessel SHIP = new PortCallMessages.Vessel(
            "9321483", null, "King of the Seas", null, "CONT", null, null, "MTR", 120.0, 20.0, null, null, null);

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
    void actualArrivalAndDepartureMoveTheVesselOnAndOffItsBerthAtTheTimesSent() {
        Instant arrived = Instant.now().minus(Duration.ofHours(3)).truncatedTo(ChronoUnit.SECONDS);
        Instant left = arrived.plus(Duration.ofHours(2));
        receiver.receive(List.of(berth("ARRI", "EST", arrived)));
        VesselVisit visit = visits.findAll().get(0);

        assertThat(receiver.receive(List.of(berth("ARRI", "ACT", arrived)))).isEmpty();
        assertThat(visit.getPhase()).isEqualTo(VisitPhase.BERTHED);
        assertThat(visit.getAtaBerth()).isEqualTo(arrived);
        assertThat(holderOf("B1")).isEqualTo("IMO9321483");

        assertThat(receiver.receive(List.of(berth("DEPA", "ACT", left)))).isEmpty();
        assertThat(visit.getPhase()).isEqualTo(VisitPhase.DEPARTED);
        assertThat(visit.getAtdBerth()).isEqualTo(left);
        assertThat(views.visits(null, false)).isEmpty();
    }

    @Test
    void anArrivalAtABerthWhereAnotherVesselStillLiesIsReportedAgainstItsEvent() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        receiver.receive(List.of(berth("ARRI", "EST", now), berth("ARRI", "ACT", now)));   // alongside B1
        receiver.receive(List.of(estimateFor(named(null, null, "FILL-2")), estimateFor(named(null, null, "FILL-3")),
                estimateFor(named(null, null, "FILL-4"))));
        PortCallMessages.Vessel second = named(null, null, "SECOND");
        receiver.receive(List.of(estimateFor(second)));   // booked on B1, after the vessel there

        List<FeedbackElement> feedback = receiver.receive(List.of(new Event(null, null, null, null, null,
                new PortCallService(null, "BERTH", "ARRI", null, "BRTH", null, null), second,
                new Timestamp("ACT", now, null))));

        assertThat(feedback).extracting(FeedbackElement::severity, FeedbackElement::message).containsExactly(
                tuple("ERROR", "B1 still has IMO9321483 alongside; its departure is recorded first"));
        assertThat(views.visits("SECOND", false).get(0).phase()).isEqualTo(VisitPhase.INBOUND);
    }

    @Test
    void anArrivalBeforeTheVesselsTurnIsReportedAgainstItsEventAndChangesNothing() {
        receiver.receive(List.of(berth("ARRI", "EST", ETA), estimateFor(named(null, null, "FILL-2")),
                estimateFor(named(null, null, "FILL-3")), estimateFor(named(null, null, "FILL-4"))));
        PortCallMessages.Vessel second = named(null, null, "SECOND");
        receiver.receive(List.of(estimateFor(second)));   // every berth is booked six hours out: after one of them
        VisitView booked = views.visits("SECOND", false).get(0);
        assertThat(booked.aheadOnBerth()).isNotNull();

        List<FeedbackElement> feedback = receiver.receive(List.of(new Event(null, null, null, null, null,
                new PortCallService(null, "BERTH", "ARRI", null, "BRTH", null, null), second,
                new Timestamp("ACT", Instant.now().truncatedTo(ChronoUnit.SECONDS), null))));

        assertThat(feedback).singleElement().satisfies(element -> {
            assertThat(element.severity()).isEqualTo("ERROR");
            assertThat(element.message())
                    .startsWith(booked.berth().berthCode() + " is booked for " + booked.aheadOnBerth().vesselCode())
                    .endsWith("a vessel comes in out of turn only when its stay ends before the next booking");
        });
        VisitView after = views.visits("SECOND", false).get(0);
        assertThat(after.phase()).isEqualTo(VisitPhase.INBOUND);
        assertThat(after.ataBerth()).isNull();
        assertThat(after.berth()).isEqualTo(booked.berth());
    }

    @Test
    void anEventForAnotherPortIsRefusedAndOneForThisPortOrNoneIsTakenAsMeantHere() {
        Event calledElsewhere = new Event(null, null, null, new PortCall(null, null, "NLRTM", null), null,
                new PortCallService(null, "BERTH", "ARRI", null, "BRTH", null, null), SHIP,
                new Timestamp("EST", ETA, null));
        Event locatedElsewhere = new Event(null, null, null, null, null,
                new PortCallService(null, "BERTH", "ARRI", null, "BRTH", new Location("Berth 1", "NLRTM", null), null),
                SHIP, new Timestamp("EST", ETA, null));

        List<FeedbackElement> refused = receiver.receive(List.of(calledElsewhere, locatedElsewhere));

        assertThat(refused).extracting(FeedbackElement::severity, FeedbackElement::message).containsExactly(
                tuple("ERROR", "This terminal is at NOFRO; the event is for NLRTM"),
                tuple("ERROR", "This terminal is at NOFRO; the event is for NLRTM"));
        assertThat(visits.findAll()).isEmpty();
        assertThat(vessels.findByImoNumber("9321483")).isEmpty(); // a refused event leaves no vessel behind

        Event calledHere = new Event(null, null, null, new PortCall(null, null, "nofro", null), null,
                new PortCallService(null, "BERTH", "ARRI", null, "BRTH", null, null), SHIP,
                new Timestamp("EST", ETA, null));
        assertThat(receiver.receive(List.of(calledHere))).isEmpty();
        assertThat(visits.findAll()).hasSize(1);
    }

    @Test
    void identifiersInTheWrongFormAreRefusedInPlainWordsAndLeaveNoRecord() {
        long vesselsBefore = vessels.count();
        List<FeedbackElement> feedback = receiver.receive(List.of(
                estimateFor(named("12345678901", null, "Long Number")),
                estimateFor(named("ABC1234", null, "Letters")),
                estimateFor(named(null, "12345", "Short MMSI")),
                estimateFor(named(null, null, "A".repeat(40))),
                berth("ARRI", "REQ", ETA)));

        assertThat(feedback).extracting(FeedbackElement::message).containsExactly(
                "vesselIMONumber must be 7 digits",
                "vesselIMONumber must be 7 digits",
                "vesselMMSINumber must be 9 digits",
                "vesselName can be at most 32 characters when no vesselIMONumber or vesselMMSINumber is given",
                "A requested time at berth is issued by the terminal, not sent to it");
        assertThat(vessels.count()).isEqualTo(vesselsBefore);
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
    void aFirstEstimateWithoutTheShipsLengthAndBeamIsRefusedAndLeavesNoRecord() {
        long vesselsBefore = vessels.count();
        PortCallMessages.Vessel unsized = new PortCallMessages.Vessel("9876543", null, "No Size", null, "CONT",
                null, null, null, null, null, null, null, null);
        PortCallMessages.Vessel lengthOnly = new PortCallMessages.Vessel("9876543", null, "No Size", null, "CONT",
                null, null, "MTR", 150.0, null, null, null, null);

        List<FeedbackElement> feedback = receiver.receive(List.of(estimateFor(unsized), estimateFor(lengthOnly)));

        assertThat(feedback).extracting(FeedbackElement::severity, FeedbackElement::message).containsExactly(
                tuple("ERROR", "The terminal holds no length overall or beam for IMO9876543; send lengthOverall"
                        + " and width (in vesselSizeUnit) with its estimate. A berth is given only to a vessel known to fit it"),
                tuple("ERROR", "The terminal holds no beam for IMO9876543; send width (in vesselSizeUnit) with its"
                        + " estimate. A berth is given only to a vessel known to fit it"));
        assertThat(vessels.count()).isEqualTo(vesselsBefore);
        assertThat(visits.findAll()).isEmpty();
    }

    // ---- repeated and out-of-order events (backlog case 9)

    @Test
    void aRepeatedEventIsAppliedOnceAndAnUpdateOfItIsApplied() {
        UUID id = UUID.randomUUID();
        Instant sent = Instant.now().minus(Duration.ofMinutes(10)).truncatedTo(ChronoUnit.SECONDS);
        assertThat(receiver.receive(List.of(estimate(id, sent, ETA)))).isEmpty();

        List<FeedbackElement> again = receiver.receive(List.of(estimate(id, sent, ETA.plus(Duration.ofHours(2)))));

        assertThat(again).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo("INFO");
            assertThat(f.message()).startsWith("Already received at").endsWith("is not applied again");
        });
        assertThat(visits.findAll().get(0).getEtaBerth()).isEqualTo(ETA);

        // the same event, updated later: applied
        assertThat(receiver.receive(List.of(estimate(id, sent.plusSeconds(60), ETA.plus(Duration.ofHours(2)))))).isEmpty();
        assertThat(visits.findAll().get(0).getEtaBerth()).isEqualTo(ETA.plus(Duration.ofHours(2)));
    }

    @Test
    void anEstimateSentBeforeTheLatestOneIsNotAppliedWhenItArrivesLate() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        receiver.receive(List.of(estimate(UUID.randomUUID(), now.minus(Duration.ofMinutes(5)), ETA)));

        List<FeedbackElement> late = receiver.receive(List.of(
                estimate(UUID.randomUUID(), now.minus(Duration.ofMinutes(10)), ETA.plus(Duration.ofHours(3)))));

        assertThat(late).extracting(FeedbackElement::severity, FeedbackElement::message).containsExactly(tuple("WARN",
                "Not applied: this event was sent at " + now.minus(Duration.ofMinutes(10))
                        + ", before the latest event from this vessel, sent at " + now.minus(Duration.ofMinutes(5))
                        + ", which overtook it"));
        assertThat(visits.findAll().get(0).getEtaBerth()).isEqualTo(ETA);
    }

    @Test
    void anOmittedCallSentBeforeANewerEstimateDoesNotCancelTheCall() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        receiver.receive(List.of(estimate(UUID.randomUUID(), now.minus(Duration.ofMinutes(5)), ETA)));

        List<FeedbackElement> late = receiver.receive(List.of(omit(UUID.randomUUID(), now.minus(Duration.ofMinutes(10)))));

        assertThat(late).extracting(FeedbackElement::severity).containsExactly("WARN");
        assertThat(visits.findAll()).singleElement()
                .satisfies(v -> assertThat(v.getPhase()).isEqualTo(VisitPhase.INBOUND));
    }

    @Test
    void anEstimateSentBeforeTheShipLeftOpensNoNewCallWhenItArrivesAfterwards() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant arrived = now.minus(Duration.ofHours(2));
        receiver.receive(List.of(
                estimate(UUID.randomUUID(), now.minus(Duration.ofHours(3)), arrived),
                berthEvent(UUID.randomUUID(), arrived, "ARRI", "ACT", arrived),
                berthEvent(UUID.randomUUID(), now.minus(Duration.ofHours(1)), "DEPA", "ACT", now.minus(Duration.ofHours(1)))));

        List<FeedbackElement> late = receiver.receive(List.of(
                estimate(UUID.randomUUID(), now.minus(Duration.ofMinutes(150)), ETA)));

        assertThat(late).extracting(FeedbackElement::severity).containsExactly("WARN");
        assertThat(visits.findAll()).singleElement()
                .satisfies(v -> assertThat(v.getPhase()).isEqualTo(VisitPhase.DEPARTED));
    }

    @Test
    void aSendersTimeFromTheFutureIsRefused() {
        List<FeedbackElement> feedback = receiver.receive(List.of(
                estimate(UUID.randomUUID(), Instant.now().plus(Duration.ofHours(1)), ETA)));

        assertThat(feedback).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo("ERROR");
            assertThat(f.message()).startsWith("eventUpdatedDateTime cannot be in the future");
        });
        assertThat(visits.findAll()).isEmpty();
    }

    @Test
    void aBatchSentAgainIsAnsweredEventByEventAndChangesNothing() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        List<Event> batch = List.of(
                estimate(UUID.randomUUID(), now.minus(Duration.ofMinutes(2)), ETA),
                omit(UUID.randomUUID(), now.minus(Duration.ofMinutes(1))));
        assertThat(receiver.receive(batch)).isEmpty();
        assertThat(visits.findAll().get(0).getPhase()).isEqualTo(VisitPhase.CANCELLED);

        // before, the omitted call sent again was an ERROR: the vessel had no open visit
        assertThat(receiver.receive(batch)).extracting(FeedbackElement::severity, FeedbackElement::propertyPath)
                .containsExactly(tuple("INFO", "$.events[0]"), tuple("INFO", "$.events[1]"));
        assertThat(visits.findAll()).hasSize(1);
    }

    @Test
    void eventsWithNoIdOrTimeAreAppliedAsTheyCome() {
        receiver.receive(List.of(berth("ARRI", "EST", ETA)));

        // no id to recognise a repeat, no time to order by: applied, as before
        assertThat(receiver.receive(List.of(berth("ARRI", "EST", ETA.plus(Duration.ofHours(1)))))).isEmpty();
        assertThat(visits.findAll().get(0).getEtaBerth()).isEqualTo(ETA.plus(Duration.ofHours(1)));
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

    private static PortCallMessages.Vessel named(String imoNumber, String mmsi, String name) {
        return new PortCallMessages.Vessel(imoNumber, mmsi, name, null, "CONT",
                null, null, "MTR", 120.0, 20.0, null, null, null);
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
                "MTR", 120.0, 20.0, null, null, null);
        return new Event(null, null, null, null, null,
                new PortCallService(null, "BERTH", "ARRI", null, "BRTH", null, null), reporting,
                new Timestamp("EST", ETA, null));
    }

    /** An estimated arrival from SHIP with the sender's id and time. */
    private static Event estimate(UUID id, Instant sent, Instant eta) {
        return berthEvent(id, sent, "ARRI", "EST", eta);
    }

    private static Event berthEvent(UUID id, Instant sent, String eventType, String classifier, Instant time) {
        return new Event(id, null, sent, null, null,
                new PortCallService(null, "BERTH", eventType, null, "BRTH", null, null), SHIP,
                new Timestamp(classifier, time, null));
    }

    private static Event omit(UUID id, Instant sent) {
        return new Event(id, null, sent, new PortCall(null, null, "NOFRO", true), null, null, SHIP, null);
    }

    private static Event berth(String eventType, String classifier, Instant time) {
        return new Event(null, null, null, null, null,
                new PortCallService(null, "BERTH", eventType, null, "BRTH", null, null), SHIP,
                new Timestamp(classifier, time, null));
    }
}
