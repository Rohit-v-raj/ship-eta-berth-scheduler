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
import com.port.scheduler.repository.BerthAllocationRepository;
import com.port.scheduler.repository.BerthRepository;
import com.port.scheduler.repository.VesselVisitRepository;
import com.port.scheduler.repository.VisitEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * first. Berths can be released to free them for the next vessel (demo lifecycle).
 */
@Service
public class BerthService {

    public record Assignment(String status, String berthId, String berthName, Integer queueNumber) {}

    public record BerthStatus(String id, String name, boolean occupied, String vesselId) {}

    private final BerthRepository berthRepository;
    private final BerthAllocationRepository allocationRepository;
    private final VesselVisitRepository visitRepository;
    private final VisitEventRepository eventRepository;

    public BerthService(BerthRepository berthRepository,
                        BerthAllocationRepository allocationRepository,
                        VesselVisitRepository visitRepository,
                        VisitEventRepository eventRepository) {
        this.berthRepository = berthRepository;
        this.allocationRepository = allocationRepository;
        this.visitRepository = visitRepository;
        this.eventRepository = eventRepository;
    }

    /**
     * Give the visit the first free berth, or its queue number if the port is
     * full. A visit that already holds a berth keeps it.
     *
     * The berth is offered for the visit's current ETA. From the terminal that
     * is a requested arrival time (RTA Berth); it becomes a planned one (PTA
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
            return new Assignment("QUEUED", null, null, queue.indexOf(visit.getId()) + 1);
        }

        Berth berth = free.get(0);
        allocationRepository.save(new BerthAllocation(visit, berth, visit.getEtaBerth()));
        visit.setRtaBerth(visit.getEtaBerth());
        visit.setTerminal(berth.getQuay().getTerminal());
        eventRepository.save(VisitEvent.of(visit, Milestone.ARRIVAL_BERTH, TimeClassifier.REQUESTED,
                visit.getEtaBerth(), Instant.now(), EventSource.TERMINAL, berth));
        return berthed(berth);
    }

    /** Free a berth so the next vessel can use it. Returns true if it was occupied. */
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
        return true;
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

    private static Assignment berthed(Berth berth) {
        return new Assignment("BERTHED", berth.getCode(), berth.getName(), null);
    }
}
