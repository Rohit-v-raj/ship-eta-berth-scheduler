package com.port.scheduler.model;

import java.time.Instant;

/** The ETA model's answer for a vessel's present position. Asking for it changes nothing. */
public record EtaEstimate(String vesselId, double etaMinutes, Instant estimatedArrival, double distanceNm) {}
