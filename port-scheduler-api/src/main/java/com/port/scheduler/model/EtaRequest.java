package com.port.scheduler.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body sent to the FastAPI {@code /predict} endpoint. The JSON field names use
 * snake_case to match the Python service's Pydantic model.
 */
public record EtaRequest(
        double lat,
        double lon,
        double sog,
        double cog,
        @JsonProperty("dest_lat") double destLat,
        @JsonProperty("dest_lon") double destLon
) {}
