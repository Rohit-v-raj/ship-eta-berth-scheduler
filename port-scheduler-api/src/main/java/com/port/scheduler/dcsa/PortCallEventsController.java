package com.port.scheduler.dcsa;

import com.port.scheduler.dcsa.PortCallEventPublisher.Filter;
import com.port.scheduler.dcsa.PortCallEventPublisher.Page;
import com.port.scheduler.dcsa.PortCallMessages.FeedbackElement;
import com.port.scheduler.dcsa.PortCallMessages.GetEventsError;
import com.port.scheduler.dcsa.PortCallMessages.GetEventsResponse;
import com.port.scheduler.dcsa.PortCallMessages.PostEventsRequest;
import com.port.scheduler.dcsa.PortCallMessages.PostEventsResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The event publisher endpoint of the DCSA Port Call standard, version 2.0.0:
 * {@code GET /events}. The standard requires a publisher to say which query
 * parameters it supports; the ones not in {@link #SUPPORTED} are refused with
 * a 400 instead of being silently ignored.
 */
@RestController
@RequestMapping("/api/port-call/v2")
public class PortCallEventsController {

    static final String API_VERSION = "2.0.0";

    static final TreeSet<String> SUPPORTED = new TreeSet<>(List.of(
            "UNLocationCode", "vesselIMONumber", "vesselMMSINumber", "vesselName", "portCallServiceTypeCode",
            "classifierCode", "eventTimestampMin", "eventTimestampMax", "limit", "cursor"));

    private final PortCallEventPublisher publisher;
    private final PortCallEventReceiver receiver;

    public PortCallEventsController(PortCallEventPublisher publisher, PortCallEventReceiver receiver) {
        this.publisher = publisher;
        this.receiver = receiver;
    }

    /**
     * The event subscriber endpoint: a vessel operator sends events here. The
     * answer is 200 even when some events could not be applied; each of those
     * is reported in {@code feedbackElements} with its position in the request.
     */
    @PostMapping("/events")
    public ResponseEntity<PostEventsResponse> receive(@RequestBody PostEventsRequest request) {
        if (request.events() == null || request.events().isEmpty()) {
            throw new IllegalArgumentException("The request must contain at least one event");
        }
        List<FeedbackElement> feedback = receiver.receive(request.events());
        return ResponseEntity.ok()
                .header("API-Version", API_VERSION)
                .body(new PostEventsResponse(feedback.isEmpty() ? null : feedback));
    }

    @GetMapping("/events")
    public ResponseEntity<GetEventsResponse> events(@RequestParam Map<String, String> params) {
        for (String name : params.keySet()) {
            if (!SUPPORTED.contains(name)) {
                throw new IllegalArgumentException(
                        "Query parameter '" + name + "' is not supported. Supported: " + String.join(", ", SUPPORTED));
            }
        }
        Page page = publisher.find(new Filter(
                params.get("UNLocationCode"), params.get("vesselIMONumber"), params.get("vesselMMSINumber"),
                params.get("vesselName"), params.get("portCallServiceTypeCode"), params.get("classifierCode"),
                dateTime(params, "eventTimestampMin"), dateTime(params, "eventTimestampMax"),
                limit(params.get("limit")), params.get("cursor")));

        HttpHeaders headers = new HttpHeaders();
        headers.set("API-Version", API_VERSION);
        if (page.nextCursor() != null) {
            headers.set("Next-Page-Cursor", page.nextCursor());
        }
        return new ResponseEntity<>(new GetEventsResponse(page.events(), null), headers, HttpStatus.OK);
    }

    /** Errors in the standard's own format: a list of feedback elements. */
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<GetEventsError> badRequest(Exception e) {
        String message = e instanceof HttpMessageNotReadableException
                ? "The request body could not be read as a DCSA Port Call message" : e.getMessage();
        return ResponseEntity.badRequest()
                .header("API-Version", API_VERSION)
                .body(new GetEventsError(List.of(new FeedbackElement("ERROR", message, null))));
    }

    private static Instant dateTime(Map<String, String> params, String name) {
        String value = params.get(name);
        try {
            return value == null ? null : Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " must be a date-time such as 2025-01-23T01:23:45Z");
        }
    }

    private static Integer limit(String value) {
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("limit must be a whole number");
        }
    }
}
