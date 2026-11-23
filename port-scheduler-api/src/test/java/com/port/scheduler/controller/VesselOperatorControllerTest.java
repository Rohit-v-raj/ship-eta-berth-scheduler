package com.port.scheduler.controller;

import com.port.scheduler.entity.vessel.VesselType;
import com.port.scheduler.entity.visit.VisitPhase;
import com.port.scheduler.exceptions.EtaServiceUnavailableException;
import com.port.scheduler.model.EtaResponse;
import com.port.scheduler.model.view.VisitView;
import com.port.scheduler.model.view.VisitView.VesselView;
import com.port.scheduler.service.EtaService;
import com.port.scheduler.service.PortViewService;
import com.port.scheduler.service.VesselVisitService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The vessel operator's calculator: the JSON it answers with, and that it changes nothing. */
@WebMvcTest(VesselOperatorController.class)
class VesselOperatorControllerTest {

    // 30 nm out at 12 knots
    private static final String POSITION =
            "{\"vesselId\":\"V001\",\"lat\":61.5,\"lon\":4.9,\"sog\":12.0,\"cog\":45.0,\"destLat\":61.612,\"destLon\":5.07}";
    // the model says two and a half hours
    private static final EtaResponse MODEL = new EtaResponse(150.0, 2.5, 30.0, 150.0);

    @Autowired MockMvc mvc;
    @MockBean EtaService etaService;
    @MockBean PortViewService views;
    @MockBean VesselVisitService vesselVisitService;

    @Test
    void aShipWithNoCallGetsTheModelsEstimateAndNothingElse() throws Exception {
        given(etaService.predict(any())).willReturn(MODEL);
        given(views.visits("V001", false)).willReturn(List.of());

        mvc.perform(post("/api/operator/eta").contentType(MediaType.APPLICATION_JSON).content(POSITION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vesselId").value("V001"))
                .andExpect(jsonPath("$.etaMinutes").value(150.0))
                .andExpect(jsonPath("$.distanceNm").value(30.0))
                .andExpect(jsonPath("$.naiveEtaMinutes").value(150.0))
                .andExpect(jsonPath("$.estimatedArrival").exists())
                .andExpect(jsonPath("$.requestedArrival").isEmpty())
                .andExpect(jsonPath("$.advisedSpeedKnots").isEmpty());
        verifyNoInteractions(vesselVisitService);
    }

    @Test
    void aModelServiceThatIsDownOrHungIsA503ThatSaysSo() throws Exception {
        given(etaService.predict(any())).willThrow(new EtaServiceUnavailableException(
                "The ETA model service is not answering; try again shortly", new RuntimeException("timed out")));

        mvc.perform(post("/api/operator/eta").contentType(MediaType.APPLICATION_JSON).content(POSITION))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("The ETA model service is not answering; try again shortly"));
        verifyNoInteractions(vesselVisitService);
    }

    @Test
    void aShipAskedToArriveLaterThanItWouldIsGivenTheSpeedThatMeetsThatTime() throws Exception {
        given(etaService.predict(any())).willReturn(MODEL);
        // the terminal wants it in six hours: 30 nm in 6 h is 5 knots, the slowest worth advising for this type
        given(views.visits("V001", false)).willReturn(List.of(openCall(Instant.now().plus(Duration.ofHours(6)), null)));

        mvc.perform(post("/api/operator/eta").contentType(MediaType.APPLICATION_JSON).content(POSITION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedArrival").exists())
                .andExpect(jsonPath("$.advisedSpeedKnots").value(5.0));
        verifyNoInteractions(vesselVisitService);
    }

    @Test
    void thereIsNoAdviceWhenTheBerthIsReadyFirstOrTheShipIsAlreadyAlongside() throws Exception {
        given(etaService.predict(any())).willReturn(MODEL);

        // asked to arrive in one hour, but it needs two and a half: nothing to slow down for
        given(views.visits("V001", false)).willReturn(List.of(openCall(Instant.now().plus(Duration.ofHours(1)), null)));
        mvc.perform(post("/api/operator/eta").contentType(MediaType.APPLICATION_JSON).content(POSITION))
                .andExpect(jsonPath("$.requestedArrival").exists())
                .andExpect(jsonPath("$.advisedSpeedKnots").isEmpty());

        // already alongside: the request it was given is history
        given(views.visits("V001", false))
                .willReturn(List.of(openCall(Instant.now().plus(Duration.ofHours(6)), Instant.now())));
        mvc.perform(post("/api/operator/eta").contentType(MediaType.APPLICATION_JSON).content(POSITION))
                .andExpect(jsonPath("$.requestedArrival").isEmpty())
                .andExpect(jsonPath("$.advisedSpeedKnots").isEmpty());
    }

    @Test
    void aBadPositionIsRefusedBeforeTheModelIsAsked() throws Exception {
        mvc.perform(post("/api/operator/eta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vesselId\":\"\",\"lat\":61.5,\"lon\":4.9,\"sog\":-1,\"cog\":45.0}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(etaService, views);
    }

    @Test
    void aPositionThatIsNoRealPlaceIsRefusedSayingWhichPart() throws Exception {
        mvc.perform(post("/api/operator/eta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vesselId\":\"V001\",\"lat\":500,\"lon\":4.9,\"sog\":12,\"cog\":45.0,"
                                + "\"destLat\":61.612,\"destLon\":5.07}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("lat: must be less than or equal to 90"));
        mvc.perform(post("/api/operator/eta").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vesselId\":\"V001\",\"lat\":61.5,\"lon\":4.9,\"sog\":12,\"cog\":45.0,"
                                + "\"destLat\":61.612,\"destLon\":-200}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("destLon: must be greater than or equal to -180"));
        verifyNoInteractions(etaService, views);
    }

    @Test
    void theOperatorsAnswersAreNotRoutesHere() throws Exception {
        // accept, another time and omit are sent as DCSA events to /api/port-call/v2/events
        for (String decision : new String[] {"accept", "reject", "omit"}) {
            mvc.perform(post("/api/operator/vessels/V001/" + decision).contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNotFound());
        }
    }

    private static VisitView openCall(Instant requestedArrival, Instant actualArrival) {
        return new VisitView("VV-20261013-AB12CD34",
                new VesselView("V001", null, null, null, VesselType.GENERAL_CARGO, null, null),
                actualArrival == null ? VisitPhase.INBOUND : VisitPhase.BERTHED, null, null, 1,
                null, requestedArrival, null, actualArrival, null, 1397, null, null, null, null, null, null, null, null, null, null, null);
    }
}
