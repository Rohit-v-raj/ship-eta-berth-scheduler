package com.port.scheduler.model.view;

import com.port.scheduler.entity.vessel.VesselType;
import com.port.scheduler.entity.visit.AllocationStatus;
import com.port.scheduler.entity.visit.EventSource;
import com.port.scheduler.entity.visit.VisitPhase;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One vessel visit as the console shows it: where the visit stands and the
 * arrival times agreed so far. A visit has a {@code berth} or a
 * {@code queueNumber}, never both.
 */
public record VisitView(
        String visitRef,
        VesselView vessel,
        VisitPhase phase,
        String terminalCode,
        /** The berth window the visit holds or held; null while it waits. */
        BerthWindow berth,
        /** Place in the virtual queue, from 1; null once a berth is allocated. */
        Integer queueNumber,
        Instant etaBerth,
        Instant rtaBerth,
        Instant ptaBerth,
        Instant ataBerth,
        Instant atdBerth,
        Integer plannedServiceMinutes,
        /** Where the vessel last said it was; null if its estimates have carried no position. */
        PositionReport lastReport,
        /** When the terminal issued the requested arrival that now stands; null if it has requested none. */
        Instant requestedAt,
        /** When the vessel's answer to that request is due; null once it has accepted or arrived, or the call is over. */
        Instant answerDueAt,
        /** Who ended a call that did not take place: VESSEL (omitted) or TERMINAL (cancelled). Null otherwise. */
        EventSource cancelledBy,
        /** A note kept with the visit, such as why the call was cancelled; null if there is none. */
        String remarks,
        /**
         * Since when the vessel has owed a fresh estimate: a milestone before its
         * arrival (72, 48 or 24 hours) passed with no estimate since. Null when none is owed.
         */
        Instant estimateDueSince,
        /** The next milestone by which a fresh estimate is expected; null when none is left. */
        Instant nextEstimateBy,
        /** For a vessel holding a berth it has not reached: its agreed arrival, once that has passed. */
        Instant notArrivedSince,
        /** The draft declared for this call, in metres; null if none was given. */
        BigDecimal arrivalDraftMeters
) {

    /** A vessel's identity and size. A size is null when the terminal has not been told it. */
    public record VesselView(String code, String name, String imoNumber, String mmsi, VesselType type,
                             BigDecimal loaMeters, BigDecimal beamMeters) {}

    public record BerthWindow(String berthCode, String berthName, AllocationStatus status,
                              Instant plannedStart, Instant plannedEnd, Instant actualStart, Instant actualEnd) {}

    /**
     * A position the vessel gave with an estimated arrival. Latitude and
     * longitude, or the distance, may be missing: each is optional in the
     * DCSA message.
     */
    public record PositionReport(Instant reportedAt, Double lat, Double lon, Double distanceNm,
                                 Instant estimatedArrival) {}
}
