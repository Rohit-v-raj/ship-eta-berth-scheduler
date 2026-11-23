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
 * Berth allocation, the berth plan and berth maintenance, backed by the
 * database.
 *
 * Each berth holds a sequence: the vessel alongside, if there is one, then the
 * vessels booked on it, each in a window of its own. The berth row carries no
 * occupancy flag; occupancy is the open allocations. A vessel that can start
 * within the booking horizon is booked on a berth; one due later waits in a
 * virtual queue, which is simply the open visits that hold no berth, oldest
 * first. After every change to the berth plan the plan is worked out again
 * ({@link #replan}).
 */
@Service
public class BerthService {

    public record Assignment(String status, String berthId, String berthName, Integer queueNumber) {}

    /** A vessel's place in the plan: the berth it will use and when it can start there. */
    record Slot(VesselVisit visit, Berth berth, Instant start) {}

    /** A stretch of a berth's time taken by a vessel: from its start until the berth is free again, turnaround included. */
    record Taken(Instant from, Instant to) {
        boolean overlaps(Instant start, Instant end) {
            return start.isBefore(to) && from.isBefore(end);
        }
    }

    /**
     * One berth's time. It is free from {@code from}: after the vessel
     * alongside and the turnaround ({@code fromVessel} true), or after the
     * last departure and the turnaround. Then come the windows already
     * booked, in order.
     */
    static final class Line {
        final Instant from;
        final boolean fromVessel;
        final List<Taken> taken = new ArrayList<>();

        Line(Instant from, boolean fromVessel) {
            this.from = from;
            this.fromVessel = fromVessel;
        }

        Line copy() {
            Line copy = new Line(from, fromVessel);
            copy.taken.addAll(taken);
            return copy;
        }

        void take(Taken window) {
            taken.add(window);
            taken.sort(Comparator.comparing(Taken::from));
        }
    }

    /**
     * Remarks for the ledger that explain a change, by visit id and by berth
     * id, and the visits whose booking the terminal itself took away (for
     * maintenance), which are planned again before anyone else moves.
     */
    private record Notes(Map<Long, String> byVisit, Map<Long, String> byBerth, Set<Long> displaced) {
        static Notes none() {
            return new Notes(new HashMap<>(), new HashMap<>(), new HashSet<>());
        }
    }

    private final BerthRepository berthRepository;
    private final BerthAllocationRepository allocationRepository;
    private final BerthMaintenanceRepository maintenanceRepository;
    private final VesselVisitRepository visitRepository;
    private final VisitEventRepository eventRepository;
    private final Duration reissueThreshold;
    private final Duration lateTolerance;
    private final Duration bookingHorizon;
    private final Duration overstayStep;
    private final Duration turnaround;

    public BerthService(BerthRepository berthRepository,
                        BerthAllocationRepository allocationRepository,
                        BerthMaintenanceRepository maintenanceRepository,
                        VesselVisitRepository visitRepository,
                        VisitEventRepository eventRepository,
                        @Value("${port.planning.reissue-threshold-minutes:15}") long reissueThresholdMinutes,
                        @Value("${port.planning.late-tolerance-minutes:240}") long lateToleranceMinutes,
                        @Value("${port.planning.booking-horizon-hours:168}") long bookingHorizonHours,
                        @Value("${port.planning.overstay-step-minutes:60}") long overstayStepMinutes,
                        @Value("${port.planning.berth-turnaround-minutes:60}") long turnaroundMinutes) {
        this.reissueThreshold = Duration.ofMinutes(reissueThresholdMinutes);
        this.lateTolerance = Duration.ofMinutes(lateToleranceMinutes);
        this.bookingHorizon = Duration.ofHours(bookingHorizonHours);
        this.overstayStep = Duration.ofMinutes(overstayStepMinutes);
        this.turnaround = Duration.ofMinutes(turnaroundMinutes);
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
     * unless it no longer fits the berth, its start is now beyond the booking
     * horizon, or it is more than the tolerance after its agreed arrival and
     * another vessel can be alongside on that berth sooner: then the berth is
     * withdrawn and the visit is planned again, keeping its place in line. A
     * visit with no berth is placed with the others by {@link #replan}.
     *
     * Either way the visit gets a requested arrival time (RTA Berth). That is
     * the terminal's request; it becomes a planned time (PTA Berth) only when
     * the vessel confirms it.
     */
    @Transactional
    public Assignment reschedule(VesselVisit visit) {
        replan(lockBerths(), Notes.none(), visit);
        return assignmentOf(visit);
    }

    /**
     * The planner frees a berth now: the vessel alongside is recorded as
     * departed and the plan moves up. Returns false if no vessel lies there;
     * vessels only booked on the berth are left as they are.
     */
    @Transactional
    public boolean release(String berthId) {
        Berth berth = berthRepository.findByCode(berthId)
                .orElseThrow(() -> new BerthNotFoundException("Berth ID " + berthId + " does not exist"));
        List<Berth> berths = lockBerths();
        Optional<BerthAllocation> alongside = allocationRepository.findByBerthAndStatus(berth, AllocationStatus.ACTIVE);
        if (alongside.isEmpty()) {
            return false; // no vessel lies there
        }
        leave(alongside.get(), berths, Instant.now(), EventSource.TERMINAL);
        return true;
    }

    /**
     * The vessel is alongside: the allocation becomes active and the visit
     * berthed. Its stay counts from this moment, not from its estimate, so the
     * berth's planned end moves with an early or a late arrival. Refused while
     * another vessel is still recorded alongside the berth. A vessel that
     * arrives before others booked ahead of it on the berth is taken in, and
     * they are planned after it.
     */
    @Transactional
    public void arrive(VesselVisit visit, Instant at) {
        List<Berth> berths = lockBerths();
        BerthAllocation allocation = allocationRepository.findByVisitAndStatusIn(visit, AllocationStatus.OPEN)
                .orElseThrow(() -> new VisitConflictException("The vessel has no berth allocated yet"));
        Berth berth = allocation.getBerth();
        allocationRepository.findByBerthAndStatus(berth, AllocationStatus.ACTIVE)
                .filter(other -> !other.getId().equals(allocation.getId()))
                .ifPresent(other -> {
                    throw new VisitConflictException(berth.getCode() + " still has "
                            + other.getVisit().getVessel().getCode() + " alongside; its departure is recorded first");
                });
        allocation.setStatus(AllocationStatus.ACTIVE);
        allocation.setActualStart(at);
        allocation.setPlannedEnd(at.plus(stay(visit)));
        visit.setAtaBerth(at);
        visit.setPhase(VisitPhase.BERTHED);
        eventRepository.save(VisitEvent.of(visit, Milestone.ARRIVAL_BERTH, TimeClassifier.ACTUAL,
                at, Instant.now(), EventSource.VESSEL, berth));
        replan(berths, Notes.none(), null);
    }

    /**
     * The vessel reports it left its berth at the given time: the berth is
     * released and the plan moves up. Refused for a vessel that never arrived,
     * and for a departure dated before its arrival.
     */
    @Transactional
    public void depart(VesselVisit visit, Instant at) {
        List<Berth> berths = lockBerths();
        BerthAllocation allocation = allocationRepository.findByVisitAndStatusIn(visit, AllocationStatus.OPEN)
                .filter(held -> held.getStatus() == AllocationStatus.ACTIVE)
                .orElseThrow(() -> new VisitConflictException("Vessel " + visit.getVessel().getCode()
                        + " has not arrived; a departure needs an arrival first"));
        Instant arrived = allocation.getActualStart();
        if (arrived != null && at.isBefore(arrived)) {
            throw new IllegalArgumentException("The departure (" + at + ") is before the arrival (" + arrived + ")");
        }
        leave(allocation, berths, at, EventSource.VESSEL);
    }

    /** Close the allocation and the visit at the given time, record who said so, and replan. */
    private void leave(BerthAllocation allocation, List<Berth> berths, Instant at, EventSource source) {
        allocation.complete(at);
        VesselVisit visit = allocation.getVisit();
        visit.depart(at);
        eventRepository.save(VisitEvent.of(visit, Milestone.DEPARTURE_BERTH, TimeClassifier.ACTUAL,
                at, Instant.now(), source, allocation.getBerth()));
        replan(berths, Notes.none(), null);
    }

    /**
     * The planner's estimate of when a vessel alongside will have finished and
     * left (IMO's ETC, cargo): the berth's planned end moves to it, the ledger
     * records it as the terminal's estimated completion of cargo operations,
     * and the plan is worked out again. Refused for a vessel not alongside and
     * for a time already past.
     */
    @Transactional
    public void expectCompletion(VesselVisit visit, Instant at, String remark) {
        List<Berth> berths = lockBerths();
        BerthAllocation allocation = allocationRepository.findByVisitAndStatusIn(visit, AllocationStatus.OPEN)
                .filter(held -> held.getStatus() == AllocationStatus.ACTIVE)
                .orElseThrow(() -> new VisitConflictException("Vessel " + visit.getVessel().getCode()
                        + " is not alongside; an expected departure is set for a vessel at its berth"));
        if (!at.isAfter(Instant.now())) {
            throw new IllegalArgumentException("The expected departure must be in the future");
        }
        allocation.setPlannedEnd(at);
        eventRepository.save(VisitEvent.of(visit, Milestone.CARGO_OPS_COMPLETE, TimeClassifier.ESTIMATED,
                at, Instant.now(), EventSource.TERMINAL, allocation.getBerth(), remark));
        replan(berths, Notes.none(), null);
    }

    /** The berth the visit is booked on or lies at, or null while it holds none. */
    @Transactional(readOnly = true)
    public Berth bookedBerth(VesselVisit visit) {
        return allocationRepository.findByVisitAndStatusIn(visit, AllocationStatus.OPEN)
                .map(BerthAllocation::getBerth)
                .orElse(null);
    }

    /**
     * Cancel a visit. Its booking, if it held one, is given up; either way
     * the vessels behind it move up.
     */
    @Transactional
    public void cancel(VesselVisit visit) {
        List<Berth> berths = lockBerths();
        visit.setPhase(VisitPhase.CANCELLED);
        allocationRepository.findByVisitAndStatusIn(visit, AllocationStatus.OPEN)
                .ifPresent(allocation -> allocation.setStatus(AllocationStatus.CANCELLED));
        replan(berths, Notes.none(), null);
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
     * stay of the vessel alongside or another window on the berth. Vessels
     * booked on the berth whose window it overlaps lose that booking and are
     * planned again around it, on that berth later or on another.
     */
    @Transactional
    public BerthMaintenance scheduleMaintenance(String berthCode, Instant from, Instant to, String reason) {
        if (from == null || to == null || !to.isAfter(from)) {
            throw new IllegalArgumentException("A maintenance window needs a start before its end");
        }
        Berth berth = berthRepository.findByCode(berthCode)
                .orElseThrow(() -> new BerthNotFoundException("Berth ID " + berthCode + " does not exist"));
        List<Berth> berths = lockBerths();
        Instant now = Instant.now();
        allocationRepository.findByBerthAndStatus(berth, AllocationStatus.ACTIVE).ifPresent(alongside -> {
            Instant end = expectedEnd(alongside, now);
            if (alongside.getActualStart().isBefore(to) && from.isBefore(end)) {
                throw new VisitConflictException(berthCode + " has " + alongside.getVisit().getVessel().getCode()
                        + " alongside until " + end + "; maintenance cannot overlap a vessel at the berth");
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
        Notes notes = Notes.none();
        for (BerthAllocation booking : allocationRepository.findByBerthAndStatusIn(berth, List.of(AllocationStatus.PLANNED))) {
            if (saved.overlaps(booking.getPlannedStart(), booking.getPlannedEnd())) {
                booking.setStatus(AllocationStatus.CANCELLED);
                notes.displaced().add(booking.getVisit().getId());
                notes.byVisit().put(booking.getVisit().getId(),
                        "Moved for maintenance on " + berthCode + ", " + from + " to " + to);
            }
        }
        replan(berths, notes, null);
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
        replan(berths, Notes.none(), null);
        return true;
    }

    /**
     * Work the berth plan out again after a change. In order:
     *
     * <ol>
     * <li>Each berth in service is free after the vessel alongside (one
     * overstay step from now if its planned end has passed with no word), or
     * after its last departure, plus the turnaround between ships.</li>
     * <li>The vessels booked on it keep their order and are timed again: each
     * starts when the berth is free after the one before and the vessel can
     * be there, its stay clear of maintenance.</li>
     * <li>A vessel that has just sent a new estimate keeps its booking unless
     * it no longer fits, is beyond the booking horizon, or is late beyond the
     * tolerance with another vessel able to be alongside sooner.</li>
     * <li>Vessels whose booking the terminal took away for maintenance are
     * planned again first, so they keep their claim on the berths.</li>
     * <li>A booking moves to another berth that fits its vessel and can take
     * it more than the late tolerance sooner, without delaying anyone booked
     * there: a vessel held up behind an overstay, or one that a berth just
     * come free can take much sooner. The first in line moves first.</li>
     * <li>Vessels with no booking are placed by {@link #plan} in the gaps the
     * bookings leave; one is never placed where it would delay a booking.
     * Each placed within the booking horizon is booked; one due later keeps
     * its place in line and holds no berth.</li>
     * <li>Every vessel whose start moved by the re-issue threshold or more is
     * told its new requested arrival, which cancels its acceptance of the
     * old one.</li>
     * </ol>
     */
    private void replan(List<Berth> berths, Notes notes, VesselVisit changed) {
        allocationRepository.flush();
        if (berths.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        Map<Long, Berth> inService = berths.stream().collect(Collectors.toMap(Berth::getId, Function.identity()));
        Map<Long, List<BerthMaintenance>> maintenance = maintenanceRepository.findEndingAfter(now).stream()
                .collect(Collectors.groupingBy(m -> m.getBerth().getId()));
        Map<Long, Instant> lastLeft = lastDepartures();
        Map<Long, BerthAllocation> alongside = new HashMap<>();
        Map<Long, List<BerthAllocation>> booked = new HashMap<>();
        berths.forEach(berth -> booked.put(berth.getId(), new ArrayList<>()));
        for (BerthAllocation allocation : allocationRepository.findByStatusIn(AllocationStatus.OPEN)) {
            Long berthId = allocation.getBerth().getId();
            if (!inService.containsKey(berthId)) {
                continue; // a berth taken out of service keeps what it holds
            }
            if (allocation.getStatus() == AllocationStatus.ACTIVE) {
                alongside.put(berthId, allocation);
            } else {
                booked.get(berthId).add(allocation);
            }
        }
        Map<Long, Line> starts = new HashMap<>();
        for (Berth berth : berths) {
            BerthAllocation there = alongside.get(berth.getId());
            starts.put(berth.getId(), there != null
                    ? new Line(later(now, expectedEnd(there, now).plus(turnaround)), true)
                    : new Line(freeAfter(lastLeft.get(berth.getId()), now), false));
            booked.get(berth.getId()).sort(Comparator.comparing(BerthAllocation::getPlannedStart)
                    .thenComparing(BerthAllocation::getId));
        }
        Map<Long, Line> lines = retime(berths, starts, booked, maintenance);

        if (changed != null) {
            Optional<BerthAllocation> own = booked.values().stream().flatMap(List::stream)
                    .filter(a -> a.getVisit().getId().equals(changed.getId())).findFirst();
            if (own.isPresent() && withdraws(changed, own.get(), lines.get(own.get().getBerth().getId()),
                    booked, maintenance, notes, now)) {
                own.get().setStatus(AllocationStatus.CANCELLED);
                booked.get(own.get().getBerth().getId()).remove(own.get());
                allocationRepository.flush();
                lines = retime(berths, starts, booked, maintenance);
            }
        }
        allocationRepository.flush();
        if (!notes.displaced().isEmpty()) {
            List<VesselVisit> displaced = visitRepository.findAllById(notes.displaced()).stream()
                    .sorted(Comparator.comparing(VesselVisit::getId)).toList();
            place(displaced, berths, lines, booked, maintenance, notes, now);
            lines = retime(berths, starts, booked, maintenance);
        }
        lines = moveToSoonerBerths(berths, starts, booked, lines, maintenance, notes);

        // the vessels with no booking, placed in the gaps
        allocationRepository.flush();
        List<Long> queue = visitRepository.findWaitingIds(VisitPhase.CLOSED, AllocationStatus.OPEN);
        Map<Long, VesselVisit> byId = visitRepository.findAllById(queue).stream()
                .collect(Collectors.toMap(VesselVisit::getId, Function.identity()));
        place(queue.stream().map(byId::get).toList(), berths, lines, booked, maintenance, notes, now);
        lines = retime(berths, starts, booked, maintenance);

        // tell every booked vessel whose time moved
        Map<Long, String> berthNotes = new HashMap<>(notes.byBerth());
        for (Berth berth : berths) {
            Line berthLine = lines.get(berth.getId());
            for (BerthAllocation booking : booked.get(berth.getId())) {
                VesselVisit visit = booking.getVisit();
                Instant start = booking.getPlannedStart();
                if (!moved(visit.getRtaBerth(), start)) {
                    continue;
                }
                String remark = notes.byVisit().get(visit.getId());
                if (remark == null) {
                    remark = berthNotes.remove(berth.getId());
                }
                Instant behind = behindOf(berthLine, start, turnaround);
                if (remark == null && behind != null) {
                    remark = "Berth expected free " + behind;
                }
                request(visit, start, berth, remark);
            }
        }
    }

    /**
     * Place vessels that hold no booking, in line order, by {@link #plan}: one
     * that can start within the booking horizon is booked; one due later is
     * told when to come and holds no berth.
     */
    private void place(List<VesselVisit> line, List<Berth> berths, Map<Long, Line> lines,
                       Map<Long, List<BerthAllocation>> booked, Map<Long, List<BerthMaintenance>> maintenance,
                       Notes notes, Instant now) {
        Instant horizon = now.plus(bookingHorizon);
        for (Slot slot : plan(line, berths, lines, maintenance, lateTolerance, turnaround)) {
            VesselVisit visit = slot.visit();
            if (slot.start().isAfter(horizon)) {
                String why = "A berth is given within " + span(bookingHorizon) + " of arrival";
                String note = notes.byVisit().get(visit.getId());
                request(visit, slot.start(), null, note == null ? why : note + ". " + why);
                continue;
            }
            BerthAllocation allocation = new BerthAllocation(visit, slot.berth(), slot.start());
            allocation.setPlannedEnd(slot.start().plus(stay(visit)));
            allocationRepository.save(allocation);
            visit.setTerminal(slot.berth().getQuay().getTerminal());
            booked.get(slot.berth().getId()).add(allocation);
            booked.get(slot.berth().getId()).sort(Comparator.comparing(BerthAllocation::getPlannedStart));
        }
    }

    /**
     * Each berth's line with its bookings timed again in their order: a
     * booking starts when the berth is free after the one before it and the
     * vessel can be there, with its stay clear of maintenance.
     */
    private Map<Long, Line> retime(List<Berth> berths, Map<Long, Line> starts,
                                   Map<Long, List<BerthAllocation>> booked,
                                   Map<Long, List<BerthMaintenance>> maintenance) {
        Map<Long, Line> lines = new HashMap<>();
        for (Berth berth : berths) {
            Line line = starts.get(berth.getId()).copy();
            List<BerthMaintenance> windows = maintenance.getOrDefault(berth.getId(), List.of());
            Instant free = line.from;
            for (BerthAllocation booking : booked.get(berth.getId())) {
                VesselVisit visit = booking.getVisit();
                Duration stay = stay(visit);
                Instant start = earliestStart(windows, later(free, visit.getEtaBerth()), stay);
                booking.setPlannedStart(start);
                booking.setPlannedEnd(start.plus(stay));
                free = start.plus(stay).plus(turnaround);
                line.take(new Taken(start, free));
            }
            lines.put(berth.getId(), line);
        }
        return lines;
    }

    /**
     * Whether a booked vessel that has sent a new estimate gives its booking
     * up, with the reason in the notes: it no longer fits the berth, its start
     * is beyond the booking horizon, or it is more than the tolerance after
     * its agreed arrival and another vessel can be alongside on that berth
     * sooner. An estimate sent again unchanged keeps the booking.
     */
    private boolean withdraws(VesselVisit visit, BerthAllocation booking, Line line,
                              Map<Long, List<BerthAllocation>> booked,
                              Map<Long, List<BerthMaintenance>> maintenance, Notes notes, Instant now) {
        Berth berth = booking.getBerth();
        if (!BerthFit.fits(berth, visit)) {
            notes.byVisit().put(visit.getId(), "Berth " + berth.getCode() + " withdrawn: the vessel does not fit it");
            return true;
        }
        Instant start = booking.getPlannedStart();
        if (start.isAfter(now.plus(bookingHorizon))) {
            notes.byVisit().put(visit.getId(), "Berth " + berth.getCode() + " withdrawn: the new estimate is more than "
                    + span(bookingHorizon) + " away");
            notes.byBerth().put(berth.getId(), "Given up by " + visit.getVessel().getCode()
                    + ", whose new estimate is more than " + span(bookingHorizon) + " away");
            return true;
        }
        Instant agreed = visit.getPtaBerth() != null ? visit.getPtaBerth() : visit.getRtaBerth();
        if (agreed != null && Duration.between(agreed, start).compareTo(lateTolerance) > 0
                && anotherGoesFirst(berth, booking, line, booked,
                maintenance.getOrDefault(berth.getId(), List.of()))) {
            String late = describe(Duration.between(agreed, start));
            notes.byVisit().put(visit.getId(), "Berth " + berth.getCode() + " withdrawn: this estimate is " + late
                    + " after the agreed arrival, and another vessel can be alongside sooner");
            notes.byBerth().put(berth.getId(), "Given up by " + visit.getVessel().getCode() + ", now expected "
                    + late + " after its agreed arrival");
            return true;
        }
        return false;
    }

    /**
     * Whether the rule, run for the booked vessel's place on its berth, gives
     * that place to another vessel: the place goes to the first in line that
     * can start there within the tolerance of the soonest start. The others
     * considered are the vessels booked after it on that berth, the vessels
     * with no booking that fit it, and vessels booked on other berths that
     * could start there more than the tolerance sooner than they do now.
     */
    private boolean anotherGoesFirst(Berth berth, BerthAllocation booking, Line line,
                                     Map<Long, List<BerthAllocation>> booked, List<BerthMaintenance> windows) {
        VesselVisit late = booking.getVisit();
        // the berth is free for this place when the booking before it, or the vessel alongside, is done
        Instant free = line.from;
        for (Taken taken : line.taken) {
            if (taken.from().isBefore(booking.getPlannedStart())) {
                free = later(free, taken.to());
            }
        }
        Instant from = free;
        Function<VesselVisit, Instant> startHere = v -> earliestStart(windows, later(from, v.getEtaBerth()), stay(v));
        Map<Long, Instant> startOf = new HashMap<>();
        startOf.put(late.getId(), startHere.apply(late));
        booked.forEach((berthId, bookings) -> bookings.forEach(other -> {
            VesselVisit visit = other.getVisit();
            if (visit.getId().equals(late.getId()) || !BerthFit.fits(berth, visit)) {
                return;
            }
            Instant here = startHere.apply(visit);
            boolean behindOnThisBerth = berthId.equals(berth.getId())
                    && other.getPlannedStart().isAfter(booking.getPlannedStart());
            if (behindOnThisBerth || here.plus(lateTolerance).isBefore(other.getPlannedStart())) {
                startOf.put(visit.getId(), here);
            }
        }));
        visitRepository.findAllById(visitRepository.findWaitingIds(VisitPhase.CLOSED, AllocationStatus.OPEN))
                .stream().filter(v -> BerthFit.fits(berth, v))
                .forEach(v -> startOf.put(v.getId(), startHere.apply(v)));
        if (startOf.size() == 1) {
            return false;
        }
        Instant limit = startOf.values().stream().min(Comparator.naturalOrder()).orElseThrow().plus(lateTolerance);
        Long first = startOf.entrySet().stream()
                .filter(entry -> !entry.getValue().isAfter(limit))
                .map(Map.Entry::getKey)
                .min(Comparator.naturalOrder())
                .orElseThrow();
        return !first.equals(late.getId());
    }

    /**
     * Move bookings to a berth that can take them much sooner: a booking moves
     * to another berth that fits its vessel when that berth can take it more
     * than the late tolerance sooner, in a gap that delays no one booked
     * there. Of several bookings that could move, the first in line moves
     * first, to the berth where it starts soonest. Repeated until nothing
     * moves; returns the lines timed again.
     */
    private Map<Long, Line> moveToSoonerBerths(List<Berth> berths, Map<Long, Line> starts,
                                               Map<Long, List<BerthAllocation>> booked, Map<Long, Line> lines,
                                               Map<Long, List<BerthMaintenance>> maintenance, Notes notes) {
        int bookings = booked.values().stream().mapToInt(List::size).sum();
        for (int round = 0; round < bookings; round++) {
            BerthAllocation move = null;
            Berth target = null;
            Instant targetStart = null;
            for (Berth berth : berths) {
                for (BerthAllocation booking : booked.get(berth.getId())) {
                    VesselVisit visit = booking.getVisit();
                    if (move != null && move.getVisit().getId() < visit.getId()) {
                        continue; // one earlier in line already moves
                    }
                    Berth best = null;
                    Instant bestStart = null;
                    for (Berth other : berths) {
                        if (other.getId().equals(berth.getId()) || !BerthFit.fits(other, visit)) {
                            continue;
                        }
                        Line line = lines.get(other.getId());
                        Instant at = earliestGap(line, maintenance.getOrDefault(other.getId(), List.of()),
                                later(line.from, visit.getEtaBerth()), stay(visit), turnaround);
                        if (at.plus(lateTolerance).isBefore(booking.getPlannedStart())
                                && (bestStart == null || at.isBefore(bestStart))) {
                            best = other;
                            bestStart = at;
                        }
                    }
                    if (best != null) {
                        move = booking;
                        target = best;
                        targetStart = bestStart;
                    }
                }
            }
            if (move == null) {
                return lines;
            }
            Berth from = move.getBerth();
            VesselVisit visit = move.getVisit();
            Duration sooner = Duration.between(targetStart, move.getPlannedStart());
            move.setStatus(AllocationStatus.CANCELLED);
            booked.get(from.getId()).remove(move);
            allocationRepository.flush();
            BerthAllocation moved = new BerthAllocation(visit, target, targetStart);
            moved.setPlannedEnd(targetStart.plus(stay(visit)));
            allocationRepository.save(moved);
            visit.setTerminal(target.getQuay().getTerminal());
            booked.get(target.getId()).add(moved);
            booked.get(target.getId()).sort(Comparator.comparing(BerthAllocation::getPlannedStart));
            // a berth given up by a late vessel says so; otherwise the move explains itself
            String givenUp = notes.byBerth().remove(target.getId());
            notes.byVisit().put(visit.getId(), givenUp != null ? givenUp : "Moved from " + from.getCode() + " to "
                    + target.getCode() + ", which can take it " + describe(sooner) + " sooner");
            lines = retime(berths, starts, booked, maintenance);
        }
        return lines;
    }

    /**
     * Place vessels with no booking on the berths. Repeatedly: find the berth
     * where some of them can start soonest; it goes to the first vessel in line
     * that fits it and can start there within the tolerance of that soonest
     * time, so a vessel that is late is skipped but keeps its place; that
     * vessel then takes whichever berth it can start on soonest, and of berths
     * where it could start equally soon, the one where the vessel before it
     * leaves first.
     *
     * A vessel can start on a berth once the berth is free and the vessel is
     * there, with its stay clear of maintenance and its stay plus the
     * turnaround clear of every window already booked, so it never delays a
     * booking. The berths' lines are not changed; the plan works on copies.
     */
    static List<Slot> plan(List<VesselVisit> line, List<Berth> berths, Map<Long, Line> lines,
                           Map<Long, List<BerthMaintenance>> maintenance, Duration tolerance,
                           Duration turnaround) {
        List<VesselVisit> waiting = new ArrayList<>(line);
        Map<Long, Line> plan = new HashMap<>();
        lines.forEach((id, berthLine) -> plan.put(id, berthLine.copy()));
        List<Slot> slots = new ArrayList<>();
        while (!waiting.isEmpty()) {
            Berth berth = null;
            Instant soonest = null;
            for (Berth candidate : berths) {
                for (VesselVisit visit : waiting) {
                    if (BerthFit.fits(candidate, visit)) {
                        Instant start = startOn(candidate, visit, plan, maintenance, turnaround);
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
                    .filter(v -> BerthFit.fits(first, v)
                            && !startOn(first, v, plan, maintenance, turnaround).isAfter(limit))
                    .findFirst().orElseThrow();
            Berth best = berth;
            Instant start = startOn(berth, chosen, plan, maintenance, turnaround);
            for (Berth candidate : berths) {
                if (BerthFit.fits(candidate, chosen)) {
                    Instant at = startOn(candidate, chosen, plan, maintenance, turnaround);
                    // on a tie, the berth where the vessel before leaves first: it has the most time in hand
                    if (at.isBefore(start) || (at.equals(start)
                            && freeBefore(plan.get(candidate.getId()), at)
                            .isBefore(freeBefore(plan.get(best.getId()), start)))) {
                        start = at;
                        best = candidate;
                    }
                }
            }
            Line bestLine = plan.get(best.getId());
            slots.add(new Slot(chosen, best, start));
            bestLine.take(new Taken(start, start.plus(stay(chosen)).plus(turnaround)));
            waiting.remove(chosen);
        }
        return slots;
    }

    private static Instant startOn(Berth berth, VesselVisit visit, Map<Long, Line> lines,
                                   Map<Long, List<BerthMaintenance>> maintenance, Duration turnaround) {
        Line line = lines.get(berth.getId());
        return earliestGap(line, maintenance.getOrDefault(berth.getId(), List.of()),
                later(line.from, visit.getEtaBerth()), stay(visit), turnaround);
    }

    /**
     * The first moment from {@code notBefore} at which a stay of this length
     * fits on the berth: clear of every maintenance window, and with the stay
     * and the turnaround after it clear of every window already booked.
     */
    static Instant earliestGap(Line line, List<BerthMaintenance> windows, Instant notBefore, Duration stay,
                               Duration turnaround) {
        Instant start = notBefore;
        while (true) {
            Instant clear = earliestStart(windows, start, stay);
            Instant end = clear.plus(stay).plus(turnaround);
            Optional<Taken> inTheWay = line.taken.stream().filter(t -> t.overlaps(clear, end)).findFirst();
            if (inTheWay.isEmpty()) {
                return clear;
            }
            start = inTheWay.get().to();
        }
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

    /** When the berth is free before a stay starting at {@code start}: after the last window that ends by then. */
    private static Instant freeBefore(Line line, Instant start) {
        Instant free = line.from;
        for (Taken taken : line.taken) {
            if (!taken.to().isAfter(start)) {
                free = later(free, taken.to());
            }
        }
        return free;
    }

    /**
     * When the vessel before a stay starting at {@code start} is expected to
     * leave, if the start is the moment the berth comes free after it (its
     * turnaround over); otherwise null.
     */
    static Instant behindOf(Line line, Instant start, Duration turnaround) {
        if (line.fromVessel && line.from.equals(start)) {
            return start.minus(turnaround);
        }
        return line.taken.stream().anyMatch(t -> t.to().equals(start)) ? start.minus(turnaround) : null;
    }

    /** Whether a new time differs from the one last told by the re-issue threshold or more. */
    private boolean moved(Instant told, Instant time) {
        return told == null || Duration.between(told, time).abs().compareTo(reissueThreshold) >= 0;
    }

    /**
     * Record the terminal's requested arrival time, if it moved by the
     * re-issue threshold or more since the last one told. Each change is a new
     * entry in the ledger, and a new request cancels the vessel's confirmation
     * of the old one, so small changes do not flood the vessel or undo its
     * acceptance.
     */
    private void request(VesselVisit visit, Instant time, Berth berth, String remarks) {
        if (!moved(visit.getRtaBerth(), time)) {
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

    /**
     * When a booking's vessel is expected to leave: its planned end, unless the
     * vessel is alongside and that has passed with no news, in which case it is
     * taken to be still there and to leave one overstay step from now.
     */
    private Instant expectedEnd(BerthAllocation booking, Instant now) {
        boolean overstaying = booking.getStatus() == AllocationStatus.ACTIVE && !booking.getPlannedEnd().isAfter(now);
        return overstaying ? now.plus(overstayStep) : booking.getPlannedEnd();
    }

    /** A berth with no vessel alongside is free once the turnaround after its last vessel has passed; now if none has left. */
    private Instant freeAfter(Instant lastDeparture, Instant now) {
        return lastDeparture == null ? now : later(now, lastDeparture.plus(turnaround));
    }

    /** When the last vessel left each berth, by berth id. */
    private Map<Long, Instant> lastDepartures() {
        Map<Long, Instant> lastLeft = new HashMap<>();
        for (Object[] row : allocationRepository.findLastDepartures(AllocationStatus.COMPLETED)) {
            lastLeft.put((Long) row[0], (Instant) row[1]);
        }
        return lastLeft;
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

    /** A setting's length of time for a remark: "7 days" when it is whole days, otherwise as {@link #describe}. */
    static String span(Duration duration) {
        long hours = duration.toHours();
        if (hours > 0 && hours % 24 == 0 && duration.toMinutesPart() == 0) {
            long days = hours / 24;
            return days == 1 ? "1 day" : days + " days";
        }
        return describe(duration);
    }

    private static Assignment berthed(Berth berth) {
        return new Assignment("BERTHED", berth.getCode(), berth.getName(), null);
    }
}
