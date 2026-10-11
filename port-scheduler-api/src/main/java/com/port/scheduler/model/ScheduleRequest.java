package com.port.scheduler.model;

import com.port.scheduler.entity.vessel.VesselType;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Incoming request: a vessel's current AIS state plus its destination berth.
 * Mirrors the inputs the FastAPI model service expects.
 */
public record ScheduleRequest(
        @NotBlank String vesselId,
        double lat,
        double lon,
        @PositiveOrZero double sog,
        @Min(0) @Max(360) double cog,
        double destLat,
        double destLon,
        /** Optional. Sets how long the vessel is expected to stay and how slowly it may be advised to sail. */
        VesselType vesselType
) {}
