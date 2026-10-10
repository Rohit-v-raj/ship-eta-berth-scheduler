package com.port.scheduler.entity.visit;

import java.util.EnumSet;
import java.util.Set;

/** Where a vessel visit is in its life, from first notice to departure. */
public enum VisitPhase {
    ANNOUNCED,
    INBOUND,
    ARRIVED,
    BERTHED,
    WORKING,
    COMPLETED,
    DEPARTED,
    CANCELLED;

    /** Phases in which the call is over. Every other phase is an open visit. */
    public static final Set<VisitPhase> CLOSED = EnumSet.of(DEPARTED, CANCELLED);
}
