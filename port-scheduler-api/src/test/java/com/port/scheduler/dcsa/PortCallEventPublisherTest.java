package com.port.scheduler.dcsa;

import com.port.scheduler.dcsa.PortCallEventPublisher.Filter;
import com.port.scheduler.dcsa.PortCallEventPublisher.Page;
import com.port.scheduler.entity.vessel.VesselType;
import com.port.scheduler.model.EtaResponse;
import com.port.scheduler.model.ScheduleRequest;
import com.port.scheduler.service.VesselVisitService;
import com.port.scheduler.support.PersistenceTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The DCSA Port Call feed, read from real MySQL after real scheduling. */
@PersistenceTest
class PortCallEventPublisherTest {

    @Autowired PortCallEventPublisher publisher;
    @Autowired VesselVisitService visitService;

    @Test
    void theTerminalsRequestIsPublishedWithTheStandardsCodesAndTheModelsEstimateIsNot() {
        schedule("V001");

        Page page = publisher.find(filter(null, null, null, null, null, null));

        assertThat(page.nextCursor()).isNull();
        assertThat(page.events()).singleElement().satisfies(e -> {
            assertThat(e.eventID()).isNotNull();
            assertThat(e.eventUpdatedDateTime()).isNotNull();
            assertThat(e.timestamp().classifierCode()).isEqualTo("REQ");
            assertThat(e.timestamp().serviceDateTime()).isNotNull();
            assertThat(e.portCallService().portCallServiceTypeCode()).isEqualTo("BERTH");
            assertThat(e.portCallService().portCallServiceEventTypeCode()).isEqualTo("ARRI");
            assertThat(e.portCallService().portCallPhaseTypeCode()).isEqualTo("INBD");
            assertThat(e.portCallService().facilityTypeCode()).isEqualTo("BRTH");
            assertThat(e.portCallService().serviceLocation().locationName()).isEqualTo("Berth 1");
            assertThat(e.portCall().unLocationCode()).isEqualTo("NOFRO");
            assertThat(e.portCall().portVisitReference()).startsWith("VV-");
            assertThat(e.portCall().isOmitted()).isNull();
            assertThat(e.vessel().vesselName()).isEqualTo("V001");
            assertThat(e.vessel().vesselTypeCode()).isEqualTo("CONT");
        });
        assertThat(publisher.find(filter(null, null, null, null, null, null)).events().get(0).eventID())
                .isEqualTo(page.events().get(0).eventID());
    }

    @Test
    void filtersNarrowTheFeed() {
        schedule("V001");
        schedule("V002");
        visitService.confirm("V001", null);   // the vessel's confirmation is a PLN timestamp

        assertThat(publisher.find(filter(null, null, null, null, null, null)).events()).hasSize(3);
        assertThat(publisher.find(filter(null, null, null, "PLN", null, null)).events())
                .singleElement().satisfies(e -> assertThat(e.vessel().vesselName()).isEqualTo("V001"));
        assertThat(publisher.find(filter(null, "V002", null, null, null, null)).events()).hasSize(1);
        assertThat(publisher.find(filter("NOFRO", null, "BERTH", null, null, null)).events()).hasSize(3);
        assertThat(publisher.find(filter("NLRTM", null, null, null, null, null)).events()).isEmpty();
        assertThat(publisher.find(filter(null, null, "PILOTAGE", null, null, null)).events()).isEmpty();
        assertThatThrownBy(() -> publisher.find(filter(null, null, null, "XYZ", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theCursorWalksThroughThePages() {
        schedule("V001");
        schedule("V002");
        schedule("V003");

        Page first = publisher.find(filter(null, null, null, null, 2, null));
        Page second = publisher.find(filter(null, null, null, null, 2, first.nextCursor()));

        assertThat(first.events()).hasSize(2);
        assertThat(first.nextCursor()).isNotNull();
        assertThat(second.events()).hasSize(1);
        assertThat(second.nextCursor()).isNull();
        assertThat(second.events().get(0).eventID()).isNotIn(first.events().get(0).eventID(), first.events().get(1).eventID());
    }

    private static Filter filter(String locode, String vesselName, String serviceType, String classifier,
                                 Integer limit, String cursor) {
        return new Filter(locode, null, null, vesselName, serviceType, classifier, null, null, limit, cursor);
    }

    private void schedule(String vesselId) {
        visitService.schedule(
                new ScheduleRequest(vesselId, 61.50, 4.90, 12.0, 45.0, 61.612, 5.070, VesselType.CONTAINER),
                new EtaResponse(120.0, 2.0, 8.5, 42.5));
    }
}
