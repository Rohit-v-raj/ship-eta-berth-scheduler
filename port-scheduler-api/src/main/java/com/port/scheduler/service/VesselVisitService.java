package com.port.scheduler.service;

import com.port.scheduler.entity.vessel.Vessel;
import com.port.scheduler.entity.vessel.VesselType;
import com.port.scheduler.entity.visit.EtaPrediction;
import com.port.scheduler.entity.visit.EventSource;
import com.port.scheduler.entity.visit.Milestone;
import com.port.scheduler.entity.visit.PredictionSource;
import com.port.scheduler.entity.visit.TimeClassifier;
import com.port.scheduler.entity.visit.VesselVisit;
import com.port.scheduler.entity.visit.VisitEvent;
import com.port.scheduler.entity.visit.VisitPhase;
import com.port.scheduler.exceptions.StaleEventException;
import com.port.scheduler.exceptions.VisitConflictException;
import com.port.scheduler.exceptions.VisitNotFoundException;
import com.port.scheduler.repository.EtaPredictionRepository;
import com.port.scheduler.repository.VesselRepository;
import com.port.scheduler.repository.VesselVisitRepository;
import com.port.scheduler.repository.VisitEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

/**
 * Vessel visit (port call) lifecycle.
 *
 * A visit opens when a vessel's operator first declares an estimated arrival
 * and stays open until the vessel departs, its operator omits the call, or
 * the terminal cancels it. A further estimate from the same vessel replans
 * that visit rather than opening a second one.
 */
@Service
public class VesselVisitService {

    private static final DateTimeFormatter REF_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    /** How far ahead of our clock an actual time may be, to allow for clocks that differ a little. */
    static final Duration ACTUAL_TIME_LEEWAY = Duration.ofMinutes(5);

    /**
     * Where a vessel said it was when it gave an estimate. Any part may be
     * missing: the DCSA message carries position and distance as optional.
     */
    public record ReportedPosition(Double lat, Double lon, Double distanceNm) {}

    /**
     * The vessel's size as given with an estimate, in metres: length overall,
     * beam, and the draft for this call. Any part may be missing.
     */
    public record VesselSize(Double lengthOverallMeters, Double beamMeters, Double draftMeters) {}

    /** The outcome of an estimate: berth or queue place, and when the terminal asks the vessel to arrive. */
    public record Scheduled(BerthService.Assignment assignment, Instant requestedArrival) {}

    private final VesselRepository vesselRepository;
    private final VesselVisitRepository visitRepository;
    private final EtaPredictionRepository predictionRepository;
    private final VisitEventRepository eventRepository;
    private final BerthService berthService;

    public VesselVisitService(VesselRepository vesselRepository,
                              VesselVisitRepository visitRepository,
                              EtaPredictionRepository predictionRepository,
                              VisitEventRepository eventRepository,
                              BerthService berthService) {
        this.vesselRepository = vesselRepository;
        this.visitRepository = visitRepository;
        this.predictionRepository = predictionRepository;
        this.eventRepository = eventRepository;
        this.berthService = berthService;
    }

    /** {@link #declareEta(String, Instant, ReportedPosition, VesselSize)} with no size given. */
    @Transactional
    public Scheduled declareEta(String vesselId, Instant eta, ReportedPosition position) {
        return declareEta(vesselId, eta, position, null);
    }

    /**
     * The operator's estimated arrival at berth. Opens the visit if the vessel
     * has none, then allocates a berth or a queue place; for a visit already
     * open it replans. Either way the terminal answers with a requested
     * arrival. A position given with the estimate is kept with it, and a size
     * is recorded against the vessel and the call. A call is opened only for a
     * vessel whose length overall and beam the terminal holds, from this
     * estimate or an earlier one: a berth is given only to a vessel known to
     * fit it. A draft is optional; the largest one declared is kept as the
     * vessel's maximum and used until the call's own arrives. A vessel that
     * fits no berth here is refused, and no call is opened for it. A vessel
     * already alongside is refused too: an estimated arrival no longer applies
     * to it.
     */
    @Transactional
    public Scheduled declareEta(String vesselId, Instant eta, ReportedPosition position, VesselSize size) {
        Instant now = Instant.now();
        Vessel vessel = vesselRepository.findByCode(vesselId)
                .orElseGet(() -> vesselRepository.save(new Vessel(vesselId)));
        Optional<VesselVisit> open = visitRepository.findByVesselAndPhaseNotIn(vessel, VisitPhase.CLOSED);
        if (open.isPresent() && open.get().getAtaBerth() != null) {
            throw new VisitConflictException("Vessel " + vesselId
                    + " is alongside; an estimated arrival no longer applies to this call");
        }
        BigDecimal length = size != null && size.lengthOverallMeters() != null
                ? metres(size.lengthOverallMeters()) : vessel.getLoaMeters();
        BigDecimal beam = size != null && size.beamMeters() != null
                ? metres(size.beamMeters()) : vessel.getBeamMeters();
        BigDecimal draft = size != null && size.draftMeters() != null
                ? metres(size.draftMeters()) : open.map(VesselVisit::getArrivalDraftMeters).orElse(null);
        // checked before anything is recorded: a call opens only for a vessel of known size
        if (open.isEmpty()) {
            requireSize(vesselId, length != null, beam != null);
        }
        vessel.setLoaMeters(length);
        vessel.setBeamMeters(beam);
        if (draft != null && (vessel.getMaxDraftMeters() == null || draft.compareTo(vessel.getMaxDraftMeters()) > 0)) {
            vessel.setMaxDraftMeters(draft);
        }
        // checked before a call is opened, so a vessel that fits nowhere leaves no visit behind
        berthService.refuseUnlessABerthFits(vessel, draft);
        VesselVisit visit = open
                .orElseGet(() -> visitRepository.save(new VesselVisit(newVisitRef(now), vessel, VisitPhase.INBOUND)));
        visit.setArrivalDraftMeters(draft);
        visit.setEtaBerth(eta);
        visit.setPlannedServiceMinutes((int) PlanningRules.typicalStay(vessel.getType()).toMinutes());
        eventRepository.save(VisitEvent.of(visit, Milestone.ARRIVAL_BERTH, TimeClassifier.ESTIMATED,
                eta, now, EventSource.VESSEL, null));
        if (position != null) {
            predictionRepository.save(EtaPrediction.builder()
                    .visit(visit)
                    .predictedAt(now)
                    .source(PredictionSource.VESSEL_DECLARED)
                    .lat(position.lat())
                    .lon(position.lon())
                    .distanceNm(position.distanceNm())
                    .etaMinutes(Duration.between(now, eta).toSeconds() / 60.0)
                    .predictedArrival(eta)
                    .build());
        }
        return new Scheduled(berthService.reschedule(visit), visit.getRtaBerth());
    }

    /**
     * The vessel operator accepts the terminal's requested arrival, which makes
     * it the planned arrival (PTA Berth). Pass the time being confirmed to have
     * a confirmation of a superseded request refused.
     */
    @Transactional
    public Instant confirm(String vesselId, Instant requestedArrival) {
        VesselVisit visit = openVisit(vesselId);
        Instant requested = visit.getRtaBerth();
        if (requested == null) {
            throw new VisitConflictException("Vessel " + vesselId + " has no requested arrival to confirm");
        }
        if (requestedArrival != null && Duration.between(requested, requestedArrival).abs().toMillis() >= 1000) {
            throw new VisitConflictException("The requested arrival is now " + requested + "; confirm that time");
        }
        if (!requested.equals(visit.getPtaBerth())) {
            visit.setPtaBerth(requested);
            // a booked vessel accepts a time at its berth; a waiting one accepts a time only
            eventRepository.save(VisitEvent.of(visit, Milestone.ARRIVAL_BERTH, TimeClassifier.PLANNED,
                    requested, Instant.now(), EventSource.VESSEL, berthService.bookedBerth(visit)));
        }
        return requested;
    }

    /** The vessel reports it is alongside, at the time it gives. */
    @Transactional
    public void arrived(String vesselId, Instant at) {
        notInTheFuture(at, "An actual arrival");
        berthService.arrive(openVisit(vesselId), at);
    }

    /** The vessel reports it has left the berth, at the time it gives. */
    @Transactional
    public void departed(String vesselId, Instant at) {
        notInTheFuture(at, "An actual departure");
        berthService.depart(openVisit(vesselId), at);
    }

    /** The vessel reports it has anchored to wait for its berth, at the time it gives. */
    @Transactional
    public void anchored(String vesselId, Instant at) {
        notInTheFuture(at, "An arrival at the anchorage");
        berthService.anchor(openVisit(vesselId), at);
    }

    /** The vessel reports it has left the anchorage, at the time it gives. */
    @Transactional
    public void leftAnchorage(String vesselId, Instant at) {
        notInTheFuture(at, "A departure from the anchorage");
        berthService.leaveAnchorage(openVisit(vesselId), at);
    }

    /**
     * Refuse an estimate that would open a call for a vessel whose length
     * overall and beam the terminal does not hold, neither in the estimate nor
     * on record. Looks the vessel up without creating it, so a refused event
     * leaves no vessel record. A vessel with a call already open passes: a new
     * estimate for it needs no size.
     */
    @Transactional(readOnly = true)
    public void checkSizeKnown(String imoNumber, String mmsi, String name, VesselSize size) {
        checkIdentifiers(imoNumber, mmsi, name);
        boolean length = size != null && size.lengthOverallMeters() != null;
        boolean beam = size != null && size.beamMeters() != null;
        if (length && beam) {
            return;
        }
        Optional<Vessel> known = findVessel(imoNumber, mmsi, name);
        if (known.isPresent()) {
            if (visitRepository.findByVesselAndPhaseNotIn(known.get(), VisitPhase.CLOSED).isPresent()) {
                return;
            }
            length |= known.get().getLoaMeters() != null;
            beam |= known.get().getBeamMeters() != null;
        }
        requireSize(known.map(Vessel::getCode).orElseGet(() -> codeFor(imoNumber, mmsi, name)), length, beam);
    }

    /**
     * Refuse an event sent before the latest event already applied for its
     * vessel: a later message has overtaken it. Looks the vessel up without
     * creating it; an unknown vessel has nothing to be out of order with.
     */
    @Transactional(readOnly = true)
    public void checkInOrder(String imoNumber, String mmsi, String name, Instant sentAt) {
        findVessel(imoNumber, mmsi, name)
                .map(Vessel::getLastEventSentAt)
                .filter(sentAt::isBefore)
                .ifPresent(latest -> {
                    throw new StaleEventException("Not applied: this event was sent at " + sentAt
                            + ", before the latest event from this vessel, sent at " + latest
                            + ", which overtook it");
                });
    }

    /** Record the sender's time of an event applied for the vessel, if it is the latest yet. */
    @Transactional
    public void noteSent(String vesselId, Instant sentAt) {
        vesselRepository.findByCode(vesselId).ifPresent(vessel -> {
            if (vessel.getLastEventSentAt() == null || sentAt.isAfter(vessel.getLastEventSentAt())) {
                vessel.setLastEventSentAt(sentAt);
            }
        });
    }

    /**
     * The terminal's own code for a vessel named by its international
     * identifiers, creating the vessel record if it is not yet known. A type
     * given for a vessel already on record is kept as its type. Identifiers
     * in the wrong form are refused before anything is looked up or stored.
     */
    @Transactional
    public String resolveVessel(String imoNumber, String mmsi, String name, VesselType type) {
        checkIdentifiers(imoNumber, mmsi, name);
        Optional<Vessel> known = findVessel(imoNumber, mmsi, name);
        if (known.isPresent()) {
            if (type != null) {
                known.get().setType(type);
            }
            return known.get().getCode();
        }
        String code = codeFor(imoNumber, mmsi, name);
        if (code == null) {
            throw new IllegalArgumentException("A vessel needs an IMO number, an MMSI or a name");
        }
        Vessel vessel = new Vessel(code);
        vessel.setImoNumber(imoNumber);
        vessel.setMmsi(mmsi);
        vessel.setName(name);
        if (type != null) {
            vessel.setType(type);
        }
        return vesselRepository.save(vessel).getCode();
    }

    /**
     * The vessel operator skips this call. Its berth, if it held one, goes to
     * the head of the queue. A vessel already alongside cannot skip the call;
     * it reports its departure instead.
     */
    @Transactional
    public void omit(String vesselId) {
        VesselVisit visit = openVisit(vesselId);
        if (visit.getAtaBerth() != null) {
            throw new VisitConflictException("Vessel " + vesselId
                    + " is alongside; report its departure instead of omitting the call");
        }
        visit.setCancelledBy(EventSource.VESSEL);
        visit.setRemarks("Omitted by the vessel operator");
        berthService.cancel(visit);
    }

    /**
     * The terminal cancels a call whose vessel has not arrived, for example
     * when its requested arrival goes unanswered. A berth it held goes to the
     * head of the queue. The vessel may announce itself again, which opens a
     * new visit behind those already waiting.
     */
    @Transactional
    public void cancelByTerminal(String visitRef, String reason) {
        VesselVisit visit = visitRepository.findByVisitRefForView(visitRef)
                .orElseThrow(() -> new VisitNotFoundException("No visit has the reference " + visitRef));
        if (VisitPhase.CLOSED.contains(visit.getPhase())) {
            throw new VisitConflictException("Visit " + visitRef + " is already over");
        }
        if (visit.getAtaBerth() != null) {
            throw new VisitConflictException("The vessel is alongside; release its berth when it leaves");
        }
        visit.setCancelledBy(EventSource.TERMINAL);
        visit.setRemarks(reason == null || reason.isBlank()
                ? "Cancelled by the terminal"
                : "Cancelled by the terminal: " + reason.strip());
        berthService.cancel(visit);
    }

    /**
     * The planner's expected departure for a vessel alongside: its berth's
     * planned end moves there and the waiting line is placed again. The reason
     * is optional and kept with the ledger entry.
     */
    @Transactional
    public void expectCompletion(String visitRef, Instant at, String reason) {
        VesselVisit visit = visitRepository.findByVisitRefForView(visitRef)
                .orElseThrow(() -> new VisitNotFoundException("No visit has the reference " + visitRef));
        if (VisitPhase.CLOSED.contains(visit.getPhase())) {
            throw new VisitConflictException("Visit " + visitRef + " is already over");
        }
        berthService.expectCompletion(visit, at, reason == null || reason.isBlank() ? null : reason.strip());
    }

    /** The vessel record named by IMO number, else MMSI, else name; empty if there is none. */
    private Optional<Vessel> findVessel(String imoNumber, String mmsi, String name) {
        Optional<Vessel> known = Optional.empty();
        if (imoNumber != null) {
            known = vesselRepository.findByImoNumber(imoNumber);
        }
        if (known.isEmpty() && mmsi != null) {
            known = vesselRepository.findByMmsi(mmsi);
        }
        if (known.isEmpty() && name != null) {
            known = vesselRepository.findByCode(name);
        }
        return known;
    }

    /** The code a new vessel record gets: from its IMO number, else its MMSI, else its name. */
    private static String codeFor(String imoNumber, String mmsi, String name) {
        return imoNumber != null ? "IMO" + imoNumber : mmsi != null ? "MMSI" + mmsi : name;
    }

    /** Refuse, saying what is missing, unless both the length overall and the beam are known. */
    private static void requireSize(String vessel, boolean length, boolean beam) {
        if (length && beam) {
            return;
        }
        String missing = !length && !beam ? "length overall or beam" : !length ? "length overall" : "beam";
        String fields = !length && !beam ? "lengthOverall and width" : !length ? "lengthOverall" : "width";
        throw new IllegalArgumentException("The terminal holds no " + missing + " for " + vessel + "; send "
                + fields + " (in vesselSizeUnit) with its estimate. A berth is given only to a vessel known to fit it");
    }

    private VesselVisit openVisit(String vesselId) {
        return vesselRepository.findByCode(vesselId)
                .flatMap(vessel -> visitRepository.findByVesselAndPhaseNotIn(vessel, VisitPhase.CLOSED))
                .orElseThrow(() -> new VisitNotFoundException("Vessel " + vesselId + " has no open visit"));
    }

    private static void notInTheFuture(Instant at, String what) {
        if (at.isAfter(Instant.now().plus(ACTUAL_TIME_LEEWAY))) {
            throw new IllegalArgumentException(what + " cannot be in the future: " + at + " has not happened yet");
        }
    }

    /**
     * IMO numbers have 7 digits and MMSIs 9. A name is at most 100 characters,
     * and at most 32 when it becomes the vessel's code because no IMO number
     * or MMSI is given.
     */
    private static void checkIdentifiers(String imoNumber, String mmsi, String name) {
        if (imoNumber != null && !imoNumber.matches("\\d{7}")) {
            throw new IllegalArgumentException("vesselIMONumber must be 7 digits");
        }
        if (mmsi != null && !mmsi.matches("\\d{9}")) {
            throw new IllegalArgumentException("vesselMMSINumber must be 9 digits");
        }
        if (name != null && name.length() > 100) {
            throw new IllegalArgumentException("vesselName can be at most 100 characters");
        }
        if (name != null && imoNumber == null && mmsi == null && name.length() > 32) {
            throw new IllegalArgumentException(
                    "vesselName can be at most 32 characters when no vesselIMONumber or vesselMMSINumber is given");
        }
    }

    private static BigDecimal metres(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static String newVisitRef(Instant now) {
        return "VV-" + REF_DATE.format(now) + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
