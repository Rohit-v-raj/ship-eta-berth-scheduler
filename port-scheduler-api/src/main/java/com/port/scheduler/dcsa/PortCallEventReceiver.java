package com.port.scheduler.dcsa;

import com.port.scheduler.dcsa.PortCallMessages.Event;
import com.port.scheduler.dcsa.PortCallMessages.FeedbackElement;
import com.port.scheduler.dcsa.PortCallMessages.PortCallService;
import com.port.scheduler.dcsa.PortCallMessages.Timestamp;
import com.port.scheduler.dcsa.PortCallMessages.Vessel;
import com.port.scheduler.entity.vessel.VesselType;
import com.port.scheduler.service.VesselVisitService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies DCSA Port Call 2.0 events sent to this terminal by a vessel operator.
 *
 * Only the berth service is acted on: an estimated arrival opens or refreshes
 * the visit and is answered with a requested arrival, a planned arrival
 * confirms that request, an actual arrival or departure moves the vessel on
 * and off its berth, and an omitted call is cancelled. Each event is applied
 * in its own transaction, so one that fails does not undo the others; what
 * went wrong is reported per event, as the standard specifies.
 */
@Service
public class PortCallEventReceiver {

    private final VesselVisitService visits;

    public PortCallEventReceiver(VesselVisitService visits) {
        this.visits = visits;
    }

    public List<FeedbackElement> receive(List<Event> events) {
        List<FeedbackElement> feedback = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            String path = "$.events[" + i + "]";
            try {
                String note = apply(events.get(i));
                if (note != null) {
                    feedback.add(new FeedbackElement("WARN", note, path));
                }
            } catch (RuntimeException e) {
                feedback.add(new FeedbackElement("ERROR", e.getMessage(), path));
            }
        }
        return feedback;
    }

    /** Applies one event. Returns a note when the event was understood but deliberately not acted on. */
    private String apply(Event event) {
        if (event == null || event.vessel() == null) {
            throw new IllegalArgumentException("An event needs a vessel");
        }
        Vessel vessel = event.vessel();
        String vesselId = visits.resolveVessel(vessel.vesselIMONumber(), vessel.vesselMMSINumber(),
                vessel.vesselName(), type(vessel.vesselTypeCode()));

        if (omitted(event)) {
            visits.omit(vesselId);
            return null;
        }
        PortCallService service = event.portCallService();
        Timestamp timestamp = event.timestamp();
        if (service == null || timestamp == null
                || timestamp.classifierCode() == null || timestamp.serviceDateTime() == null) {
            throw new IllegalArgumentException(
                    "An event needs a portCallService and a timestamp with classifierCode and serviceDateTime");
        }
        if (!"BERTH".equals(service.portCallServiceTypeCode())) {
            return "Not processed: this terminal acts on BERTH events only";
        }
        boolean arrival = "ARRI".equals(service.portCallServiceEventTypeCode());
        switch (timestamp.classifierCode()) {
            case "EST" -> {
                if (!arrival) {
                    return "Not processed: an estimated departure is addressed to the port authority";
                }
                visits.declareEta(vesselId, timestamp.serviceDateTime());
            }
            case "PLN" -> {
                if (!arrival) {
                    return "Not processed: a planned departure is addressed to the port authority";
                }
                visits.confirm(vesselId, timestamp.serviceDateTime());
            }
            case "ACT" -> {
                if (arrival) {
                    visits.arrived(vesselId, timestamp.serviceDateTime());
                } else {
                    visits.departed(vesselId);
                }
            }
            case "REQ" -> throw new IllegalArgumentException(
                    "A requested time at berth is issued by the terminal, not sent to it");
            default -> throw new IllegalArgumentException(
                    "Unknown classifierCode '" + timestamp.classifierCode() + "'; use EST, PLN or ACT");
        }
        return null;
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
