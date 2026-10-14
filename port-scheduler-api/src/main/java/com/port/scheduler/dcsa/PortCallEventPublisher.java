package com.port.scheduler.dcsa;

import com.port.scheduler.dcsa.PortCallMessages.Event;
import com.port.scheduler.dcsa.PortCallMessages.GeoCoordinate;
import com.port.scheduler.dcsa.PortCallMessages.Location;
import com.port.scheduler.dcsa.PortCallMessages.PortCall;
import com.port.scheduler.dcsa.PortCallMessages.PortCallService;
import com.port.scheduler.dcsa.PortCallMessages.TerminalCall;
import com.port.scheduler.dcsa.PortCallMessages.Timestamp;
import com.port.scheduler.dcsa.PortCallMessages.Vessel;
import com.port.scheduler.dcsa.PortCallMessages.VoyageNumberOrReference;
import com.port.scheduler.entity.topology.Berth;
import com.port.scheduler.entity.topology.Port;
import com.port.scheduler.entity.vessel.VesselType;
import com.port.scheduler.entity.visit.EventSource;
import com.port.scheduler.entity.visit.Milestone;
import com.port.scheduler.entity.visit.TimeClassifier;
import com.port.scheduler.entity.visit.VesselVisit;
import com.port.scheduler.entity.visit.VisitEvent;
import com.port.scheduler.entity.visit.VisitPhase;
import com.port.scheduler.repository.PortRepository;
import com.port.scheduler.repository.VisitEventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Publishes the visit timestamp ledger as DCSA Port Call 2.0 events.
 *
 * Estimates produced by the ETA model are left out: in the standard an
 * estimated arrival at berth is the carrier's to send, and the model's figure
 * is the terminal's own forecast. Identifiers are UUIDs derived from this
 * service's own keys, so they are stable from one request to the next.
 */
@Service
public class PortCallEventPublisher {

    static final int DEFAULT_PAGE_SIZE = 100;
    static final int MAX_PAGE_SIZE = 500;

    /** The filters this publisher supports. Null means "any". */
    public record Filter(String unLocationCode, String vesselIMONumber, String vesselMMSINumber,
                         String vesselName, String portCallServiceTypeCode, String classifierCode,
                         Instant eventTimestampMin, Instant eventTimestampMax, Integer limit, String cursor) {}

    /** One page of events; {@code nextCursor} is null on the last page. */
    public record Page(List<Event> events, String nextCursor) {}

    private final VisitEventRepository eventRepository;
    private final PortRepository portRepository;

    public PortCallEventPublisher(VisitEventRepository eventRepository, PortRepository portRepository) {
        this.eventRepository = eventRepository;
        this.portRepository = portRepository;
    }

    @Transactional(readOnly = true)
    public Page find(Filter filter) {
        // This service runs one port, so every event belongs to it.
        String locode = portRepository.findAll().stream().findFirst().map(Port::getUnLocode).orElse(null);
        TimeClassifier classifier = filter.classifierCode() == null
                ? null : TimeClassifier.fromCode(filter.classifierCode());
        List<Milestone> milestones = Arrays.stream(Milestone.values())
                .filter(m -> filter.portCallServiceTypeCode() == null
                        || m.getServiceTypeCode().equals(filter.portCallServiceTypeCode()))
                .toList();
        int size = Math.min(filter.limit() == null ? DEFAULT_PAGE_SIZE : filter.limit(), MAX_PAGE_SIZE);
        if (size < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        long after = after(filter.cursor());
        if (milestones.isEmpty()
                || (filter.unLocationCode() != null && !filter.unLocationCode().equalsIgnoreCase(locode))) {
            return new Page(List.of(), null);
        }

        List<VisitEvent> rows = eventRepository.findForPublishing(after, EventSource.ML_MODEL, milestones, classifier,
                filter.vesselIMONumber(), filter.vesselMMSINumber(), filter.vesselName(),
                filter.eventTimestampMin(), filter.eventTimestampMax(), PageRequest.of(0, size + 1));
        boolean more = rows.size() > size;
        List<VisitEvent> page = more ? rows.subList(0, size) : rows;
        String next = more ? String.valueOf(page.get(page.size() - 1).getId()) : null;
        return new Page(page.stream().map(e -> toEvent(e, locode)).toList(), next);
    }

    private static long after(String cursor) {
        try {
            return cursor == null ? 0 : Long.parseLong(cursor);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("cursor is not a value this service issued");
        }
    }

    private static Event toEvent(VisitEvent e, String locode) {
        VesselVisit visit = e.getVisit();
        Milestone milestone = e.getMilestone();
        // The standard tells a call omitted by the carrier from a service cancelled by its provider.
        boolean cancelled = visit.getPhase() == VisitPhase.CANCELLED;
        boolean byTerminal = visit.getCancelledBy() == EventSource.TERMINAL;
        Boolean omitted = cancelled && !byTerminal ? Boolean.TRUE : null;
        Boolean canceled = cancelled && byTerminal ? Boolean.TRUE : null;
        Berth berth = e.getBerth();
        Location location = berth == null ? null : new Location(berth.getName(), locode,
                new GeoCoordinate(String.valueOf(berth.getLatitude()), String.valueOf(berth.getLongitude())));
        return new Event(
                id("event", e.getId()),
                null,
                e.getRecordedAt(),
                new PortCall(id("port-call", visit.getId()), visit.getVisitRef(), locode, omitted),
                new TerminalCall(id("terminal-call", visit.getId()), 1,
                        voyage(visit.getOutboundVoyageNo()), voyage(visit.getInboundVoyageNo()), omitted),
                new PortCallService(id("service", visit.getId() + ":" + milestone.name()),
                        milestone.getServiceTypeCode(), milestone.getEventTypeCode(),
                        milestone.getPhaseTypeCode(), milestone.getFacilityTypeCode(), location, canceled),
                toVessel(visit),
                new Timestamp(e.getClassifier().getCode(), e.getEventTime(), e.getRemarks()));
    }

    private static Vessel toVessel(VesselVisit visit) {
        com.port.scheduler.entity.vessel.Vessel v = visit.getVessel();
        Double length = number(v.getLoaMeters());
        Double width = number(v.getBeamMeters());
        Double airDraft = number(v.getAirDraftMeters());
        Double draft = number(visit.getArrivalDraftMeters());
        // A vessel scheduled before its particulars are on record is known only by the terminal's own code.
        String name = v.getName() != null ? v.getName() : v.getCode();
        return new Vessel(v.getImoNumber(), v.getMmsi(), name.length() > 50 ? name.substring(0, 50) : name,
                v.getCallSign(), typeCode(v.getType()), null, null,
                length != null || width != null ? "MTR" : null, length, width,
                airDraft != null || draft != null ? "MTR" : null, airDraft, draft);
    }

    /** The standard's vessel type code; types it has no code for are left out. */
    private static String typeCode(VesselType type) {
        return switch (type) {
            case CONTAINER -> "CONT";
            case BULK_CARRIER -> "BULK";
            case TANKER -> "TANK";
            case RORO -> "RORO";
            case GENERAL_CARGO -> "GCGO";
            case PASSENGER -> "PASS";
            default -> null;
        };
    }

    private static VoyageNumberOrReference voyage(String number) {
        return number == null ? null : new VoyageNumberOrReference(number);
    }

    private static Double number(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private static UUID id(String kind, Object key) {
        return UUID.nameUUIDFromBytes(("port-scheduler:" + kind + ":" + key).getBytes(StandardCharsets.UTF_8));
    }
}
