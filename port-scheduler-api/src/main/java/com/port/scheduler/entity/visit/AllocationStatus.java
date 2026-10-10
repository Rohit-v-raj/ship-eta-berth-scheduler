package com.port.scheduler.entity.visit;

import java.util.EnumSet;
import java.util.Set;

public enum AllocationStatus {
    PLANNED,
    ACTIVE,
    COMPLETED,
    CANCELLED;

    /** Statuses that hold a berth. A berth with an open allocation is occupied. */
    public static final Set<AllocationStatus> OPEN = EnumSet.of(PLANNED, ACTIVE);
}
