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
     * is recorded against the vessel and the call. A vessel that fits no berth
     * here is refused, and no call is opened for it.
     */
    @Transactional
    public Scheduled declareEta(String vesselId, Instant eta, ReportedPosition position, VesselSize size) {
        Instant now = Instant.now();
        Vessel vessel = vesselRepository.findByCode(vesselId)
                .orElseGet(() -> vesselRepository.save(new Vessel(vesselId)));
        Optional<VesselVisit> open = visitRepository.findByVesselAndPhaseNotIn(vessel, VisitPhase.CLOSED);
        BigDecimal draft = open.map(VesselVisit::getArrivalDraftMeters).orElse(null);
        if (size != null) {
            if (size.lengthOverallMeters() != null) {
                vessel.setLoaMeters(metres(size.lengthOverallMeters()));
            }
            if (size.beamMeters() != null) {
                vessel.setBeamMeters(metres(size.beamMeters()));
            }
            if (size.draftMeters() != null) {
                draft = metres(size.draftMeters());
            }
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
            eventRepository.save(VisitEvent.of(visit, Milestone.ARRIVAL_BERTH, TimeClassifier.PLANNED,
                    requested, Instant.now(), EventSource.VESSEL, null));
        }
        return requested;
    }

    /** The vessel reports it is alongside. */
    @Transactional
    public void arrived(String vesselId, Instant at) {
        berthService.arrive(openVisit(vesselId), at);
    }

    /** The vessel reports it has left the berth. */
    @Transactional
    public void departed(String vesselId) {
        berthService.depart(openVisit(vesselId));
    }

    /**
     * The terminal's own code for a vessel named by its international
     * identifiers, creating the vessel record if it is not yet known. A type
     * given for a vessel already on record is kept as its type.
     */
    @Transactional
    public String resolveVessel(String imoNumber, String mmsi, String name, VesselType type) {
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
        if (known.isPresent()) {
            if (type != null) {
                known.get().setType(type);
            }
            return known.get().getCode();
        }
        String code = imoNumber != null ? "IMO" + imoNumber : mmsi != null ? "MMSI" + mmsi : name;
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

    /** The vessel operator skips this call. Its berth, if it held one, goes to the head of the queue. */
    @Transactional
    public void omit(String vesselId) {
        VesselVisit visit = openVisit(vesselId);
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

    private VesselVisit openVisit(String vesselId) {
        return vesselRepository.findByCode(vesselId)
                .flatMap(vessel -> visitRepository.findByVesselAndPhaseNotIn(vessel, VisitPhase.CLOSED))
                .orElseThrow(() -> new VisitNotFoundException("Vessel " + vesselId + " has no open visit"));
    }

    private static BigDecimal metres(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static String newVisitRef(Instant now) {
        return "VV-" + REF_DATE.format(now) + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
