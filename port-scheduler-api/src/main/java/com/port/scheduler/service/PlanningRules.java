package com.port.scheduler.service;

import com.port.scheduler.entity.vessel.VesselType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Reference figures and arithmetic for berth planning.
 *
 * The figures are published industry medians, not measurements of this port,
 * and are meant to be replaced by the terminal's own data once it has some.
 */
public final class PlanningRules {

    private static final double MINUTES_PER_DAY = 24 * 60;

    private PlanningRules() {}

    /**
     * How long a vessel of this type typically occupies a berth.
     *
     * Median time in port from UNCTAD, Review of Maritime Transport 2019
     * (2018 port calls): container ships 0.70 days, dry bulk 2.05, liquid bulk
     * 0.94. Types UNCTAD gives no figure for use its all-ships median, 0.97.
     */
    public static Duration typicalStay(VesselType type) {
        double days = switch (type) {
            case CONTAINER -> 0.70;
            case BULK_CARRIER -> 2.05;
            case TANKER -> 0.94;
            default -> 0.97;
        };
        return Duration.ofMinutes(Math.round(days * MINUTES_PER_DAY));
    }

    /**
     * The slowest speed worth advising, in knots.
     *
     * Container ships: 10, the most economical speed in the worked example of
     * the IMO study "Just In Time Arrival: emissions reduction potential in
     * global container shipping" (2022) and the bottom of the 10 to 15 knot
     * range Maersk reports for slow steaming, which it applies to bulk carriers
     * too. Tankers: 8.5, the ballast speed Maersk Tankers adopted. No published
     * figure was found for other types; 5 knots is the same study's threshold
     * for a vessel being under way at all.
     */
    public static double economicalSpeedKnots(VesselType type) {
        return switch (type) {
            case CONTAINER, BULK_CARRIER -> 10.0;
            case TANKER -> 8.5;
            default -> 5.0;
        };
    }

    /**
     * The speed that brings a vessel in at the requested time instead of early:
     * distance over time available, never below the economical speed. Returns
     * null when that is no slower than the vessel's present speed, because
     * there is then nothing to advise.
     */
    public static Double advisedSpeedKnots(double distanceNm, Duration untilRequested,
                                           double currentSpeedKnots, double economicalSpeedKnots) {
        double hours = untilRequested.toSeconds() / 3600.0;
        if (hours <= 0) {
            return null;
        }
        double speed = Math.max(distanceNm / hours, economicalSpeedKnots);
        return speed < currentSpeedKnots ? Math.round(speed * 10) / 10.0 : null;
    }

    /**
     * When a vessel owes the terminal a fresh estimate: the latest of the
     * milestones before its arrival (72, 48 and 24 hours, say) that has passed
     * with no estimate received since. Null when none is owed. Ports commonly
     * ask for an estimate at such milestones; DCSA's messages have no way for
     * the terminal to ask, so this only says when one is expected.
     */
    public static Instant estimateDueSince(Instant arrival, Instant lastEstimate, Instant now,
                                           List<Duration> milestones) {
        if (arrival == null) {
            return null;
        }
        Instant due = null;
        for (Duration milestone : milestones) {
            Instant at = arrival.minus(milestone);
            if (!at.isAfter(now) && (due == null || at.isAfter(due))) {
                due = at;
            }
        }
        return due != null && (lastEstimate == null || lastEstimate.isBefore(due)) ? due : null;
    }

    /** The next milestone before the arrival that is still ahead; null when none is left. */
    public static Instant nextEstimateBy(Instant arrival, Instant now, List<Duration> milestones) {
        if (arrival == null) {
            return null;
        }
        Instant next = null;
        for (Duration milestone : milestones) {
            Instant at = arrival.minus(milestone);
            if (at.isAfter(now) && (next == null || at.isBefore(next))) {
                next = at;
            }
        }
        return next;
    }

    /**
     * Speed advice for a vessel asked to arrive at {@code requested}, from the
     * position it reported at {@code reportedAt}. There is advice only when the
     * requested time is later than the vessel would arrive anyway: that is
     * when slowing down replaces waiting.
     */
    public static Double advisedSpeedKnots(VesselType type, double distanceNm, double currentSpeedKnots,
                                           Instant reportedAt, Instant predictedArrival, Instant requested) {
        if (requested == null || !requested.isAfter(predictedArrival)) {
            return null;
        }
        return advisedSpeedKnots(distanceNm, Duration.between(reportedAt, requested), currentSpeedKnots,
                economicalSpeedKnots(type));
    }
}
