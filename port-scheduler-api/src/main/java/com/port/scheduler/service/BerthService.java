package com.port.scheduler.service;

import com.port.scheduler.entity.topology.Berth;
import com.port.scheduler.entity.topology.OperationalStatus;
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
import com.port.scheduler.repository.BerthRepository;
import com.port.scheduler.repository.VesselVisitRepository;
import com.port.scheduler.repository.VisitEventRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Berth allocation and occupancy, backed by the database.
 *
 * A berth is occupied while it has an open allocation; the berth row itself
 * carries no occupancy flag. A vessel that cannot be given a berth waits in a
 * virtual queue, which is simply the open visits that hold no berth, oldest
 * first. A queued vessel is told when a berth is expected to come free, and a
 * released berth goes to the head of the queue.
 */
@Service
public class BerthService {

    public record Assignment(String status, String berthId, String berthName, Integer queueNumber) {}

    public record BerthStatus(String id, String name, boolean occupied, String vesselId) {}

    private final BerthRepository berthRepository;
    private final BerthAllocationRepository allocationRepository;
    private final VesselVisitRepository visitRepository;
    private final VisitEventRepository eventRepository;
    private final Duration reissueThreshold;

    public BerthService(BerthRepository berthRepository,
                        BerthAllocationRepository allocationRepository,
                        VesselVisitRepository visitRepository,
                        VisitEventRepository eventRepository,
                        @Value("${port.planning.reissue-threshold-minutes:15}") long reissueThresholdMinutes) {
        this.reissueThreshold = Duration.ofMinutes(reissueThresholdMinutes);
        this.berthRepository = berthRepository;
        this.allocationRepository = allocationRepository;
        this.visitRepository = visitRepository;
        this.eventRepository = eventRepository;
    }

    /**
     * Give the visit the first free berth, or its queue number if the port is
     * full. A visit that already holds a berth keeps it.
     *
     * Either way the visit gets a requested arrival time (RTA Berth): its own
     * ETA when a berth is free, otherwise the time a berth is expected to come
     * free. That is the terminal's request; it becomes a planned time (PTA
     * Berth) only when the vessel confirms it, so pta_berth is left empty here.
     */
    @Transactional
    public Assignment allocate(VesselVisit visit) {
        Optional<BerthAllocation> held = allocationRepository.findByVisitAndStatusIn(visit, AllocationStatus.OPEN);
        if (held.isPresent()) {
            return berthed(held.get().getBerth());
        }

        List<Berth> free = berthRepository.findFreeForUpdate(OperationalStatus.ACTIVE, AllocationStatus.OPEN);
        if (free.isEmpty()) {
            List<Long> queue = visitRepository.findWaitingIds(VisitPhase.CLOSED, AllocationStatus.OPEN);
            int position = queue.indexOf(visit.getId()) + 1;
            Instant berthFree = expectedFree(position, visit);
            request(visit, later(berthFree, visit.getEtaBerth()), null, "Berth expected free " + berthFree);
            return new Assignment("QUEUED", null, null, position);
        }

        Berth berth = free.get(0);
        assign(visit, berth, visit.getEtaBerth());
        return berthed(berth);
    }

    /**
     * Free a berth and hand it to the vessel that has waited longest, if any.
     * Returns true if the berth was occupied.
     */
    @Transactional
    public boolean release(String berthId) {
        Berth berth = berthRepository.findByCode(berthId)
                .orElseThrow(() -> new BerthNotFoundException("Berth ID " + berthId + " does not exist"));
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

        handOver(berth, now);
        return true;
    }

    /**
     * The vessel has given a new ETA. A berth it already holds moves to that
     * time and a new arrival is requested; otherwise it is allocated as usual.
     */
    @Transactional
    public Assignment reschedule(VesselVisit visit) {
        allocationRepository.findByVisitAndStatusIn(visit, AllocationStatus.OPEN).ifPresent(allocation -> {
            allocation.setPlannedStart(visit.getEtaBerth());
            allocation.setPlannedEnd(visit.getEtaBerth().plus(stay(visit)));
            request(visit, visit.getEtaBerth(), allocation.getBerth(), null);
        });
        return allocate(visit);
    }

    /** The vessel is alongside: the allocation becomes active and the visit berthed. */
    @Transactional
    public void arrive(VesselVisit visit, Instant at) {
        BerthAllocation allocation = allocationRepository.findByVisitAndStatusIn(visit, AllocationStatus.OPEN)
                .orElseThrow(() -> new VisitConflictException("The vessel has no berth allocated yet"));
        allocation.setStatus(AllocationStatus.ACTIVE);
        allocation.setActualStart(at);
        visit.setAtaBerth(at);
        visit.setPhase(VisitPhase.BERTHED);
        eventRepository.save(VisitEvent.of(visit, Milestone.ARRIVAL_BERTH, TimeClassifier.ACTUAL,
                at, Instant.now(), EventSource.VESSEL, allocation.getBerth()));
    }

    /** The vessel has left: its berth is released and passed on. */
    @Transactional
    public void depart(VesselVisit visit) {
        BerthAllocation allocation = allocationRepository.findByVisitAndStatusIn(visit, AllocationStatus.OPEN)
                .orElseThrow(() -> new VisitConflictException("The vessel holds no berth to depart from"));
        release(allocation.getBerth().getCode());
    }

    /** Cancel a visit. Its berth, if it held one, goes to the vessel that has waited longest. */
    @Transactional
    public void cancel(VesselVisit visit) {
        Instant now = Instant.now();
        visit.setPhase(VisitPhase.CANCELLED);
        allocationRepository.findByVisitAndStatusIn(visit, AllocationStatus.OPEN).ifPresent(allocation -> {
            allocation.setStatus(AllocationStatus.CANCELLED);
            handOver(allocation.getBerth(), now);
        });
    }

    @Transactional(readOnly = true)
    public List<BerthStatus> status() {
        Map<Long, String> vesselByBerth = allocationRepository.findWithVesselByStatusIn(AllocationStatus.OPEN).stream()
                .collect(Collectors.toMap(a -> a.getBerth().getId(), a -> a.getVisit().getVessel().getCode()));
        return berthRepository.findAllByOrderBySeqAsc().stream()
                .map(b -> new BerthStatus(b.getCode(), b.getName(),
                        vesselByBerth.containsKey(b.getId()), vesselByBerth.get(b.getId())))
                .toList();
    }

    /** Give a berth that has just come free to the head of the queue, if anyone is waiting. */
    private void handOver(Berth berth, Instant now) {
        if (berth.getStatus() != OperationalStatus.ACTIVE) {
            return;
        }
        // the closed allocation must reach the database before another takes the berth
        allocationRepository.flush();
        visitRepository.findWaitingIds(VisitPhase.CLOSED, AllocationStatus.OPEN).stream().findFirst()
                .flatMap(visitRepository::findById)
                .ifPresent(next -> assign(next, berth, later(now, next.getEtaBerth())));
    }

    /** Allocate the berth from the given time and ask the vessel to arrive then. */
    private void assign(VesselVisit visit, Berth berth, Instant start) {
        BerthAllocation allocation = new BerthAllocation(visit, berth, start);
        allocation.setPlannedEnd(start.plus(stay(visit)));
        allocationRepository.save(allocation);
        visit.setTerminal(berth.getQuay().getTerminal());
        request(visit, start, berth, null);
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

    /**
     * When a berth is expected to come free for the vessel at this place in the
     * queue: the first waiting vessel gets the earliest planned end, the second
     * the next, and so on. Past one round of berths, each further round adds
     * the visit's own expected stay, which is an approximation.
     */
    private Instant expectedFree(int position, VesselVisit visit) {
        Instant now = Instant.now();
        List<Instant> ends = allocationRepository.findByStatusIn(AllocationStatus.OPEN).stream()
                .map(a -> later(now, a.getPlannedEnd()))
                .sorted()
                .toList();
        if (ends.isEmpty()) {
            return now;
        }
        int ahead = Math.max(position, 1) - 1;
        return ends.get(ahead % ends.size()).plus(stay(visit).multipliedBy(ahead / ends.size()));
    }

    private static Duration stay(VesselVisit visit) {
        Integer minutes = visit.getPlannedServiceMinutes();
        return minutes != null ? Duration.ofMinutes(minutes) : PlanningRules.typicalStay(visit.getVessel().getType());
    }

    /** The later of the two; {@code other} may be null. */
    private static Instant later(Instant time, Instant other) {
        return other != null && other.isAfter(time) ? other : time;
    }

    private static Assignment berthed(Berth berth) {
        return new Assignment("BERTHED", berth.getCode(), berth.getName(), null);
    }
}
