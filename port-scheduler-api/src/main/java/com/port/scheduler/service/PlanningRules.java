package com.port.scheduler.service;

import com.port.scheduler.entity.vessel.VesselType;

import java.time.Duration;

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
}
