package com.port.scheduler.dcsa;

import com.port.scheduler.dcsa.PortCallEventPublisher.Page;
import com.port.scheduler.dcsa.PortCallMessages.Event;
import com.port.scheduler.dcsa.PortCallMessages.FeedbackElement;
import com.port.scheduler.dcsa.PortCallMessages.PortCall;
import com.port.scheduler.dcsa.PortCallMessages.PortCallService;
import com.port.scheduler.dcsa.PortCallMessages.Timestamp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The JSON and headers of GET /events, against the DCSA Port Call 2.0 specification. */
@WebMvcTest(PortCallEventsController.class)
class PortCallEventsControllerTest {

    @Autowired MockMvc mvc;
    @MockBean PortCallEventPublisher publisher;
    @MockBean PortCallEventReceiver receiver;

    @Test
    void eventsAreReturnedInTheStandardsShapeWithItsHeaders() throws Exception {
        Event event = new Event(UUID.fromString("3910eb91-8791-4699-8029-8bba8cedb6f5"), null,
                Instant.parse("2026-10-12T10:00:00Z"),
                new PortCall(null, "VV-1", "NOFRO", null), null,
                new PortCallService(null, "BERTH", "ARRI", "INBD", "BRTH", null), null,
                new Timestamp("REQ", Instant.parse("2026-10-12T14:00:00Z"), null));
        given(publisher.find(any())).willReturn(new Page(List.of(event), "42"));

        mvc.perform(get("/api/port-call/v2/events").param("classifierCode", "REQ"))
                .andExpect(status().isOk())
                .andExpect(header().string("API-Version", "2.0.0"))
                .andExpect(header().string("Next-Page-Cursor", "42"))
                .andExpect(jsonPath("$.events[0].eventID").value("3910eb91-8791-4699-8029-8bba8cedb6f5"))
                .andExpect(jsonPath("$.events[0].eventUpdatedDateTime").value("2026-10-12T10:00:00Z"))
                .andExpect(jsonPath("$.events[0].portCall.UNLocationCode").value("NOFRO"))
                .andExpect(jsonPath("$.events[0].portCall.portVisitReference").value("VV-1"))
                .andExpect(jsonPath("$.events[0].portCallService.portCallServiceTypeCode").value("BERTH"))
                .andExpect(jsonPath("$.events[0].portCallService.portCallServiceEventTypeCode").value("ARRI"))
                .andExpect(jsonPath("$.events[0].timestamp.classifierCode").value("REQ"))
                .andExpect(jsonPath("$.events[0].timestamp.serviceDateTime").value("2026-10-12T14:00:00Z"))
                .andExpect(jsonPath("$.events[0].vessel").doesNotExist())
                .andExpect(jsonPath("$.events[0].portCall.unLocationCode").doesNotExist())
                .andExpect(jsonPath("$.feedbackElements").doesNotExist());
    }

    @Test
    void noMatchIsA200WithAnEmptyListNotA404() throws Exception {
        given(publisher.find(any())).willReturn(new Page(List.of(), null));

        mvc.perform(get("/api/port-call/v2/events"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Next-Page-Cursor"))
                .andExpect(jsonPath("$.events").isEmpty());
    }

    @Test
    void anUnsupportedFilterIsRefusedInTheStandardsErrorFormat() throws Exception {
        mvc.perform(get("/api/port-call/v2/events").param("carrierServiceCode", "FE1"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("API-Version", "2.0.0"))
                .andExpect(jsonPath("$.feedbackElements[0].severity").value("ERROR"))
                .andExpect(jsonPath("$.feedbackElements[0].message").value(containsString("carrierServiceCode")));
    }

    @Test
    void aMalformedDateIsRefusedInTheStandardsErrorFormat() throws Exception {
        mvc.perform(get("/api/port-call/v2/events").param("eventTimestampMin", "yesterday"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.feedbackElements[0].message").value(containsString("eventTimestampMin")));
    }

    @Test
    void eventsSentToTheTerminalAreAnsweredWithFeedbackPerEvent() throws Exception {
        given(receiver.receive(any())).willReturn(
                List.of(new FeedbackElement("WARN", "Not processed: this terminal acts on BERTH events only", "$.events[0]")));

        mvc.perform(post("/api/port-call/v2/events").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"events\":[{\"vessel\":{\"vesselIMONumber\":\"9321483\"},\"isFYI\":false,"
                                + "\"portCallService\":{\"portCallServiceTypeCode\":\"PILOTAGE\"},"
                                + "\"timestamp\":{\"classifierCode\":\"EST\",\"serviceDateTime\":\"2026-10-12T14:00:00Z\","
                                + "\"delayReasonCodes\":[\"STR\"]}}]}"))
                .andExpect(status().isOk())
                .andExpect(header().string("API-Version", "2.0.0"))
                .andExpect(jsonPath("$.feedbackElements[0].severity").value("WARN"))
                .andExpect(jsonPath("$.feedbackElements[0].propertyPath").value("$.events[0]"));
    }

    @Test
    void anEmptyOrUnreadableRequestIsRefusedInTheStandardsErrorFormat() throws Exception {
        mvc.perform(post("/api/port-call/v2/events").contentType(MediaType.APPLICATION_JSON).content("{\"events\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.feedbackElements[0].severity").value("ERROR"));
        mvc.perform(post("/api/port-call/v2/events").contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.feedbackElements[0].severity").value("ERROR"));
    }
}
