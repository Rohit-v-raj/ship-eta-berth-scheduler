package com.port.scheduler.dcsa;

import com.port.scheduler.dcsa.PortCallEventPublisher.Filter;
import com.port.scheduler.dcsa.PortCallMessages.Event;
import com.port.scheduler.dcsa.PortCallMessages.FeedbackElement;
import com.port.scheduler.dcsa.PortCallMessages.PortCall;
import com.port.scheduler.dcsa.PortCallMessages.PortCallService;
import com.port.scheduler.dcsa.PortCallMessages.Timestamp;
import com.port.scheduler.entity.vessel.Vessel;
import com.port.scheduler.entity.vessel.VesselType;
import com.port.scheduler.entity.visit.VesselVisit;
import com.port.scheduler.entity.visit.VisitPhase;
import com.port.scheduler.repository.VesselRepository;
import com.port.scheduler.repository.VesselVisitRepository;
import com.port.scheduler.service.BerthService;
import com.port.scheduler.service.BerthService.BerthStatus;
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
            "9321483", null, "King of the Seas", null, "CONT", null, null, null, null, null, null);

    @Autowired PortCallEventReceiver receiver;
    @Autowired PortCallEventPublisher publisher;
    @Autowired VesselRepository vessels;
    @Autowired VesselVisitRepository visits;
    @Autowired BerthService berthService;

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
        assertThat(berthService.status().get(0)).isEqualTo(new BerthStatus("B1", "Berth 1", true, "IMO9321483"));
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
        assertThat(berthService.status().get(0).occupied()).isTrue();

        assertThat(receiver.receive(List.of(berth("DEPA", "ACT", ETA.plus(Duration.ofHours(20)))))).isEmpty();
        assertThat(visit.getPhase()).isEqualTo(VisitPhase.DEPARTED);
        assertThat(berthService.status()).allSatisfy(b -> assertThat(b.occupied()).isFalse());
    }

    @Test
    void eventsTheTerminalDoesNotActOnAreReportedAndAnOmittedCallIsCancelled() {
        Event pilotage = new Event(null, null, null, null, null,
                new PortCallService(null, "PILOTAGE", "STRT", "INBD", "PBPL", null), SHIP,
                new Timestamp("EST", ETA, null));
        Event omitted = new Event(null, null, null, new PortCall(null, null, "NOFRO", true), null, null, SHIP, null);
        receiver.receive(List.of(berth("ARRI", "EST", ETA)));

        List<FeedbackElement> feedback = receiver.receive(List.of(pilotage, berth("ARRI", "REQ", ETA), omitted));

        assertThat(feedback).extracting(FeedbackElement::severity, FeedbackElement::propertyPath)
                .containsExactly(tuple("WARN", "$.events[0]"), tuple("ERROR", "$.events[1]"));
        assertThat(visits.findAll().get(0).getPhase()).isEqualTo(VisitPhase.CANCELLED);
    }

    private static Event berth(String eventType, String classifier, Instant time) {
        return new Event(null, null, null, null, null,
                new PortCallService(null, "BERTH", eventType, null, "BRTH", null), SHIP,
                new Timestamp(classifier, time, null));
    }
}
