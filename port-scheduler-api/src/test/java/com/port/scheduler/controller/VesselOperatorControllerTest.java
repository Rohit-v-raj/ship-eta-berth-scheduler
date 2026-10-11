package com.port.scheduler.controller;

import com.port.scheduler.exceptions.VisitConflictException;
import com.port.scheduler.exceptions.VisitNotFoundException;
import com.port.scheduler.model.EtaResponse;
import com.port.scheduler.service.BerthService.Assignment;
import com.port.scheduler.service.EtaService;
import com.port.scheduler.service.VesselVisitService;
import com.port.scheduler.service.VesselVisitService.Scheduled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The JSON contract of the vessel operator's endpoints. */
@WebMvcTest(VesselOperatorController.class)
class VesselOperatorControllerTest {

    private static final String POSITION =
            "{\"vesselId\":\"V001\",\"lat\":61.5,\"lon\":4.9,\"sog\":12.0,\"cog\":45.0,\"destLat\":61.612,\"destLon\":5.07}";

    @Autowired MockMvc mvc;
    @MockBean EtaService etaService;
    @MockBean VesselVisitService vesselVisitService;

    @Test
    void etaAsksTheModelAndStoresNothing() throws Exception {
        given(etaService.predict(any())).willReturn(new EtaResponse(98.5, 1.64, 15.2, 76.0));

        mvc.perform(post("/api/operator/eta").contentType(MediaType.APPLICATION_JSON).content(POSITION))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vesselId").value("V001"))
                .andExpect(jsonPath("$.etaMinutes").value(98.5))
                .andExpect(jsonPath("$.distanceNm").value(15.2))
                .andExpect(jsonPath("$.estimatedArrival").exists());
        verifyNoInteractions(vesselVisitService);
    }

    @Test
    void acceptReturnsThePlannedArrival() throws Exception {
        given(vesselVisitService.confirm(eq("V001"), any())).willReturn(Instant.parse("2026-10-12T14:00:00Z"));

        mvc.perform(post("/api/operator/vessels/V001/accept").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestedArrival\":\"2026-10-12T14:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.plannedArrival").value("2026-10-12T14:00:00Z"));
    }

    @Test
    void acceptingASupersededTimeIsA409WithAJsonBody() throws Exception {
        given(vesselVisitService.confirm(any(), any()))
                .willThrow(new VisitConflictException("The requested arrival is now 2026-10-12T15:00:00Z"));

        mvc.perform(post("/api/operator/vessels/V001/accept"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.statusCode").value(409));
    }

    @Test
    void rejectIsAnsweredWithANewRequestedArrival() throws Exception {
        given(vesselVisitService.replan(eq("V001"), eq(Instant.parse("2026-10-12T18:00:00Z"))))
                .willReturn(new Scheduled(new Assignment("QUEUED", null, null, 1),
                        Instant.parse("2026-10-12T19:30:00Z"), null));

        mvc.perform(post("/api/operator/vessels/V001/reject").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"estimatedArrival\":\"2026-10-12T18:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.queueNumber").value(1))
                .andExpect(jsonPath("$.requestedArrival").value("2026-10-12T19:30:00Z"));
    }

    @Test
    void rejectWithoutATimeTheVesselCanMakeIsA400() throws Exception {
        mvc.perform(post("/api/operator/vessels/V001/reject").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(vesselVisitService);
    }

    @Test
    void omitReportsTheCallAsOmittedAndAnUnknownVesselIsA404() throws Exception {
        mvc.perform(post("/api/operator/vessels/V001/omit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OMITTED"));

        willThrow(new VisitNotFoundException("Vessel V9 has no open visit")).given(vesselVisitService).omit("V9");
        mvc.perform(post("/api/operator/vessels/V9/omit"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.statusCode").value(404));
    }
}
