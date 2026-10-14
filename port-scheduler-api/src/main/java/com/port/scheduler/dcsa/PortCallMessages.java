package com.port.scheduler.dcsa;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Message shapes of the DCSA Port Call standard, version 2.0.0, limited to the
 * ones this service sends. Names, nesting and codes follow the standard's
 * OpenAPI specification exactly; attributes the service has no data for are
 * left out, which the standard allows because none is mandatory.
 */
public final class PortCallMessages {

    private PortCallMessages() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GetEventsResponse(List<Event> events, List<FeedbackElement> feedbackElements) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GetEventsError(List<FeedbackElement> feedbackElements) {}

    public record PostEventsRequest(List<Event> events) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PostEventsResponse(List<FeedbackElement> feedbackElements) {}

    /** {@code severity} is ERROR, WARN or INFO. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FeedbackElement(String severity, String message, String propertyPath) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Event(UUID eventID, UUID replyToEventID, Instant eventUpdatedDateTime,
                        PortCall portCall, TerminalCall terminalCall, PortCallService portCallService,
                        Vessel vessel, Timestamp timestamp) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PortCall(UUID portCallID, String portVisitReference,
                           @JsonProperty("UNLocationCode") String unLocationCode, Boolean isOmitted) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TerminalCall(UUID terminalCallID, Integer sequenceNumber,
                               VoyageNumberOrReference exportVoyageNumberOrReference,
                               VoyageNumberOrReference importVoyageNumberOrReference, Boolean isOmitted) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VoyageNumberOrReference(String carrierVoyageNumber) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PortCallService(UUID portCallServiceID, String portCallServiceTypeCode,
                                  String portCallServiceEventTypeCode, String portCallPhaseTypeCode,
                                  String facilityTypeCode, Location serviceLocation, Boolean isCanceled) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Location(String locationName, @JsonProperty("UNLocationCode") String unLocationCode,
                           GeoCoordinate geoCoordinate) {}

    /** Decimal degrees as text, as the standard specifies. */
    public record GeoCoordinate(String latitude, String longitude) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Timestamp(String classifierCode, Instant serviceDateTime, String remark) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Vessel(String vesselIMONumber, String vesselMMSINumber, String vesselName,
                         String vesselCallSign, String vesselTypeCode,
                         Location vesselPosition, Double milesToDestinationPort, String vesselSizeUnit,
                         Double lengthOverall, Double width, String draftUnit, Double airDraft, Double draft) {}
}
