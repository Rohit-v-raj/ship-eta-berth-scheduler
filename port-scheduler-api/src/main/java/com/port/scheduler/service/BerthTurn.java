package com.port.scheduler.service;

import com.port.scheduler.entity.visit.AllocationStatus;
import com.port.scheduler.entity.visit.BerthAllocation;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/**
 * Whose turn it is at a berth. A vessel booked on a berth comes alongside at
 * its turn: when no other vessel lies there and no booking ahead of it in the
 * berth's line is still to come. A vessel that is early, with others booked
 * ahead of it, comes in only if its stay and the turnaround after it end
 * before the next booking starts, so the vessel booked first is never delayed.
 * An early arrival gains nothing over a vessel booked ahead: the port practice
 * where berthing windows are booked (Napier Port: an early vessel "can only
 * occupy the berth if it is vacant and it doesn't impact on any other window
 * arrangement").
 */
final class BerthTurn {

    /** The order of a berth's line, as the plan keeps it: planned start, then the booking made first. */
    private static final Comparator<BerthAllocation> LINE = Comparator.comparing(BerthAllocation::getPlannedStart)
            .thenComparing(BerthAllocation::getId);

    private BerthTurn() {}

    /**
     * The allocation an arrival of {@code own}'s vessel at {@code at} would
     * come before: another vessel alongside the berth, or else the first
     * booking ahead of {@code own} whose start the stay and turnaround would
     * run into. Empty when the vessel may come alongside.
     *
     * @param onBerth the open allocations on {@code own}'s berth; {@code own} may be among them
     */
    static Optional<BerthAllocation> ahead(BerthAllocation own, Collection<BerthAllocation> onBerth, Instant at,
                                           Duration stay, Duration turnaround) {
        Optional<BerthAllocation> alongside = onBerth.stream()
                .filter(other -> !other.getId().equals(own.getId()))
                .filter(other -> other.getStatus() == AllocationStatus.ACTIVE)
                .findFirst();
        if (alongside.isPresent()) {
            return alongside;
        }
        Instant gone = at.plus(stay).plus(turnaround);
        return onBerth.stream()
                .filter(other -> !other.getId().equals(own.getId()))
                .filter(other -> other.getStatus() == AllocationStatus.PLANNED)
                .filter(other -> LINE.compare(other, own) < 0)
                .min(LINE)
                .filter(next -> gone.isAfter(next.getPlannedStart()));
    }
}
