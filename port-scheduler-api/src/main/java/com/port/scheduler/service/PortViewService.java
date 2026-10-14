package com.port.scheduler.service;

import com.port.scheduler.entity.topology.Berth;
import com.port.scheduler.entity.topology.BerthMaintenance;
import com.port.scheduler.entity.topology.Port;
import com.port.scheduler.entity.topology.Terminal;
import com.port.scheduler.entity.vessel.Vessel;
import com.port.scheduler.entity.visit.AllocationStatus;
import com.port.scheduler.entity.visit.BerthAllocation;
import com.port.scheduler.entity.visit.EtaPrediction;
import com.port.scheduler.entity.visit.Milestone;
import com.port.scheduler.entity.visit.TimeClassifier;
import com.port.scheduler.entity.visit.VesselVisit;
import com.port.scheduler.entity.visit.VisitPhase;
import com.port.scheduler.exceptions.VisitNotFoundException;
import com.port.scheduler.model.view.BerthPlanView;
import com.port.scheduler.model.view.BerthPlanView.Lane;
import com.port.scheduler.model.view.BerthPlanView.Window;
import com.port.scheduler.model.view.MaintenanceView;
import com.port.scheduler.model.view.PortView;
import com.port.scheduler.model.view.PortView.BerthView;
import com.port.scheduler.model.view.PortView.TerminalView;
import com.port.scheduler.model.view.VisitDetailView;
import com.port.scheduler.model.view.VisitDetailView.EventView;
import com.port.scheduler.model.view.VisitView;
import com.port.scheduler.model.view.VisitView.BerthWindow;
import com.port.scheduler.model.view.VisitView.PositionReport;
import com.port.scheduler.model.view.VisitView.VesselView;
import com.port.scheduler.repository.BerthAllocationRepository;
import com.port.scheduler.repository.BerthMaintenanceRepository;
import com.port.scheduler.repository.BerthRepository;
import com.port.scheduler.repository.EtaPredictionRepository;
import com.port.scheduler.repository.PortRepository;
import com.port.scheduler.repository.VesselVisitRepository;
import com.port.scheduler.repository.VisitEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Read-only views for the console: the port and its berths, the visits in
 * progress, one visit's history, and the berth plan.
 *
 * Nothing here changes state and nothing is stored for it. A queue number is
 * worked out on each read from the same query allocation uses, so the views
 * cannot drift from what a vessel was told. When a vessel's answer to the
 * requested arrival is due is worked out the same way, from the ledger: the
 * terminal flags a late answer and leaves what to do about it to the planner.
 */
@Service
@Transactional(readOnly = true)
public class PortViewService {

    /** The most visits returned when closed ones are asked for too. */
    static final int MAX_VISITS = 200;

    private final PortRepository portRepository;
    private final BerthRepository berthRepository;
    private final VesselVisitRepository visitRepository;
    private final BerthAllocationRepository allocationRepository;
    private final EtaPredictionRepository predictionRepository;
    private final VisitEventRepository eventRepository;
    private final BerthMaintenanceRepository maintenanceRepository;
    private final Duration answerWindow;
    private final List<Duration> estimateMilestones;

    public PortViewService(PortRepository portRepository,
                           BerthRepository berthRepository,
                           VesselVisitRepository visitRepository,
                           BerthAllocationRepository allocationRepository,
                           EtaPredictionRepository predictionRepository,
                           VisitEventRepository eventRepository,
                           BerthMaintenanceRepository maintenanceRepository,
                           @Value("${port.planning.answer-window-minutes:60}") long answerWindowMinutes,
                           @Value("${port.planning.estimate-milestones-hours:72,48,24}") List<Long> milestoneHours) {
        this.answerWindow = Duration.ofMinutes(answerWindowMinutes);
        this.estimateMilestones = milestoneHours.stream().map(Duration::ofHours).toList();
        this.maintenanceRepository = maintenanceRepository;
        this.portRepository = portRepository;
        this.berthRepository = berthRepository;
        this.visitRepository = visitRepository;
        this.allocationRepository = allocationRepository;
        this.predictionRepository = predictionRepository;
        this.eventRepository = eventRepository;
    }

    public PortView port() {
        // This service runs one port.
        Port port = portRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No port has been set up"));
        List<Berth> berths = berthRepository.findAllWithTopology();
        List<TerminalView> terminals = berths.stream().map(b -> b.getQuay().getTerminal()).distinct()
                .map(t -> new TerminalView(t.getCode(), t.getName(), t.getType(), t.getStatus()))
                .toList();
        List<BerthView> berthViews = berths.stream()
                .map(b -> new BerthView(b.getCode(), b.getName(), b.getSeq(),
                        b.getQuay().getTerminal().getCode(), b.getQuay().getCode(),
                        b.getLatitude(), b.getLongitude(), b.getLengthMeters(), b.getDepthMeters(),
                        b.getMaxLoaMeters(), b.getMaxBeamMeters(), b.getMaxDraftMeters(), b.getStatus()))
                .toList();
        return new PortView(port.getUnLocode(), port.getName(), port.getCountryCode(), port.getTimeZone(),
                port.getLatitude(), port.getLongitude(), terminals, berthViews);
    }

    /**
     * Open visits in queue order (oldest first), or with {@code includeClosed}
     * the most recent visits of any state, newest first. A null vessel code
     * means every vessel.
     */
    public List<VisitView> visits(String vesselCode, boolean includeClosed) {
        List<VesselVisit> visits = includeClosed
                ? visitRepository.findAllForView(vesselCode, PageRequest.of(0, MAX_VISITS))
                : visitRepository.findOpenForView(vesselCode, VisitPhase.CLOSED);
        return toViews(visits);
    }

    public VisitDetailView visit(String visitRef) {
        VesselVisit visit = visitRepository.findByVisitRefForView(visitRef)
                .orElseThrow(() -> new VisitNotFoundException("No visit has the reference " + visitRef));
        List<EventView> events = eventRepository.findLedger(visit).stream()
                .map(e -> new EventView(e.getMilestone(), e.getClassifier(), e.getEventTime(), e.getRecordedAt(),
                        e.getSource(), e.getBerth() == null ? null : e.getBerth().getCode(), e.getRemarks()))
                .toList();
        List<PositionReport> reports = predictionRepository.findByVisitOrderByIdAsc(visit).stream()
                .map(PortViewService::toReport)
                .toList();
        return new VisitDetailView(toViews(List.of(visit)).get(0), events, reports);
    }

    public BerthPlanView berthPlan(Instant from, Instant to) {
        Map<Long, List<BerthAllocation>> byBerth = allocationRepository
                .findForPlan(AllocationStatus.OPEN, AllocationStatus.COMPLETED, from, to).stream()
                .collect(Collectors.groupingBy(a -> a.getBerth().getId()));
        Map<Long, List<MaintenanceView>> maintenance = maintenanceRepository.findOverlapping(from, to).stream()
                .collect(Collectors.groupingBy(m -> m.getBerth().getId(),
                        Collectors.mapping(PortViewService::toMaintenance, Collectors.toList())));
        List<Lane> lanes = berthRepository.findAllWithTopology().stream()
                .map(b -> new Lane(b.getCode(), b.getName(), b.getQuay().getTerminal().getCode(), b.getStatus(),
                        byBerth.getOrDefault(b.getId(), List.of()).stream().map(PortViewService::toWindow).toList(),
                        maintenance.getOrDefault(b.getId(), List.of())))
                .toList();
        return new BerthPlanView(from, to, lanes);
    }

    /** Maintenance windows not yet over, on every berth, earliest first. */
    public List<MaintenanceView> maintenance() {
        return maintenanceRepository.findEndingAfter(Instant.now()).stream()
                .map(PortViewService::toMaintenance)
                .toList();
    }

    private List<VisitView> toViews(List<VesselVisit> visits) {
        if (visits.isEmpty()) {
            return List.of();
        }
        List<Long> queue = visitRepository.findWaitingIds(VisitPhase.CLOSED, AllocationStatus.OPEN);
        // ordered oldest first, so a visit's latest allocation is the one kept
        Map<Long, BerthAllocation> allocations = new HashMap<>();
        allocationRepository.findWithBerthByVisitIn(visits).forEach(a -> allocations.put(a.getVisit().getId(), a));
        Map<Long, EtaPrediction> latest = predictionRepository.findLatestByVisitIn(visits).stream()
                .collect(Collectors.toMap(p -> p.getVisit().getId(), p -> p));
        // the ledger knows when the terminal issued the request that now stands, and when the vessel last estimated
        Map<Long, Instant> requestedAt = latestRecorded(visits, TimeClassifier.REQUESTED);
        Map<Long, Instant> estimatedAt = latestRecorded(visits, TimeClassifier.ESTIMATED);
        Instant now = Instant.now();
        return visits.stream()
                .map(v -> toView(v, queue.indexOf(v.getId()), allocations.get(v.getId()), latest.get(v.getId()),
                        requestedAt.get(v.getId()), estimatedAt.get(v.getId()), now))
                .toList();
    }

    private Map<Long, Instant> latestRecorded(List<VesselVisit> visits, TimeClassifier classifier) {
        return eventRepository.findLatestRecordedAt(visits, Milestone.ARRIVAL_BERTH, classifier).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Instant) row[1]));
    }

    private VisitView toView(VesselVisit visit, int queueIndex, BerthAllocation allocation,
                             EtaPrediction report, Instant requestedAt, Instant estimatedAt, Instant now) {
        Vessel vessel = visit.getVessel();
        Terminal terminal = visit.getTerminal();
        boolean open = !VisitPhase.CLOSED.contains(visit.getPhase());
        boolean underWay = open && visit.getAtaBerth() == null;
        // a visit in progress shows only the berth it holds; one whose booking was withdrawn is waiting again
        if (open && allocation != null && !AllocationStatus.OPEN.contains(allocation.getStatus())) {
            allocation = null;
        }
        // an answer is owed while a request stands that the vessel has neither accepted nor overtaken by arriving
        boolean awaitingAnswer = requestedAt != null && visit.getRtaBerth() != null && visit.getPtaBerth() == null
                && underWay;
        Instant arrival = firstOf(visit.getPtaBerth(), visit.getRtaBerth(), visit.getEtaBerth());
        Instant agreed = firstOf(visit.getPtaBerth(), visit.getRtaBerth());
        boolean booked = allocation != null && allocation.getStatus() == AllocationStatus.PLANNED;
        BerthWindow window = allocation == null ? null : new BerthWindow(
                allocation.getBerth().getCode(), allocation.getBerth().getName(), allocation.getStatus(),
                allocation.getPlannedStart(), allocation.getPlannedEnd(),
                allocation.getActualStart(), allocation.getActualEnd());
        return new VisitView(
                visit.getVisitRef(),
                new VesselView(vessel.getCode(), vessel.getName(), vessel.getImoNumber(), vessel.getMmsi(),
                        vessel.getType(), vessel.getLoaMeters(), vessel.getBeamMeters()),
                visit.getPhase(),
                terminal == null ? null : terminal.getCode(),
                window,
                queueIndex < 0 ? null : queueIndex + 1,
                visit.getEtaBerth(), visit.getRtaBerth(), visit.getPtaBerth(),
                visit.getAtaBerth(), visit.getAtdBerth(),
                visit.getPlannedServiceMinutes(),
                report == null ? null : toReport(report),
                requestedAt,
                awaitingAnswer ? requestedAt.plus(answerWindow) : null,
                visit.getCancelledBy(),
                visit.getRemarks(),
                underWay ? PlanningRules.estimateDueSince(arrival, estimatedAt, now, estimateMilestones) : null,
                underWay ? PlanningRules.nextEstimateBy(arrival, now, estimateMilestones) : null,
                booked && agreed != null && agreed.isBefore(now) ? agreed : null,
                visit.getArrivalDraftMeters());
    }

    private static Instant firstOf(Instant... times) {
        for (Instant time : times) {
            if (time != null) {
                return time;
            }
        }
        return null;
    }

    private static MaintenanceView toMaintenance(BerthMaintenance m) {
        return new MaintenanceView(m.getId(), m.getBerth().getCode(), m.getBerth().getName(),
                m.getStartsAt(), m.getEndsAt(), m.getReason());
    }

    private static PositionReport toReport(EtaPrediction p) {
        return new PositionReport(p.getPredictedAt(), p.getLat(), p.getLon(), p.getDistanceNm(),
                p.getPredictedArrival());
    }

    private static Window toWindow(BerthAllocation a) {
        VesselVisit visit = a.getVisit();
        Vessel vessel = visit.getVessel();
        return new Window(visit.getVisitRef(), vessel.getCode(), vessel.getName(), vessel.getType(), a.getStatus(),
                a.getPlannedStart(), a.getPlannedEnd(), a.getActualStart(), a.getActualEnd(),
                visit.getEtaBerth(), visit.getRtaBerth(), visit.getPtaBerth());
    }
}
