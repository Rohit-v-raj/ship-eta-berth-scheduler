package com.port.scheduler.model;

import com.port.scheduler.entity.vessel.VesselType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * What a vessel operator asks the ETA calculator: the vessel's current AIS
 * state and where it is going. Mirrors the inputs the FastAPI model service
 * expects.
 */
public record EtaQuery(
        @NotBlank String vesselId,
        double lat,
        double lon,
        @PositiveOrZero double sog,
        @Min(0) @Max(360) double cog,
        double destLat,
        double destLon,
        /** Optional. Sets how slowly the vessel may be advised to sail; otherwise the type on record is used. */
        VesselType vesselType
) {}
