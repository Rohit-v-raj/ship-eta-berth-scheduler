package com.port.scheduler.service;

import com.port.scheduler.entity.topology.Berth;
import com.port.scheduler.entity.vessel.Vessel;
import com.port.scheduler.entity.visit.VesselVisit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Whether a vessel fits a berth: its length overall, beam and draft each
 * within the berth's limits. A size the terminal has not been told, or a limit
 * the berth does not state, passes: nothing can be checked against it.
 */
final class BerthFit {

    private BerthFit() {}

    static boolean fits(Berth berth, VesselVisit visit) {
        return fits(berth, visit.getVessel(), visit.getArrivalDraftMeters());
    }

    /** {@code draft} is the one declared for the call; without it the vessel's maximum draft is used. */
    static boolean fits(Berth berth, Vessel vessel, BigDecimal draft) {
        return within(vessel.getLoaMeters(), berth.getMaxLoaMeters())
                && within(vessel.getBeamMeters(), berth.getMaxBeamMeters())
                && within(draft(vessel, draft), berth.getMaxDraftMeters());
    }

    private static BigDecimal draft(Vessel vessel, BigDecimal declared) {
        return declared != null ? declared : vessel.getMaxDraftMeters();
    }

    /**
     * Why no berth takes the vessel, for a refusal: its known sizes and the
     * largest each berth allows, for example "length 250 m; the largest berth
     * here takes length 190 m, beam 32 m, draft 10.5 m".
     */
    static String whyNone(Vessel vessel, BigDecimal draft, Collection<Berth> berths) {
        return describe(vessel.getLoaMeters(), vessel.getBeamMeters(), draft(vessel, draft))
                + "; the largest berth here takes "
                + describe(largest(berths, Berth::getMaxLoaMeters), largest(berths, Berth::getMaxBeamMeters),
                        largest(berths, Berth::getMaxDraftMeters));
    }

    private static boolean within(BigDecimal size, BigDecimal limit) {
        return size == null || limit == null || size.compareTo(limit) <= 0;
    }

    private static BigDecimal largest(Collection<Berth> berths, Function<Berth, BigDecimal> limit) {
        return berths.stream().map(limit).filter(Objects::nonNull).max(BigDecimal::compareTo).orElse(null);
    }

    private static String describe(BigDecimal length, BigDecimal beam, BigDecimal draft) {
        List<String> parts = new ArrayList<>();
        if (length != null) parts.add("length " + length.stripTrailingZeros().toPlainString() + " m");
        if (beam != null) parts.add("beam " + beam.stripTrailingZeros().toPlainString() + " m");
        if (draft != null) parts.add("draft " + draft.stripTrailingZeros().toPlainString() + " m");
        return parts.isEmpty() ? "no stated size" : String.join(", ", parts);
    }
}
