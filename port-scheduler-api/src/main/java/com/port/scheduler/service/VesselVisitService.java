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
import com.port.scheduler.model.EtaResponse;
import com.port.scheduler.model.ScheduleRequest;
import com.port.scheduler.repository.EtaPredictionRepository;
import com.port.scheduler.repository.VesselRepository;
import com.port.scheduler.repository.VesselVisitRepository;
import com.port.scheduler.repository.VisitEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
    public BerthService.Assignment schedule(ScheduleRequest req, EtaResponse eta) {
        Instant now = Instant.now();
        Instant predictedArrival = now.plusSeconds(Math.round(eta.etaMinutes() * 60));

        Vessel vessel = vesselRepository.findByCode(req.vesselId())
                .orElseGet(() -> vesselRepository.save(new Vessel(req.vesselId())));
        VesselVisit visit = visitRepository.findByVesselAndPhaseNotIn(vessel, VisitPhase.CLOSED)
                .orElseGet(() -> visitRepository.save(new VesselVisit(newVisitRef(now), vessel, VisitPhase.INBOUND)));
        visit.setEtaBerth(predictedArrival);

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

        return berthService.allocate(visit);
    }

    private static String newVisitRef(Instant now) {
        return "VV-" + REF_DATE.format(now) + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
