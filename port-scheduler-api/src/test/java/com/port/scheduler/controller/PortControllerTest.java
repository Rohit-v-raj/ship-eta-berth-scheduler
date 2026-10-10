package com.port.scheduler.controller;

import com.port.scheduler.exceptions.BerthNotFoundException;
import com.port.scheduler.model.EtaResponse;
import com.port.scheduler.service.BerthService;
import com.port.scheduler.service.BerthService.Assignment;
import com.port.scheduler.service.BerthService.BerthStatus;
import com.port.scheduler.service.EtaService;
import com.port.scheduler.service.VesselVisitService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The JSON contract of /api/ships, with the services mocked out. */
@WebMvcTest(PortController.class)
class PortControllerTest {

    private static final String V001 = """
            {"vesselId":"V001","lat":61.5,"lon":4.9,"sog":12.0,"cog":45.0,"destLat":61.612,"destLon":5.07}
            """;

    @Autowired MockMvc mvc;
    @MockBean EtaService etaService;
    @MockBean VesselVisitService vesselVisitService;
    @MockBean BerthService berthService;

    @Test
    void scheduleReturnsTheEtaWithABerth() throws Exception {
        given(etaService.predict(any())).willReturn(new EtaResponse(414.0, 6.9, 21.44, 102.9));
        given(vesselVisitService.schedule(any(), any())).willReturn(new Assignment("BERTHED", "B1", "Berth 1", null));

        mvc.perform(post("/api/ships/schedule").contentType(MediaType.APPLICATION_JSON).content(V001))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vesselId").value("V001"))
                .andExpect(jsonPath("$.etaMinutes").value(414.0))
                .andExpect(jsonPath("$.etaHours").value(6.9))
                .andExpect(jsonPath("$.status").value("BERTHED"))
                .andExpect(jsonPath("$.berthId").value("B1"))
                .andExpect(jsonPath("$.berthName").value("Berth 1"))
                .andExpect(jsonPath("$.queueNumber").isEmpty());
    }

    @Test
    void scheduleReturnsAQueueNumberWhenThePortIsFull() throws Exception {
        given(etaService.predict(any())).willReturn(new EtaResponse(414.0, 6.9, 21.44, 102.9));
        given(vesselVisitService.schedule(any(), any())).willReturn(new Assignment("QUEUED", null, null, 1));

        mvc.perform(post("/api/ships/schedule").contentType(MediaType.APPLICATION_JSON).content(V001))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.berthId").isEmpty())
                .andExpect(jsonPath("$.queueNumber").value(1));
    }

    @Test
    void scheduleRejectsARequestWithoutAVesselIdBeforeCallingTheModel() throws Exception {
        mvc.perform(post("/api/ships/schedule").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vesselId\":\"\",\"lat\":61.5,\"lon\":4.9,\"sog\":12.0,\"cog\":45.0}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(etaService, vesselVisitService);
    }

    @Test
    void berthsListsOccupancy() throws Exception {
        given(berthService.status()).willReturn(List.of(
                new BerthStatus("B1", "Berth 1", true, "V001"),
                new BerthStatus("B2", "Berth 2", false, null)));

        mvc.perform(get("/api/ships/berths"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("B1"))
                .andExpect(jsonPath("$[0].occupied").value(true))
                .andExpect(jsonPath("$[0].vesselId").value("V001"))
                .andExpect(jsonPath("$[1].occupied").value(false))
                .andExpect(jsonPath("$[1].vesselId").isEmpty());
    }

    @Test
    void releaseReportsWhetherABerthWasFreed() throws Exception {
        given(berthService.release("B2")).willReturn(true);

        mvc.perform(put("/api/ships/berths/B2/release"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.berthId").value("B2"))
                .andExpect(jsonPath("$.released").value(true));
    }

    @Test
    void releaseOfAnUnknownBerthIsA404WithAJsonBody() throws Exception {
        given(berthService.release("B99")).willThrow(new BerthNotFoundException("Berth ID B99 does not exist"));

        mvc.perform(put("/api/ships/berths/B99/release"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.statusCode").value(404))
                .andExpect(jsonPath("$.message").value("Berth not found: Berth ID B99 does not exist"));
    }

    @Test
    void releaseIsNoLongerAPost() throws Exception {
        mvc.perform(post("/api/ships/berths/B2/release"))
                .andExpect(status().isMethodNotAllowed());
    }
}
