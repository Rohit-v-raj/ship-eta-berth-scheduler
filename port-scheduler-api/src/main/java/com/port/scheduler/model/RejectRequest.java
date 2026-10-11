package com.port.scheduler.model;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * A vessel operator declining the terminal's requested arrival. Declining means
 * offering the time the vessel can make; to skip the call altogether, omit it.
 */
public record RejectRequest(@NotNull Instant estimatedArrival) {}
