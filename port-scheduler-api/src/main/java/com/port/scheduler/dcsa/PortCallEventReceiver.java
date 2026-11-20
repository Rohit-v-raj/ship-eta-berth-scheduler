package com.port.scheduler.dcsa;

import com.port.scheduler.dcsa.PortCallMessages.Event;
import com.port.scheduler.dcsa.PortCallMessages.FeedbackElement;
import com.port.scheduler.dcsa.PortCallMessages.GeoCoordinate;
import com.port.scheduler.dcsa.PortCallMessages.PortCallService;
import com.port.scheduler.dcsa.PortCallMessages.Timestamp;
import com.port.scheduler.dcsa.PortCallMessages.Vessel;
import com.port.scheduler.entity.topology.Port;
import com.port.scheduler.entity.vessel.VesselType;
import com.port.scheduler.repository.PortRepository;
import com.port.scheduler.service.VesselVisitService;
import com.port.scheduler.service.VesselVisitService.ReportedPosition;
import com.port.scheduler.service.VesselVisitService.VesselSize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

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
 * went wrong is reported per event, as the standard specifies. An event for
 * another port is refused.
 */
@Service
public class PortCallEventReceiver {

    private static final Logger log = LoggerFactory.getLogger(PortCallEventReceiver.class);
    private static final double FEET_TO_METRES = 0.3048;

    private final VesselVisitService visits;
    private final PortRepository portRepository;

    public PortCallEventReceiver(VesselVisitService visits, PortRepository portRepository) {
        this.visits = visits;
        this.portRepository = portRepository;
    }

    public List<FeedbackElement> receive(List<Event> events) {
        String here = portRepository.findAll().stream().findFirst().map(Port::getUnLocode).orElse(null);
        List<FeedbackElement> feedback = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            String path = "$.events[" + i + "]";
            try {
                String note = apply(events.get(i), here);
                if (note != null) {
                    feedback.add(new FeedbackElement("WARN", note, path));
                }
            } catch (DataAccessException | TransactionException e) {
                // the database's own message names tables and columns; the sender gets a plain one
                log.warn("DCSA event {} could not be stored", path, e);
                feedback.add(new FeedbackElement("ERROR", "The event could not be stored", path));
            } catch (RuntimeException e) {
                feedback.add(new FeedbackElement("ERROR", e.getMessage(), path));
            }
        }
        return feedback;
    }

    /**
     * Applies one event. Returns a note when the event was understood but
     * deliberately not acted on. Everything that can be checked from the event
     * alone is checked before the vessel is looked up, so a refused event
     * leaves no vessel record behind.
     */
    private String apply(Event event, String here) {
        if (event == null || event.vessel() == null) {
            throw new IllegalArgumentException("An event needs a vessel");
        }
        checkPort(event, here);
        Vessel vessel = event.vessel();

        if (omitted(event)) {
            visits.omit(vesselId(vessel));
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
                ReportedPosition position = position(vessel);
                VesselSize size = size(vessel);
                visits.declareEta(vesselId(vessel), timestamp.serviceDateTime(), position, size);
            }
            case "PLN" -> {
                if (!arrival) {
                    return "Not processed: a planned departure is addressed to the port authority";
                }
                visits.confirm(vesselId(vessel), timestamp.serviceDateTime());
            }
            case "ACT" -> {
                if (arrival) {
                    visits.arrived(vesselId(vessel), timestamp.serviceDateTime());
                } else {
                    visits.departed(vesselId(vessel), timestamp.serviceDateTime());
                }
            }
            case "REQ" -> throw new IllegalArgumentException(
                    "A requested time at berth is issued by the terminal, not sent to it");
            default -> throw new IllegalArgumentException(
                    "Unknown classifierCode '" + timestamp.classifierCode() + "'; use EST, PLN or ACT");
        }
        return null;
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
