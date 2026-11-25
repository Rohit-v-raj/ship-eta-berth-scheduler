package com.port.scheduler.entity.visit;

import lombok.Getter;

/**
 * The point in a port call that a timestamp refers to. Each value carries the
 * four codes the DCSA Port Call standard uses for it: service type, event
 * type, port call phase and facility type.
 */
@Getter
public enum Milestone {
    ARRIVAL_PILOT_STATION("PILOT_BOARDING_PLACE", "ARRI", "INBD", "PBPL"),
    /** The vessel anchors to wait for its berth. The standard gives the anchorage no port call phase. */
    ANCHORAGE_START("ANCHORAGE", "STRT", null, "ANCH"),
    ANCHORAGE_COMPLETE("ANCHORAGE", "CMPL", null, "ANCH"),
    ARRIVAL_BERTH("BERTH", "ARRI", "INBD", "BRTH"),
    CARGO_OPS_START("CARGO_OPERATIONS", "STRT", "ALGS", "BRTH"),
    CARGO_OPS_COMPLETE("CARGO_OPERATIONS", "CMPL", "ALGS", "BRTH"),
    DEPARTURE_BERTH("BERTH", "DEPA", "OUTB", "BRTH");

    private final String serviceTypeCode;
    private final String eventTypeCode;
    private final String phaseTypeCode;
    private final String facilityTypeCode;

    Milestone(String serviceTypeCode, String eventTypeCode, String phaseTypeCode, String facilityTypeCode) {
        this.serviceTypeCode = serviceTypeCode;
        this.eventTypeCode = eventTypeCode;
        this.phaseTypeCode = phaseTypeCode;
        this.facilityTypeCode = facilityTypeCode;
    }
}
