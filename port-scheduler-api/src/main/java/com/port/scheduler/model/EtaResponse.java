package com.port.scheduler.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response parsed from the FastAPI {@code /predict} endpoint.
 */
public record EtaResponse(
        @JsonProperty("eta_minutes") double etaMinutes,
        @JsonProperty("eta_hours") double etaHours,
        @JsonProperty("distance_to_dest_nm") double distanceToDestNm,
        @JsonProperty("naive_eta_min") Double naiveEtaMin
) {}
