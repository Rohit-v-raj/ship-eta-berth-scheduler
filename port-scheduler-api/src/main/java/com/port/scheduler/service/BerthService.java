package com.port.scheduler.service;

import com.port.scheduler.entity.topology.Berth;
import com.port.scheduler.entity.topology.BerthMaintenance;
import com.port.scheduler.entity.topology.OperationalStatus;
import com.port.scheduler.entity.vessel.Vessel;
import com.port.scheduler.entity.visit.AllocationStatus;
import com.port.scheduler.entity.visit.BerthAllocation;
import com.port.scheduler.entity.visit.EventSource;
import com.port.scheduler.entity.visit.Milestone;
import com.port.scheduler.entity.visit.TimeClassifier;
import com.port.scheduler.entity.visit.VesselVisit;
import com.port.scheduler.entity.visit.VisitEvent;
import com.port.scheduler.entity.visit.VisitPhase;
import com.port.scheduler.exceptions.BerthNotFoundException;
import com.port.scheduler.exceptions.VisitConflictException;
import com.port.scheduler.repository.BerthAllocationRepository;
import com.port.scheduler.repository.BerthMaintenanceRepository;
import com.port.scheduler.repository.BerthRepository;
import com.port.scheduler.repository.VesselVisitRepository;
import com.port.scheduler.repository.VisitEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Berth allocation, the waiting line and berth maintenance, backed by the
 * database.
 *
 * A berth is occupied while it has an open allocation; the berth row itself
 * carries no occupancy flag. A vessel that holds no berth waits in a virtual
 * queue, which is simply the open visits that hold no berth, oldest first.
 * After every change to the berth plan the whole line is worked out again
 * ({@link #replan}): each waiting vessel gets a berth it fits, at a time clear
 * of maintenance, and is told when to come.
 */
@Service
public class BerthService {

    public record Assignment(String status, String berthId, String berthName, Integer queueNumber) {}

    /**
     * A waiting vessel's place in the plan: the berth it will use, when that
     * berth comes free, and when the vessel can start there.
     */
    record Slot(VesselVisit visit, Berth berth, Instant berthFree, Instant start) {}

    /** Remarks for the ledger that explain a change, by visit id and by berth id. */
    private record Notes(Map<Long, String> byVisit, Map<Long, String> byBerth) {
        static Notes none() {
            return new Notes(new HashMap<>(), new HashMap<>());
        }
    }

    private final BerthRepository berthRepository;
    private final BerthAllocationRepository allocationRepository;
    private final BerthMaintenanceRepository maintenanceRepository;
    private final VesselVisitRepository visitRepository;
    private final VisitEventRepository eventRepository;
    private final Duration reissueThreshold;
    private final Duration lateTolerance;

    public BerthService(BerthRepository berthRepository,
                        BerthAllocationRepository allocationRepository,
                        BerthMaintenanceRepository maintenanceRepository,
                        VesselVisitRepository visitRepository,
                        VisitEventRepository eventRepository,
                        @Value("${port.planning.reissue-threshold-minutes:15}") long reissueThresholdMinutes,
                        @Value("${port.planning.late-tolerance-minutes:240}") long lateToleranceMinutes) {
        this.reissueThreshold = Duration.ofMinutes(reissueThresholdMinutes);
        this.lateTolerance = Duration.ofMinutes(lateToleranceMinutes);
        this.berthRepository = berthRepository;
        this.allocationRepository = allocationRepository;
        this.maintenanceRepository = maintenanceRepository;
        this.visitRepository = visitRepository;
        this.eventRepository = eventRepository;
    }

    /**
     * Plan a visit that has just declared or changed its estimated arrival.
     *
     * A visit holding a berth it has not reached keeps it at the new time,
     * unless the new time is more than the tolerance after its agreed arrival
     * and a waiting vessel can be alongside sooner: then the berth is withdrawn
     * and the visit waits, keeping its place in line. A visit with no berth is
     * placed with the others by {@link #replan}.
     *
     * Either way the visit gets a requested arrival time (RTA Berth). That is
     * the terminal's request; it becomes a planned time (PTA Berth) only when
     * the vessel confirms it.
     */
    @Transactional
    public Assignment reschedule(VesselVisit visit) {
        List<Berth> berths = lockBerths();
        Notes notes = Notes.none();
        allocationRepository.findByVisitAndStatusIn(visit, AllocationStatus.OPEN)
                .filter(booking -> booking.getStatus() == AllocationStatus.PLANNED)
                .ifPresent(booking -> holdOrWithdraw(visit, booking, notes));
        replan(berths, notes);
        return assignmentOf(visit);
    }

    /**
     * Free a berth and pass it on to the waiting line. Returns true if the
     * berth was occupied.
     */
    @Transactional
    public boolean release(String berthId) {
        Berth berth = berthRepository.findByCode(berthId)
                .orElseThrow(() -> new BerthNotFoundException("Berth ID " + berthId + " does not exist"));
        List<Berth> berths = lockBerths();
        Optional<BerthAllocation> open = allocationRepository.findByBerthAndStatusIn(berth, AllocationStatus.OPEN);
        if (open.isEmpty()) {
            return false; // already free
        }

        Instant now = Instant.now();
        BerthAllocation allocation = open.get();
        allocation.complete(now);
        VesselVisit visit = allocation.getVisit();
        visit.depart(now);
        eventRepository.save(VisitEvent.of(visit, Milestone.DEPARTURE_BERTH, TimeClassifier.ACTUAL,
                now, now, EventSource.TERMINAL, berth));

        replan(berths, Notes.none());
        return true;
    }

    /**
     * The vessel is alongside: the allocation becomes active and the visit
     * berthed. Its stay counts from this moment, not from its estimate, so the
     * berth's planned end moves with an early or a late arrival.
     */
    @Transactional
    public void arrive(VesselVisit visit, Instant at) {
        List<Berth> berths = lockBerths();
        BerthAllocation allocation = allocationRepository.findByVisitAndStatusIn(visit, AllocationStatus.OPEN)
                .orElseThrow(() -> new VisitConflictException("The vessel has no berth allocated yet"));
        allocation.setStatus(AllocationStatus.ACTIVE);
        allocation.setActualStart(at);
        allocation.setPlannedEnd(at.plus(stay(visit)));
        visit.setAtaBerth(at);
        visit.setPhase(VisitPhase.BERTHED);
        eventRepository.save(VisitEvent.of(visit, Milestone.ARRIVAL_BERTH, TimeClassifier.ACTUAL,
                at, Instant.now(), EventSource.VESSEL, allocation.getBerth()));
        replan(berths, Notes.none());
    }

    /** The vessel has left: its berth is released and passed on. */
    @Transactional
    public void depart(VesselVisit visit) {
        BerthAllocation allocation = allocationRepository.findByVisitAndStatusIn(visit, AllocationStatus.OPEN)
                .orElseThrow(() -> new VisitConflictException("The vessel holds no berth to depart from"));
        release(allocation.getBerth().getCode());
    }

    /**
     * Cancel a visit. Its berth, if it held one, goes back to the waiting
     * line; either way the vessels behind it move up.
     */
    @Transactional
    public void cancel(VesselVisit visit) {
        List<Berth> berths = lockBerths();
        visit.setPhase(VisitPhase.CANCELLED);
        allocationRepository.findByVisitAndStatusIn(visit, AllocationStatus.OPEN)
                .ifPresent(allocation -> allocation.setStatus(AllocationStatus.CANCELLED));
        replan(berths, Notes.none());
    }

    /**
     * Refuse a vessel that fits no berth in service, so that it is not left
     * waiting for a berth that will never take it. {@code draft} is the one
     * declared for the call, if any.
     */
    @Transactional
    public void refuseUnlessABerthFits(Vessel vessel, BigDecimal draft) {
        List<Berth> berths = berthRepository.findAllByOrderBySeqAsc().stream()
                .filter(berth -> berth.getStatus() == OperationalStatus.ACTIVE)
                .toList();
        if (!berths.isEmpty() && berths.stream().noneMatch(berth -> BerthFit.fits(berth, vessel, draft))) {
            throw new VisitConflictException("No berth here takes " + vessel.getCode() + ": "
                    + BerthFit.whyNone(vessel, draft, berths));
        }
    }

    /**
     * Record planned maintenance on a berth. Refused when it overlaps the
     * berth's booking (planned or alongside) or another window on the berth.
     * Waiting vessels are then planned around it.
     */
    @Transactional
    public BerthMaintenance scheduleMaintenance(String berthCode, Instant from, Instant to, String reason) {
        if (from == null || to == null || !to.isAfter(from)) {
            throw new IllegalArgumentException("A maintenance window needs a start before its end");
        }
        Berth berth = berthRepository.findByCode(berthCode)
                .orElseThrow(() -> new BerthNotFoundException("Berth ID " + berthCode + " does not exist"));
        List<Berth> berths = lockBerths();
        allocationRepository.findByBerthAndStatusIn(berth, AllocationStatus.OPEN).ifPresent(booking -> {
            Instant start = booking.getActualStart() != null ? booking.getActualStart() : booking.getPlannedStart();
            if (start.isBefore(to) && from.isBefore(booking.getPlannedEnd())) {
                throw new VisitConflictException(berthCode + " is booked for " + booking.getVisit().getVessel().getCode()
                        + " from " + start + " to " + booking.getPlannedEnd()
                        + "; maintenance cannot overlap a booking");
            }
        });
        for (BerthMaintenance window : maintenanceRepository.findByBerthAndEndsAtAfterOrderByStartsAt(berth, from)) {
            if (window.overlaps(from, to)) {
                throw new VisitConflictException(berthCode + " already has maintenance from " + window.getStartsAt()
                        + " to " + window.getEndsAt());
            }
        }
        BerthMaintenance saved = maintenanceRepository.save(
                new BerthMaintenance(berth, from, to, reason == null || reason.isBlank() ? null : reason.strip()));
        maintenanceRepository.flush();
        replan(berths, Notes.none());
        return saved;
    }

    /** Remove a maintenance window. Returns false if the berth has no window with that id. */
    @Transactional
    public boolean removeMaintenance(String berthCode, long id) {
        Berth berth = berthRepository.findByCode(berthCode)
                .orElseThrow(() -> new BerthNotFoundException("Berth ID " + berthCode + " does not exist"));
        List<Berth> berths = lockBerths();
        Optional<BerthMaintenance> window = maintenanceRepository.findByIdAndBerth(id, berth);
        if (window.isEmpty()) {
            return false;
        }
        maintenanceRepository.delete(window.get());
        maintenanceRepository.flush();
        replan(berths, Notes.none());
        return true;
    }

    /**
     * The booked vessel has a new estimate. Keep the booking at the new time,
     * unless the vessel no longer fits the berth, or the new time is more than
     * the tolerance after the agreed arrival and a waiting vessel can be
     * alongside sooner. Then withdraw it, and say why in the ledger.
     */
    private void holdOrWithdraw(VesselVisit visit, BerthAllocation booking, Notes notes) {
        Instant now = Instant.now();
        Berth berth = booking.getBerth();
        if (!BerthFit.fits(berth, visit)) {
            booking.setStatus(AllocationStatus.CANCELLED);
            notes.byVisit().put(visit.getId(), "Berth " + berth.getCode() + " withdrawn: the vessel does not fit it");
            return;
        }
        List<BerthMaintenance> windows = maintenanceRepository.findByBerthAndEndsAtAfterOrderByStartsAt(berth, now);
        Instant start = earliestStart(windows, later(now, visit.getEtaBerth()), stay(visit));
        Instant agreed = visit.getPtaBerth() != null ? visit.getPtaBerth() : visit.getRtaBerth();
        if (agreed != null && Duration.between(agreed, start).compareTo(lateTolerance) > 0
                && waitingVesselGoesFirst(berth, visit, windows, now)) {
            String late = describe(Duration.between(agreed, start));
            booking.setStatus(AllocationStatus.CANCELLED);
            notes.byVisit().put(visit.getId(), "Berth " + berth.getCode() + " withdrawn: this estimate is " + late
                    + " after the agreed arrival, and a waiting vessel can be alongside sooner");
            notes.byBerth().put(berth.getId(), "Given up by " + visit.getVessel().getCode() + ", now expected "
                    + late + " after its agreed arrival");
            return;
        }
        booking.setPlannedStart(start);
        booking.setPlannedEnd(start.plus(stay(visit)));
        request(visit, start, berth, null);
    }

    /** Whether the rule, run for this berth with the booked vessel back in line, gives the berth to another vessel. */
    private boolean waitingVesselGoesFirst(Berth berth, VesselVisit booked, List<BerthMaintenance> windows,
                                           Instant now) {
        List<Long> queue = new ArrayList<>(visitRepository.findWaitingIds(VisitPhase.CLOSED, AllocationStatus.OPEN));
        if (queue.isEmpty()) {
            return false;
        }
        queue.add(booked.getId());
        Collections.sort(queue); // in line by when each call was opened
        Map<Long, VesselVisit> byId = visitRepository.findAllById(queue).stream()
                .collect(Collectors.toMap(VesselVisit::getId, Function.identity()));
        Map<Long, Instant> free = Map.of(berth.getId(), now);
        Map<Long, List<BerthMaintenance>> maintenance = Map.of(berth.getId(), windows);
        List<VesselVisit> line = queue.stream().map(byId::get).filter(v -> BerthFit.fits(berth, v)).toList();
        Instant limit = line.stream().map(v -> startOn(berth, v, free, maintenance))
                .min(Comparator.naturalOrder()).orElseThrow().plus(lateTolerance);
        VesselVisit first = line.stream()
                .filter(v -> !startOn(berth, v, free, maintenance).isAfter(limit))
                .findFirst().orElseThrow();
        return first != booked;
    }

    /**
     * Work the waiting line out again after a change to the berth plan. Each
     * berth in service is available from its booking's planned end, or now if
     * it is free; the line is then placed by {@link #plan}. A vessel placed on
     * a berth that is free now is booked on it at once. Every other waiting
     * vessel is told when to come: a new requested arrival is sent only if the
     * time moved by the re-issue threshold or more, and it cancels the
     * vessel's acceptance of the old one.
     */
    private void replan(List<Berth> berths, Notes notes) {
        allocationRepository.flush();
        Instant now = Instant.now();
        List<Long> queue = visitRepository.findWaitingIds(VisitPhase.CLOSED, AllocationStatus.OPEN);
        if (queue.isEmpty() || berths.isEmpty()) {
            return;
        }
        Map<Long, VesselVisit> byId = visitRepository.findAllById(queue).stream()
                .collect(Collectors.toMap(VesselVisit::getId, Function.identity()));
        List<VesselVisit> line = queue.stream().map(byId::get).toList();
        Map<Long, BerthAllocation> held = allocationRepository.findByStatusIn(AllocationStatus.OPEN).stream()
                .collect(Collectors.toMap(a -> a.getBerth().getId(), Function.identity()));
        Map<Long, List<BerthMaintenance>> maintenance = maintenanceRepository.findEndingAfter(now).stream()
                .collect(Collectors.groupingBy(m -> m.getBerth().getId()));
        Map<Long, Instant> available = new HashMap<>();
        for (Berth berth : berths) {
            BerthAllocation booking = held.get(berth.getId());
            available.put(berth.getId(), booking == null ? now : later(now, booking.getPlannedEnd()));
        }

        Set<Long> bookedNow = new HashSet<>();
        for (Slot slot : plan(line, berths, available, maintenance, lateTolerance)) {
            VesselVisit visit = slot.visit();
            Berth berth = slot.berth();
            String note = notes.byVisit().get(visit.getId());
            if (!held.containsKey(berth.getId()) && bookedNow.add(berth.getId())) {
                assign(visit, berth, slot.start(), notes.byBerth().getOrDefault(berth.getId(), note));
            } else {
                String expected = "Berth expected free " + slot.berthFree();
                request(visit, slot.start(), null, note == null ? expected : note + ". " + expected);
            }
        }
    }

    /**
     * Place the waiting line on the berths. Repeatedly: find the berth where
     * some waiting vessel can start soonest; it goes to the first vessel in
     * line that fits it and can start there within the tolerance of that
     * soonest time, so a vessel that is late is skipped but keeps its place;
     * that vessel then takes whichever berth it can start on soonest.
     *
     * A vessel can start on a berth once the berth is available and the
     * vessel is there, with its whole stay clear of the berth's maintenance.
     */
    static List<Slot> plan(List<VesselVisit> line, List<Berth> berths, Map<Long, Instant> available,
                           Map<Long, List<BerthMaintenance>> maintenance, Duration tolerance) {
        List<VesselVisit> waiting = new ArrayList<>(line);
        Map<Long, Instant> free = new HashMap<>(available);
        List<Slot> slots = new ArrayList<>();
        while (!waiting.isEmpty()) {
            Berth berth = null;
            Instant soonest = null;
            for (Berth candidate : berths) {
                for (VesselVisit visit : waiting) {
                    if (BerthFit.fits(candidate, visit)) {
                        Instant start = startOn(candidate, visit, free, maintenance);
                        if (soonest == null || start.isBefore(soonest)) {
                            soonest = start;
                            berth = candidate;
                        }
                    }
                }
            }
            if (berth == null) {
                break; // no one left fits any berth
            }

            Berth first = berth;
            Instant limit = soonest.plus(tolerance);
            VesselVisit chosen = waiting.stream()
                    .filter(v -> BerthFit.fits(first, v) && !startOn(first, v, free, maintenance).isAfter(limit))
                    .findFirst().orElseThrow();
            Berth best = berth;
            Instant start = startOn(berth, chosen, free, maintenance);
            for (Berth candidate : berths) {
                if (BerthFit.fits(candidate, chosen)) {
                    Instant at = startOn(candidate, chosen, free, maintenance);
                    if (at.isBefore(start)) {
                        start = at;
                        best = candidate;
                    }
                }
            }
            slots.add(new Slot(chosen, best, free.get(best.getId()), start));
            free.put(best.getId(), start.plus(stay(chosen)));
            waiting.remove(chosen);
        }
        return slots;
    }

    private static Instant startOn(Berth berth, VesselVisit visit, Map<Long, Instant> free,
                                   Map<Long, List<BerthMaintenance>> maintenance) {
        return earliestStart(maintenance.getOrDefault(berth.getId(), List.of()),
                later(free.get(berth.getId()), visit.getEtaBerth()), stay(visit));
    }

    /**
     * The first moment from {@code notBefore} at which a stay of this length
     * is clear of every maintenance window. The windows are in start order and
     * never overlap one another.
     */
    static Instant earliestStart(List<BerthMaintenance> windows, Instant notBefore, Duration stay) {
        Instant start = notBefore;
        for (BerthMaintenance window : windows) {
            if (window.overlaps(start, start.plus(stay))) {
                start = window.getEndsAt();
            }
        }
        return start;
    }

    /** Book the berth from the given time and ask the vessel to arrive then. */
    private void assign(VesselVisit visit, Berth berth, Instant start, String remarks) {
        BerthAllocation allocation = new BerthAllocation(visit, berth, start);
        allocation.setPlannedEnd(start.plus(stay(visit)));
        allocationRepository.save(allocation);
        visit.setTerminal(berth.getQuay().getTerminal());
        request(visit, start, berth, remarks);
    }

    /**
     * Record the terminal's requested arrival time. Each change is a new entry
     * in the ledger, and a new request cancels the vessel's confirmation of the
     * old one. A queued vessel's request is re-issued only when it moves by at
     * least the configured threshold, so small ETA updates do not flood it.
     */
    private void request(VesselVisit visit, Instant time, Berth berth, String remarks) {
        Instant previous = visit.getRtaBerth();
        if (berth == null && previous != null
                && Duration.between(previous, time).abs().compareTo(reissueThreshold) < 0) {
            return;
        }
        visit.setRtaBerth(time);
        visit.setPtaBerth(null);
        eventRepository.save(VisitEvent.of(visit, Milestone.ARRIVAL_BERTH, TimeClassifier.REQUESTED,
                time, Instant.now(), EventSource.TERMINAL, berth, remarks));
    }

    private Assignment assignmentOf(VesselVisit visit) {
        Optional<BerthAllocation> held = allocationRepository.findByVisitAndStatusIn(visit, AllocationStatus.OPEN);
        if (held.isPresent()) {
            return berthed(held.get().getBerth());
        }
        List<Long> queue = visitRepository.findWaitingIds(VisitPhase.CLOSED, AllocationStatus.OPEN);
        return new Assignment("QUEUED", null, null, queue.indexOf(visit.getId()) + 1);
    }

    private List<Berth> lockBerths() {
        return berthRepository.findInServiceForUpdate(OperationalStatus.ACTIVE);
    }

    private static Duration stay(VesselVisit visit) {
        Integer minutes = visit.getPlannedServiceMinutes();
        return minutes != null ? Duration.ofMinutes(minutes) : PlanningRules.typicalStay(visit.getVessel().getType());
    }

    /** The later of the two; {@code other} may be null. */
    private static Instant later(Instant time, Instant other) {
        return other != null && other.isAfter(time) ? other : time;
    }

    /** A length of time for a remark: "45 min", "8 h", "8 h 10 min". */
    static String describe(Duration duration) {
        long minutes = Math.round(duration.toSeconds() / 60.0);
        long hours = minutes / 60;
        long rest = minutes % 60;
        if (hours == 0) {
            return rest + " min";
        }
        return rest == 0 ? hours + " h" : hours + " h " + rest + " min";
    }

    private static Assignment berthed(Berth berth) {
        return new Assignment("BERTHED", berth.getCode(), berth.getName(), null);
    }
}
