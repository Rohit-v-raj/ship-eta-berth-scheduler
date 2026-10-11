package com.port.scheduler.model;

import java.time.Instant;

/** The outcome of a vessel operator's decision: "CONFIRMED" with the planned arrival, or "OMITTED". */
public record VisitDecision(String vesselId, String status, Instant plannedArrival) {}
