package com.port.scheduler.model;

import java.time.Instant;

/**
 * The calculator's answer for a vessel's present position. Asking changes
 * nothing.
 *
 * The first part is the ETA model's estimate, with plain distance over speed
 * beside it. The second part is filled only when the vessel has a call open
 * and the terminal has requested an arrival: the speed that would bring the
 * vessel in at that time instead of early.
 */
public record EtaEstimate(
        String vesselId,
        double etaMinutes,
        Instant estimatedArrival,
        double distanceNm,
        /** Distance over present speed, in minutes; null if the model service did not give it. */
        Double naiveEtaMinutes,
        /** The terminal's requested arrival for the vessel's open call; null if it has none. */
        Instant requestedArrival,
        /** Advisory only; present when holding the present speed would mean waiting for the berth. */
        Double advisedSpeedKnots
) {}
