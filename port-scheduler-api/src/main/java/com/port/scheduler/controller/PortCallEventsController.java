package com.port.scheduler.controller;

import com.port.scheduler.dcsa.PortCallEventPublisher;
import com.port.scheduler.dcsa.PortCallEventReceiver;
import com.port.scheduler.dcsa.PortCallEventPublisher.Filter;
import com.port.scheduler.dcsa.PortCallEventPublisher.Page;
import com.port.scheduler.dcsa.PortCallMessages.Event;
import com.port.scheduler.dcsa.PortCallMessages.FeedbackElement;
import com.port.scheduler.dcsa.PortCallMessages.GetEventsError;
import com.port.scheduler.dcsa.PortCallMessages.GetEventsResponse;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.exc.InvalidFormatException;
import tools.jackson.databind.exc.MismatchedInputException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The DCSA Port Call standard, version 2.0.0: the one route by which a vessel
 * operator and the terminal agree a berth. {@code POST /events} receives the
 * operator's timestamps (an estimate opens a call, a planned arrival accepts
 * the terminal's request, and so on) and {@code GET /events} publishes what
 * has been exchanged.
 *
 * The standard requires a publisher to say which query parameters it
 * supports; the ones not in {@link #SUPPORTED} are refused with a 400 instead
 * of being silently ignored. The translation to and from the standard's
 * message shapes is in the {@code dcsa} package.
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
    private final ObjectMapper json;

    public PortCallEventsController(PortCallEventPublisher publisher, PortCallEventReceiver receiver, ObjectMapper json) {
        this.publisher = publisher;
        this.receiver = receiver;
        this.json = json;
    }

    /**
     * The event subscriber endpoint: a vessel operator sends events here. The
     * answer is 200 even when some events could not be applied; each of those
     * is reported in {@code feedbackElements} with its position in the request.
     *
     * Each event is read on its own, so one that cannot be read (a time with no
     * UTC offset, a word where a number belongs) is refused by itself, naming the
     * field, and the others are still applied. Only a body that is not JSON, or
     * has no events, is refused as a whole.
     */
    @PostMapping("/events")
    public ResponseEntity<PostEventsResponse> receive(@RequestBody JsonNode body) {
        JsonNode events = body == null ? null : body.get("events");
        if (events == null || !events.isArray() || events.isEmpty()) {
            throw new IllegalArgumentException("The request must contain at least one event");
        }
        List<FeedbackElement> feedback = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            String path = "$.events[" + i + "]";
            Event event;
            try {
                event = json.treeToValue(events.get(i), Event.class);
            } catch (JacksonException e) {
                feedback.add(unreadable(e, path));
                continue;
            }
            receiver.receive(event, path).ifPresent(feedback::add);
        }
        return ResponseEntity.ok()
                .header("API-Version", API_VERSION)
                .body(new PostEventsResponse(feedback.isEmpty() ? null : feedback));
    }

    /** An event that could not be read, refused by itself with the field that stopped it. */
    static FeedbackElement unreadable(JacksonException e, String path) {
        StringBuilder field = new StringBuilder();
        for (JacksonException.Reference ref : e.getPath()) {
            field.append(ref.getPropertyName() != null ? "." + ref.getPropertyName() : "[" + ref.getIndex() + "]");
        }
        String name = field.length() > 0 ? field.substring(1) : "the event";
        Object value = e instanceof InvalidFormatException format ? format.getValue() : null;
        String shown = value == null ? "" : " '" + abbreviate(String.valueOf(value)) + "'";
        Class<?> target = e instanceof MismatchedInputException mismatch ? mismatch.getTargetType() : null;
        String message;
        if (target == Instant.class) {
            message = name + ":" + shown + " is not a date-time with a UTC offset; send ISO 8601, for example "
                    + "2026-11-25T10:00:00Z";
        } else if (target != null) {
            message = name + ":" + shown + " could not be read as " + describe(target);
        } else {
            message = name + " could not be read";
        }
        return new FeedbackElement("ERROR", message, path + field);
    }

    private static String describe(Class<?> type) {
        if (Number.class.isAssignableFrom(type) || type.isPrimitive() && type != boolean.class) {
            return "a number";
        }
        if (type == Boolean.class || type == boolean.class) {
            return "true or false";
        }
        if (type == java.util.UUID.class) {
            return "a UUID";
        }
        return type == String.class ? "text" : "an object of the expected shape";
    }

    private static String abbreviate(String value) {
        return value.length() <= 40 ? value : value.substring(0, 40) + "…";
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
