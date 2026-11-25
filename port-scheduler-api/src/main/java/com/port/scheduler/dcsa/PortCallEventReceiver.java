package com.port.scheduler.dcsa;

import com.port.scheduler.dcsa.PortCallMessages.Event;
import com.port.scheduler.dcsa.PortCallMessages.FeedbackElement;
import com.port.scheduler.dcsa.PortCallMessages.GeoCoordinate;
import com.port.scheduler.dcsa.PortCallMessages.PortCallService;
import com.port.scheduler.dcsa.PortCallMessages.Timestamp;
import com.port.scheduler.dcsa.PortCallMessages.Vessel;
import com.port.scheduler.entity.dcsa.ReceivedEvent;
import com.port.scheduler.entity.topology.Port;
import com.port.scheduler.entity.vessel.VesselType;
import com.port.scheduler.exceptions.StaleEventException;
import com.port.scheduler.repository.PortRepository;
import com.port.scheduler.repository.ReceivedEventRepository;
import com.port.scheduler.service.VesselVisitService;
import com.port.scheduler.service.VesselVisitService.ReportedPosition;
import com.port.scheduler.service.VesselVisitService.VesselSize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Applies DCSA Port Call 2.0 events sent to this terminal by a vessel operator.
 *
 * The berth service is acted on: an estimated arrival opens or refreshes the
 * visit and is answered with a requested arrival, a planned arrival confirms
 * that request, an actual arrival or departure moves the vessel on and off its
 * berth, and an omitted call is cancelled. So is the anchorage: a vessel that
 * is early anchors and reports it (actual STRT), which may bring its berth
 * forward, and reports leaving (actual CMPL). Other services are answered
 * "not processed". Each event is applied
 * in its own transaction, so one that fails does not undo the others; what
 * went wrong is reported per event, as the standard specifies. An event for
 * another port is refused. A repeated event is answered and not applied
 * again, and an estimate or omitted call that arrives after a later event
 * from the same vessel is not applied.
 */
@Service
public class PortCallEventReceiver {

    private static final Logger log = LoggerFactory.getLogger(PortCallEventReceiver.class);
    private static final double FEET_TO_METRES = 0.3048;

    /** How far ahead of the terminal's clock a sender's time may be, for clocks that differ a little. */
    private static final Duration CLOCK_LEEWAY = Duration.ofMinutes(5);

    private final VesselVisitService visits;
    private final PortRepository portRepository;
    private final ReceivedEventRepository received;
    private final TransactionTemplate transactions;

    public PortCallEventReceiver(VesselVisitService visits, PortRepository portRepository,
                                 ReceivedEventRepository received, PlatformTransactionManager transactionManager) {
        this.visits = visits;
        this.portRepository = portRepository;
        this.received = received;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public List<FeedbackElement> receive(List<Event> events) {
        String here = thisPort();
        List<FeedbackElement> feedback = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            receive(events.get(i), "$.events[" + i + "]", here).ifPresent(feedback::add);
        }
        return feedback;
    }

    /**
     * One event, reported against {@code path}, its place in the request: what
     * the sender is told about it, or empty when it was applied with nothing to
     * report. The controller uses this to apply each event that could be read
     * while reporting one that could not.
     */
    public Optional<FeedbackElement> receive(Event event, String path) {
        return receive(event, path, thisPort());
    }

    private Optional<FeedbackElement> receive(Event event, String path, String here) {
        try {
            // one transaction per event: its effects and its receipt are kept together, or neither
            Note note = transactions.execute(status -> apply(event, here));
            return note == null ? Optional.empty() : Optional.of(new FeedbackElement(note.severity(), note.message(), path));
        } catch (StaleEventException e) {
            return Optional.of(new FeedbackElement("WARN", e.getMessage(), path));
        } catch (DataAccessException | TransactionException e) {
            // the database's own message names tables and columns; the sender gets a plain one
            log.warn("DCSA event {} could not be stored", path, e);
            return Optional.of(new FeedbackElement("ERROR", "The event could not be stored", path));
        } catch (RuntimeException e) {
            return Optional.of(new FeedbackElement("ERROR", e.getMessage(), path));
        }
    }

    private String thisPort() {
        return portRepository.findAll().stream().findFirst().map(Port::getUnLocode).orElse(null);
    }

    /**
     * A note to the sender: {@code WARN} or {@code INFO} for an event understood
     * but not acted on, or {@code WARN} for one applied with a caveat.
     */
    private record Note(String severity, String message) {}

    /**
     * Applies one event. Returns a note when the event was understood but
     * deliberately not acted on, or applied with a caveat (an estimated
     * arrival already past). Everything that can be checked from the event
     * alone is checked before the vessel is looked up, so a refused event
     * leaves no vessel record behind.
     *
     * Events arrive at least once and in no promised order. An event whose
     * {@code eventID} was applied before is not applied again, unless it
     * carries a later {@code eventUpdatedDateTime} (an update of it). An
     * estimate or an omitted call sent before the latest event already applied
     * for its vessel is not applied. An event with no
     * {@code eventUpdatedDateTime} cannot be ordered: it is applied as it
     * comes and takes no part in the order.
     */
    private Note apply(Event event, String here) {
        if (event == null || event.vessel() == null) {
            throw new IllegalArgumentException("An event needs a vessel");
        }
        checkPort(event, here);
        Instant now = Instant.now();
        Instant sentAt = sentAt(event.eventUpdatedDateTime(), now);
        Optional<ReceivedEvent> seen = event.eventID() == null
                ? Optional.empty() : received.findById(event.eventID().toString());
        if (seen.isPresent() && !isLater(sentAt, seen.get().getUpdatedAt())) {
            return new Note("INFO", "Already received at " + seen.get().getReceivedAt()
                    + "; this event was applied then and is not applied again");
        }
        Vessel vessel = event.vessel();

        String code;
        Note caveat = null;
        if (omitted(event)) {
            checkInOrder(vessel, sentAt);
            code = vesselId(vessel);
            visits.omit(code);
        } else {
            PortCallService service = event.portCallService();
            Timestamp timestamp = event.timestamp();
            if (service == null || timestamp == null
                    || timestamp.classifierCode() == null || timestamp.serviceDateTime() == null) {
                throw new IllegalArgumentException(
                        "An event needs a portCallService and a timestamp with classifierCode and serviceDateTime");
            }
            if ("ANCHORAGE".equals(service.portCallServiceTypeCode())) {
                // a ship that is early anchors and says so; the times are facts, so only actual ones are taken
                if (!"ACT".equals(timestamp.classifierCode())) {
                    return new Note("WARN", "Not processed: this terminal records actual anchorage times only");
                }
                String eventType = service.portCallServiceEventTypeCode();
                if (!"STRT".equals(eventType) && !"CMPL".equals(eventType)) {
                    throw new IllegalArgumentException(
                            "An anchorage event is STRT (anchored) or CMPL (left the anchorage), not '" + eventType + "'");
                }
                code = vesselId(vessel);
                if ("STRT".equals(eventType)) {
                    visits.anchored(code, timestamp.serviceDateTime());
                } else {
                    visits.leftAnchorage(code, timestamp.serviceDateTime());
                }
                return applied(event, code, sentAt, seen, now);
            }
            if (!"BERTH".equals(service.portCallServiceTypeCode())) {
                return new Note("WARN", "Not processed: this terminal acts on BERTH and ANCHORAGE events only");
            }
            boolean arrival = "ARRI".equals(service.portCallServiceEventTypeCode());
            switch (timestamp.classifierCode()) {
                case "EST" -> {
                    if (!arrival) {
                        return new Note("WARN", "Not processed: an estimated departure is addressed to the port authority");
                    }
                    ReportedPosition position = position(vessel);
                    VesselSize size = size(vessel);
                    // before the vessel is looked up, so an estimate refused for want of a size leaves no record
                    visits.checkSizeKnown(vessel.vesselIMONumber(), vessel.vesselMMSINumber(), vessel.vesselName(), size);
                    checkInOrder(vessel, sentAt);
                    code = vesselId(vessel);
                    visits.declareEta(code, timestamp.serviceDateTime(), position, size);
                    if (timestamp.serviceDateTime().isBefore(now.minus(CLOCK_LEEWAY))) {
                        caveat = new Note("WARN", "Applied, but the estimated arrival "
                                + timestamp.serviceDateTime() + " has already passed: the ship is planned from now");
                    }
                }
                case "PLN" -> {
                    if (!arrival) {
                        return new Note("WARN", "Not processed: a planned departure is addressed to the port authority");
                    }
                    code = vesselId(vessel);
                    visits.confirm(code, timestamp.serviceDateTime());
                }
                case "ACT" -> {
                    code = vesselId(vessel);
                    if (arrival) {
                        visits.arrived(code, timestamp.serviceDateTime());
                    } else {
                        visits.departed(code, timestamp.serviceDateTime());
                    }
                }
                case "REQ" -> throw new IllegalArgumentException(
                        "A requested time at berth is issued by the terminal, not sent to it");
                default -> throw new IllegalArgumentException(
                        "Unknown classifierCode '" + timestamp.classifierCode() + "'; use EST, PLN or ACT");
            }
        }
        applied(event, code, sentAt, seen, now);
        return caveat;
    }

    /** After an event is applied: note the sender's time for the vessel and keep the event's id. */
    private Note applied(Event event, String code, Instant sentAt, Optional<ReceivedEvent> seen, Instant now) {
        if (sentAt != null) {
            visits.noteSent(code, sentAt);
        }
        remember(event, sentAt, seen, now);
        return null;
    }

    /** Refuse an event sent before the latest one already applied for its vessel; none to check without a time. */
    private void checkInOrder(Vessel vessel, Instant sentAt) {
        if (sentAt != null) {
            visits.checkInOrder(vessel.vesselIMONumber(), vessel.vesselMMSINumber(), vessel.vesselName(), sentAt);
        }
    }

    /** Keep the event's id, so a repeat is recognised; an event with no id cannot be. */
    private void remember(Event event, Instant sentAt, Optional<ReceivedEvent> seen, Instant now) {
        if (event.eventID() == null) {
            return;
        }
        if (seen.isPresent()) {
            seen.get().setUpdatedAt(sentAt);
            seen.get().setReceivedAt(now);
        } else {
            received.save(new ReceivedEvent(event.eventID().toString(), sentAt, now));
        }
    }

    /**
     * The sender's time for the event, to the microsecond the database keeps;
     * null when it gave none. A time more than a few minutes ahead of the clock
     * is refused, so a sender cannot pin the order of later events.
     */
    private static Instant sentAt(Instant updated, Instant now) {
        if (updated == null) {
            return null;
        }
        if (updated.isAfter(now.plus(CLOCK_LEEWAY))) {
            throw new IllegalArgumentException(
                    "eventUpdatedDateTime cannot be in the future: " + updated + " has not happened yet");
        }
        return updated.truncatedTo(ChronoUnit.MICROS);
    }

    /** Whether a delivery is an update of an event already received: it carries a later time. */
    private static boolean isLater(Instant sentAt, Instant before) {
        return sentAt != null && (before == null || sentAt.isAfter(before));
    }

    /** The terminal's code for the vessel the event names, created on first sight. */
    private String vesselId(Vessel vessel) {
        return visits.resolveVessel(vessel.vesselIMONumber(), vessel.vesselMMSINumber(),
                vessel.vesselName(), type(vessel.vesselTypeCode()));
    }

    /**
     * An event that names a port, in {@code portCall} or in the service's
     * location, must name this one. An event that names none is taken as
     * meant for this terminal.
     */
    private static void checkPort(Event event, String here) {
        if (here == null) {
            return;
        }
        String called = event.portCall() == null ? null : event.portCall().unLocationCode();
        PortCallService service = event.portCallService();
        String located = service == null || service.serviceLocation() == null
                ? null : service.serviceLocation().unLocationCode();
        for (String code : new String[] {called, located}) {
            if (code != null && !code.equalsIgnoreCase(here)) {
                throw new IllegalArgumentException("This terminal is at " + here + "; the event is for " + code);
            }
        }
    }

    /**
     * Where the vessel says it is, from the standard's optional
     * {@code vesselPosition} and {@code milesToDestinationPort}. Null when the
     * event carries neither.
     */
    private static ReportedPosition position(Vessel vessel) {
        GeoCoordinate at = vessel.vesselPosition() == null ? null : vessel.vesselPosition().geoCoordinate();
        Double miles = vessel.milesToDestinationPort();
        if (at == null && miles == null) {
            return null;
        }
        if (miles != null && miles < 0) {
            throw new IllegalArgumentException("milesToDestinationPort cannot be negative");
        }
        if (at == null) {
            return new ReportedPosition(null, null, miles);
        }
        try {
            double lat = Double.parseDouble(at.latitude());
            double lon = Double.parseDouble(at.longitude());
            if (Math.abs(lat) > 90 || Math.abs(lon) > 180) {
                throw new NumberFormatException();
            }
            return new ReportedPosition(lat, lon, miles);
        } catch (NumberFormatException | NullPointerException e) {
            throw new IllegalArgumentException(
                    "vesselPosition needs a latitude from -90 to 90 and a longitude from -180 to 180, in decimal degrees");
        }
    }

    /**
     * The vessel's size, from the standard's optional {@code lengthOverall} and
     * {@code width} (in {@code vesselSizeUnit}) and {@code draft} (in
     * {@code draftUnit}), converted to metres. Null when the event carries none.
     */
    private static VesselSize size(Vessel vessel) {
        Double length = metres(vessel.lengthOverall(), vessel.vesselSizeUnit(), "lengthOverall", "vesselSizeUnit");
        Double width = metres(vessel.width(), vessel.vesselSizeUnit(), "width", "vesselSizeUnit");
        Double draft = metres(vessel.draft(), vessel.draftUnit(), "draft", "draftUnit");
        return length == null && width == null && draft == null ? null : new VesselSize(length, width, draft);
    }

    /** A dimension in metres: {@code MTR} as given, {@code FOT} (feet) converted. A missing unit means metres. */
    private static Double metres(Double value, String unit, String field, String unitField) {
        if (value == null) {
            return null;
        }
        if (!(value > 0) || value >= 1000) {
            throw new IllegalArgumentException(field + " must be more than 0 and less than 1000");
        }
        if (unit == null || "MTR".equals(unit)) {
            return value;
        }
        if ("FOT".equals(unit)) {
            return value * FEET_TO_METRES;
        }
        throw new IllegalArgumentException(unitField + " must be MTR or FOT");
    }

    private static boolean omitted(Event event) {
        return (event.portCall() != null && Boolean.TRUE.equals(event.portCall().isOmitted()))
                || (event.terminalCall() != null && Boolean.TRUE.equals(event.terminalCall().isOmitted()));
    }

    private static VesselType type(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "CONT" -> VesselType.CONTAINER;
            case "BULK" -> VesselType.BULK_CARRIER;
            case "TANK", "LGTK" -> VesselType.TANKER;
            case "RORO", "CARC" -> VesselType.RORO;
            case "GCGO" -> VesselType.GENERAL_CARGO;
            case "PASS", "FERY" -> VesselType.PASSENGER;
            default -> VesselType.OTHER;
        };
    }
}
