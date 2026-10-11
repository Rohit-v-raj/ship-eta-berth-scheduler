package com.port.scheduler.service;

import com.port.scheduler.entity.vessel.Vessel;
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
import com.port.scheduler.model.EtaResponse;
import com.port.scheduler.model.ScheduleRequest;
import com.port.scheduler.repository.EtaPredictionRepository;
import com.port.scheduler.repository.VesselRepository;
import com.port.scheduler.repository.VesselVisitRepository;
import com.port.scheduler.repository.VisitEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.port.scheduler.entity.vessel.VesselType;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

/**
 * Vessel visit (port call) lifecycle.
 *
 * A visit opens the first time a vessel asks to be scheduled and stays open
 * until the vessel departs. Further requests from the same vessel are position
 * updates: they refresh that visit's ETA rather than opening a second visit.
 */
@Service
public class VesselVisitService {

    private static final DateTimeFormatter REF_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final VesselRepository vesselRepository;
    private final VesselVisitRepository visitRepository;
    private final EtaPredictionRepository predictionRepository;
    private final VisitEventRepository eventRepository;
    private final BerthService berthService;
    private final String etaModelName;

    public VesselVisitService(VesselRepository vesselRepository,
                              VesselVisitRepository visitRepository,
                              EtaPredictionRepository predictionRepository,
                              VisitEventRepository eventRepository,
                              BerthService berthService,
                              @Value("${eta.model.name}") String etaModelName) {
        this.vesselRepository = vesselRepository;
        this.visitRepository = visitRepository;
        this.predictionRepository = predictionRepository;
        this.eventRepository = eventRepository;
        this.berthService = berthService;
        this.etaModelName = etaModelName;
    }

    /**
     * Record the model's ETA against the vessel's visit, then give the visit a
     * berth or its place in the queue. Call the model before this method so the
     * HTTP round trip stays outside the database transaction.
     */
    @Transactional
    public Scheduled schedule(ScheduleRequest req, EtaResponse eta) {
        Instant now = Instant.now();
        Instant predictedArrival = now.plusSeconds(Math.round(eta.etaMinutes() * 60));

        Vessel vessel = vesselRepository.findByCode(req.vesselId())
                .orElseGet(() -> vesselRepository.save(new Vessel(req.vesselId())));
        VesselVisit visit = visitRepository.findByVesselAndPhaseNotIn(vessel, VisitPhase.CLOSED)
                .orElseGet(() -> visitRepository.save(new VesselVisit(newVisitRef(now), vessel, VisitPhase.INBOUND)));
        visit.setEtaBerth(predictedArrival);
        if (req.vesselType() != null) {
            vessel.setType(req.vesselType());
        }
        visit.setPlannedServiceMinutes((int) PlanningRules.typicalStay(vessel.getType()).toMinutes());

        predictionRepository.save(EtaPrediction.builder()
                .visit(visit)
                .predictedAt(now)
                .source(PredictionSource.ML_MODEL)
                .modelName(etaModelName)
                .lat(req.lat())
                .lon(req.lon())
                .sogKnots(req.sog())
                .cogDegrees(req.cog())
                .destLat(req.destLat())
                .destLon(req.destLon())
                .distanceNm(eta.distanceToDestNm())
                .etaMinutes(eta.etaMinutes())
                .naiveEtaMinutes(eta.naiveEtaMin())
                .predictedArrival(predictedArrival)
                .build());
        eventRepository.save(VisitEvent.of(visit, Milestone.ARRIVAL_BERTH, TimeClassifier.ESTIMATED,
                predictedArrival, now, EventSource.ML_MODEL, null));

        BerthService.Assignment assignment = berthService.allocate(visit);

        // Speed advice only when the berth will not be free by the time the vessel would arrive.
        Instant requested = visit.getRtaBerth();
        Double advisedSpeed = requested != null && requested.isAfter(predictedArrival)
                ? PlanningRules.advisedSpeedKnots(eta.distanceToDestNm(), Duration.between(now, requested),
                        req.sog(), PlanningRules.economicalSpeedKnots(vessel.getType()))
                : null;
        return new Scheduled(assignment, requested, advisedSpeed);
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

    /**
     * The operator's own ETA at berth, as opposed to the model's. Opens the
     * visit if the vessel has none, then allocates a berth or a queue place.
     */
    @Transactional
    public Scheduled declareEta(String vesselId, Instant eta) {
        Instant now = Instant.now();
        Vessel vessel = vesselRepository.findByCode(vesselId)
                .orElseGet(() -> vesselRepository.save(new Vessel(vesselId)));
        VesselVisit visit = visitRepository.findByVesselAndPhaseNotIn(vessel, VisitPhase.CLOSED)
                .orElseGet(() -> visitRepository.save(new VesselVisit(newVisitRef(now), vessel, VisitPhase.INBOUND)));
        visit.setEtaBerth(eta);
        visit.setPlannedServiceMinutes((int) PlanningRules.typicalStay(vessel.getType()).toMinutes());
        eventRepository.save(VisitEvent.of(visit, Milestone.ARRIVAL_BERTH, TimeClassifier.ESTIMATED,
                eta, now, EventSource.VESSEL, null));
        return new Scheduled(berthService.reschedule(visit), visit.getRtaBerth(), null);
    }

    /** The operator rejects the requested arrival and states when the vessel can arrive instead. */
    @Transactional
    public Scheduled replan(String vesselId, Instant eta) {
        openVisit(vesselId);
        return declareEta(vesselId, eta);
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
     * identifiers, creating the vessel record if it is not yet known.
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
        visit.setRemarks("Omitted by the vessel operator");
        berthService.cancel(visit);
    }

    private VesselVisit openVisit(String vesselId) {
        return vesselRepository.findByCode(vesselId)
                .flatMap(vessel -> visitRepository.findByVesselAndPhaseNotIn(vessel, VisitPhase.CLOSED))
                .orElseThrow(() -> new VisitNotFoundException("Vessel " + vesselId + " has no open visit"));
    }

    /** The outcome of scheduling: berth or queue place, when to arrive, and at what speed if slower helps. */
    public record Scheduled(BerthService.Assignment assignment, Instant requestedArrival, Double advisedSpeedKnots) {}

    private static String newVisitRef(Instant now) {
        return "VV-" + REF_DATE.format(now) + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
