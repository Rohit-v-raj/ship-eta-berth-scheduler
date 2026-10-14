package com.port.scheduler.controller;

import com.port.scheduler.entity.topology.Berth;
import com.port.scheduler.entity.topology.BerthMaintenance;
import com.port.scheduler.entity.topology.OperationalStatus;
import com.port.scheduler.entity.topology.TerminalType;
import com.port.scheduler.entity.vessel.VesselType;
import com.port.scheduler.entity.visit.AllocationStatus;
import com.port.scheduler.entity.visit.EventSource;
import com.port.scheduler.entity.visit.Milestone;
import com.port.scheduler.entity.visit.TimeClassifier;
import com.port.scheduler.entity.visit.VisitPhase;
import com.port.scheduler.exceptions.BerthNotFoundException;
import com.port.scheduler.exceptions.VisitConflictException;
import com.port.scheduler.exceptions.VisitNotFoundException;
import com.port.scheduler.model.view.BerthPlanView;
import com.port.scheduler.model.view.BerthPlanView.Lane;
import com.port.scheduler.model.view.BerthPlanView.Window;
import com.port.scheduler.model.view.MaintenanceView;
import com.port.scheduler.model.view.PortView;
import com.port.scheduler.model.view.PortView.BerthView;
import com.port.scheduler.model.view.PortView.TerminalView;
import com.port.scheduler.model.view.VisitDetailView;
import com.port.scheduler.model.view.VisitDetailView.EventView;
import com.port.scheduler.model.view.VisitView;
import com.port.scheduler.model.view.VisitView.PositionReport;
import com.port.scheduler.model.view.VisitView.VesselView;
import com.port.scheduler.service.BerthService;
import com.port.scheduler.service.PortViewService;
import com.port.scheduler.service.VesselVisitService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The JSON contract of what the console reads, and of the planner's two actions, with the services mocked out. */
@WebMvcTest(ConsoleController.class)
class ConsoleControllerTest {

    private static final Instant REPORTED = Instant.parse("2026-10-13T12:00:00Z");
    private static final Instant ETA = Instant.parse("2026-10-13T14:00:00Z");
    private static final Instant REQUESTED = Instant.parse("2026-10-13T18:30:00Z");

    private static final VisitView QUEUED = new VisitView(
            "VV-20261013-AB12CD34",
            new VesselView("V005", null, null, null, VesselType.CONTAINER, null, null),
            VisitPhase.INBOUND, null, null, 1,
            ETA, REQUESTED, null, null, null, 1008,
            new PositionReport(REPORTED, 61.5, 4.9, 8.5, ETA),
            REPORTED, REPORTED.plus(Duration.ofMinutes(60)), null, null, null, null, null, null);

    @Autowired MockMvc mvc;
    @MockBean PortViewService views;
    @MockBean BerthService berthService;
    @MockBean VesselVisitService visitService;

    @Test
    void portListsItsTerminalsAndBerthsWithCoordinates() throws Exception {
        given(views.port()).willReturn(new PortView("NOFRO", "Floro", "NO", "Europe/Oslo", 61.6, 5.033,
                List.of(new TerminalView("FRO-CT", "Floro Container Terminal", TerminalType.CONTAINER,
                        OperationalStatus.ACTIVE)),
                List.of(new BerthView("B1", "Berth 1", 1, "FRO-CT", "Q1", 61.612, 5.07,
                        new BigDecimal("200.00"), new BigDecimal("12.00"), new BigDecimal("190.00"),
                        new BigDecimal("32.00"), new BigDecimal("10.50"), OperationalStatus.ACTIVE))));

        mvc.perform(get("/api/port"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unLocode").value("NOFRO"))
                .andExpect(jsonPath("$.timeZone").value("Europe/Oslo"))
                .andExpect(jsonPath("$.terminals[0].type").value("CONTAINER"))
                .andExpect(jsonPath("$.berths[0].code").value("B1"))
                .andExpect(jsonPath("$.berths[0].terminalCode").value("FRO-CT"))
                .andExpect(jsonPath("$.berths[0].latitude").value(61.612))
                .andExpect(jsonPath("$.berths[0].maxLoaMeters").value(190.00))
                .andExpect(jsonPath("$.berths[0].status").value("ACTIVE"));
    }

    @Test
    void visitsAreTheOpenOnesUnlessAllAreAskedFor() throws Exception {
        given(views.visits(null, false)).willReturn(List.of(QUEUED));

        mvc.perform(get("/api/visits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].visitRef").value("VV-20261013-AB12CD34"))
                .andExpect(jsonPath("$[0].vessel.code").value("V005"))
                .andExpect(jsonPath("$[0].vessel.type").value("CONTAINER"))
                .andExpect(jsonPath("$[0].phase").value("INBOUND"))
                .andExpect(jsonPath("$[0].berth").isEmpty())
                .andExpect(jsonPath("$[0].queueNumber").value(1))
                .andExpect(jsonPath("$[0].etaBerth").value("2026-10-13T14:00:00Z"))
                .andExpect(jsonPath("$[0].rtaBerth").value("2026-10-13T18:30:00Z"))
                .andExpect(jsonPath("$[0].ptaBerth").isEmpty())
                .andExpect(jsonPath("$[0].lastReport.reportedAt").value("2026-10-13T12:00:00Z"))
                .andExpect(jsonPath("$[0].lastReport.lat").value(61.5))
                .andExpect(jsonPath("$[0].lastReport.distanceNm").value(8.5))
                .andExpect(jsonPath("$[0].lastReport.estimatedArrival").value("2026-10-13T14:00:00Z"))
                // when the request was issued, and when the ship's answer to it is due
                .andExpect(jsonPath("$[0].requestedAt").value("2026-10-13T12:00:00Z"))
                .andExpect(jsonPath("$[0].answerDueAt").value("2026-10-13T13:00:00Z"))
                .andExpect(jsonPath("$[0].cancelledBy").isEmpty())
                // speed is the ship's business: the terminal's view of a visit carries none
                .andExpect(jsonPath("$[0].advisedSpeedKnots").doesNotExist());

        mvc.perform(get("/api/visits").param("vessel", "V005").param("state", "all"))
                .andExpect(status().isOk());
        verify(views).visits("V005", true);
    }

    @Test
    void anUnknownStateIsA400() throws Exception {
        mvc.perform(get("/api/visits").param("state", "closed"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(views);
    }

    @Test
    void aVisitsDetailCarriesItsLedgerAndAnUnknownReferenceIsA404() throws Exception {
        given(views.visit("VV-20261013-AB12CD34")).willReturn(new VisitDetailView(QUEUED,
                List.of(new EventView(Milestone.ARRIVAL_BERTH, TimeClassifier.REQUESTED, REQUESTED, REPORTED,
                        EventSource.TERMINAL, null, "Berth expected free 2026-10-13T18:30:00Z")),
                List.of(QUEUED.lastReport())));
        given(views.visit("VV-NOT-THERE")).willThrow(new VisitNotFoundException("No visit has the reference VV-NOT-THERE"));

        mvc.perform(get("/api/visits/VV-20261013-AB12CD34"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visit.queueNumber").value(1))
                .andExpect(jsonPath("$.events[0].milestone").value("ARRIVAL_BERTH"))
                .andExpect(jsonPath("$.events[0].classifier").value("REQUESTED"))
                .andExpect(jsonPath("$.events[0].source").value("TERMINAL"))
                .andExpect(jsonPath("$.events[0].eventTime").value("2026-10-13T18:30:00Z"))
                .andExpect(jsonPath("$.events[0].recordedAt").value("2026-10-13T12:00:00Z"))
                .andExpect(jsonPath("$.reports[0].lon").value(4.9));

        mvc.perform(get("/api/visits/VV-NOT-THERE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.statusCode").value(404));
    }

    @Test
    void theBerthPlanCoversTheNamedPeriod() throws Exception {
        Instant from = Instant.parse("2026-10-13T00:00:00Z");
        Instant to = Instant.parse("2026-10-14T00:00:00Z");
        given(views.berthPlan(from, to)).willReturn(new BerthPlanView(from, to, List.of(
                new Lane("B1", "Berth 1", "FRO-CT", OperationalStatus.ACTIVE, List.of(
                        new Window("VV-20261013-AB12CD34", "V001", null, VesselType.CONTAINER, AllocationStatus.ACTIVE,
                                ETA, REQUESTED, ETA, null, ETA, ETA, ETA)),
                        List.of(new MaintenanceView(7L, "B1", "Berth 1", REQUESTED, REQUESTED.plus(Duration.ofHours(4)),
                                "Fender repair"))))));

        mvc.perform(get("/api/port/berth-plan").param("from", from.toString()).param("to", to.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-10-13T00:00:00Z"))
                .andExpect(jsonPath("$.berths[0].berthCode").value("B1"))
                .andExpect(jsonPath("$.berths[0].terminalCode").value("FRO-CT"))
                .andExpect(jsonPath("$.berths[0].windows[0].vesselCode").value("V001"))
                .andExpect(jsonPath("$.berths[0].windows[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.berths[0].windows[0].plannedEnd").value("2026-10-13T18:30:00Z"))
                .andExpect(jsonPath("$.berths[0].windows[0].actualEnd").isEmpty())
                .andExpect(jsonPath("$.berths[0].maintenance[0].from").value("2026-10-13T18:30:00Z"))
                .andExpect(jsonPath("$.berths[0].maintenance[0].reason").value("Fender repair"));
    }

    @Test
    void withoutAPeriodTheBerthPlanLooksTwelveHoursBackAndTwoDaysAhead() throws Exception {
        mvc.perform(get("/api/port/berth-plan")).andExpect(status().isOk());

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(views).berthPlan(from.capture(), to.capture());
        assertThat(Duration.between(from.getValue(), to.getValue())).isEqualTo(Duration.ofHours(60));
        assertThat(from.getValue()).isBefore(Instant.now());
    }

    @Test
    void aPeriodThatEndsBeforeItStartsOrAMalformedTimeIsA400() throws Exception {
        mvc.perform(get("/api/port/berth-plan")
                        .param("from", "2026-10-14T00:00:00Z").param("to", "2026-10-13T00:00:00Z"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/port/berth-plan").param("from", "yesterday"))
                .andExpect(status().isBadRequest());
        verify(views, never()).berthPlan(any(), any());
    }

    @Test
    void releaseReportsWhetherABerthWasFreed() throws Exception {
        given(berthService.release("B2")).willReturn(true);
        given(berthService.release("B3")).willReturn(false);

        mvc.perform(put("/api/port/berths/B2/release"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.berthCode").value("B2"))
                .andExpect(jsonPath("$.released").value(true));
        mvc.perform(put("/api/port/berths/B3/release"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(false));
    }

    @Test
    void releaseOfAnUnknownBerthIsA404WithAJsonBodyAndItIsAPut() throws Exception {
        given(berthService.release("B99")).willThrow(new BerthNotFoundException("Berth ID B99 does not exist"));

        mvc.perform(put("/api/port/berths/B99/release"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.statusCode").value(404))
                .andExpect(jsonPath("$.message").value("Berth not found: Berth ID B99 does not exist"));
        mvc.perform(post("/api/port/berths/B2/release"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void cancellingACallPassesTheReasonOnAndTheBodyIsOptional() throws Exception {
        mvc.perform(put("/api/visits/VV-20261013-AB12CD34/cancel").contentType("application/json")
                        .content("{\"reason\":\"no answer to the requested arrival\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visitRef").value("VV-20261013-AB12CD34"))
                .andExpect(jsonPath("$.cancelled").value(true));
        verify(visitService).cancelByTerminal("VV-20261013-AB12CD34", "no answer to the requested arrival");

        mvc.perform(put("/api/visits/VV-20261013-AB12CD34/cancel")).andExpect(status().isOk());
        verify(visitService).cancelByTerminal("VV-20261013-AB12CD34", null);
    }

    @Test
    void cancellingAnUnknownCallIsA404AndOneThatCannotBeCancelledIsA409() throws Exception {
        willThrow(new VisitNotFoundException("No visit has the reference VV-NOT-THERE"))
                .given(visitService).cancelByTerminal("VV-NOT-THERE", null);
        willThrow(new VisitConflictException("The vessel is alongside; release its berth when it leaves"))
                .given(visitService).cancelByTerminal("VV-ALONGSIDE", null);

        mvc.perform(put("/api/visits/VV-NOT-THERE/cancel"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.statusCode").value(404));
        mvc.perform(put("/api/visits/VV-ALONGSIDE/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Conflict: The vessel is alongside; release its berth when it leaves"));
    }

    @Test
    void aReasonLongerThanTheVisitCanHoldIsA400() throws Exception {
        mvc.perform(put("/api/visits/VV-20261013-AB12CD34/cancel").contentType("application/json")
                        .content("{\"reason\":\"" + "x".repeat(401) + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(visitService);
    }

    @Test
    void maintenanceIsListedAndScheduledWithA201() throws Exception {
        Instant from = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);
        Instant to = from.plus(Duration.ofHours(6));
        given(views.maintenance()).willReturn(List.of(new MaintenanceView(3L, "B3", "Berth 3", from, to, "Dredging")));
        BerthMaintenance saved = maintenanceOn("B3", from, to, "Dredging");
        given(berthService.scheduleMaintenance("B3", from, to, "Dredging")).willReturn(saved);

        mvc.perform(get("/api/port/maintenance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].berthCode").value("B3"))
                .andExpect(jsonPath("$[0].reason").value("Dredging"));
        mvc.perform(post("/api/port/berths/B3/maintenance").contentType("application/json")
                        .content("{\"from\":\"" + from + "\",\"to\":\"" + to + "\",\"reason\":\"Dredging\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.berthCode").value("B3"))
                .andExpect(jsonPath("$.from").value(from.toString()))
                .andExpect(jsonPath("$.to").value(to.toString()));
    }

    @Test
    void maintenanceThatEndsBeforeItStartsOrIsOverIsA400AndOneOverABookingIsA409() throws Exception {
        Instant from = Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.SECONDS);
        given(berthService.scheduleMaintenance("B1", from, from.plus(Duration.ofHours(2)), null))
                .willThrow(new VisitConflictException("B1 is booked for V001 from ... to ...; maintenance cannot overlap a booking"));

        mvc.perform(post("/api/port/berths/B1/maintenance").contentType("application/json")
                        .content("{\"from\":\"" + from + "\",\"to\":\"" + from.minus(Duration.ofHours(1)) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/port/berths/B1/maintenance").contentType("application/json")
                        .content("{\"from\":\"2026-01-01T00:00:00Z\",\"to\":\"2026-01-02T00:00:00Z\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/port/berths/B1/maintenance").contentType("application/json")
                        .content("{\"to\":\"" + from + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/port/berths/B1/maintenance").contentType("application/json")
                        .content("{\"from\":\"" + from + "\",\"to\":\"" + from.plus(Duration.ofHours(2)) + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Conflict: B1 is booked")));
    }

    @Test
    void removingAMaintenanceWindowAnswersWhetherItExisted() throws Exception {
        given(berthService.removeMaintenance("B3", 3L)).willReturn(true);
        given(berthService.removeMaintenance("B3", 99L)).willReturn(false);

        mvc.perform(delete("/api/port/berths/B3/maintenance/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value(true));
        mvc.perform(delete("/api/port/berths/B3/maintenance/99"))
                .andExpect(status().isNotFound());
    }

    /** A saved maintenance window, as the service would return it. */
    private static BerthMaintenance maintenanceOn(String berthCode, Instant from, Instant to, String reason) {
        Berth berth = mock(Berth.class);
        given(berth.getCode()).willReturn(berthCode);
        given(berth.getName()).willReturn("Berth " + berthCode.substring(1));
        return new BerthMaintenance(berth, from, to, reason);
    }

    @Test
    void theOldShipEndpointsAreGone() throws Exception {
        // a call is opened by a DCSA estimated arrival, and berths are read through /api/port
        mvc.perform(post("/api/ships/schedule").contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/ships/berths")).andExpect(status().isNotFound());
        mvc.perform(put("/api/ships/berths/B1/release")).andExpect(status().isNotFound());
    }
}
