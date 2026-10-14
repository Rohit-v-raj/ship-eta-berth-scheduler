package com.port.scheduler.service;

import com.port.scheduler.entity.vessel.VesselType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanningRulesTest {

    @Test
    void stayComesFromThePublishedMedianForTheType() {
        assertThat(PlanningRules.typicalStay(VesselType.CONTAINER)).isEqualTo(Duration.ofMinutes(1008));    // 0.70 days
        assertThat(PlanningRules.typicalStay(VesselType.BULK_CARRIER)).isEqualTo(Duration.ofMinutes(2952)); // 2.05 days
        assertThat(PlanningRules.typicalStay(VesselType.TANKER)).isEqualTo(Duration.ofMinutes(1354));       // 0.94 days
        assertThat(PlanningRules.typicalStay(VesselType.FISHING)).isEqualTo(Duration.ofMinutes(1397));      // 0.97 days
    }

    @Test
    void adviceIsDistanceOverTimeAvailable() {
        // 30 nm to go, berth free in 4 hours, sailing at 12 knots
        assertThat(PlanningRules.advisedSpeedKnots(30, Duration.ofHours(4), 12, 5)).isEqualTo(7.5);
    }

    @Test
    void adviceNeverGoesBelowTheEconomicalSpeed() {
        assertThat(PlanningRules.advisedSpeedKnots(30, Duration.ofHours(10), 12, 10)).isEqualTo(10.0);
    }

    @Test
    void thereIsNoAdviceWhenSlowingDownWouldNotHelp() {
        assertThat(PlanningRules.advisedSpeedKnots(30, Duration.ofHours(2), 12, 5)).isNull();   // would need 15 knots
        assertThat(PlanningRules.advisedSpeedKnots(30, Duration.ofHours(10), 9, 10)).isNull();  // already below economical
        assertThat(PlanningRules.advisedSpeedKnots(30, Duration.ZERO, 12, 5)).isNull();
    }

    private static final Instant ARRIVAL = Instant.parse("2026-10-20T12:00:00Z");
    private static final List<Duration> MILESTONES =
            List.of(Duration.ofHours(72), Duration.ofHours(48), Duration.ofHours(24));

    @Test
    void anEstimateIsDueOnceAMilestonePassesWithNoneSince() {
        Instant now = ARRIVAL.minus(Duration.ofHours(30));   // the 72 and 48 hour milestones have passed

        assertThat(PlanningRules.estimateDueSince(ARRIVAL, ARRIVAL.minus(Duration.ofHours(60)), now, MILESTONES))
                .isEqualTo(ARRIVAL.minus(Duration.ofHours(48)));
        assertThat(PlanningRules.estimateDueSince(ARRIVAL, ARRIVAL.minus(Duration.ofHours(40)), now, MILESTONES))
                .isNull();
        assertThat(PlanningRules.nextEstimateBy(ARRIVAL, now, MILESTONES)).isEqualTo(ARRIVAL.minus(Duration.ofHours(24)));
    }

    @Test
    void noEstimateIsDueBeforeTheFirstMilestoneOrAfterTheLast() {
        assertThat(PlanningRules.estimateDueSince(ARRIVAL, null, ARRIVAL.minus(Duration.ofHours(80)), MILESTONES))
                .isNull();
        assertThat(PlanningRules.nextEstimateBy(ARRIVAL, ARRIVAL.minus(Duration.ofHours(10)), MILESTONES)).isNull();
        assertThat(PlanningRules.estimateDueSince(null, null, ARRIVAL, MILESTONES)).isNull();
    }
}
