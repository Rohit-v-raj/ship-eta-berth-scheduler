package com.port.scheduler.model;

import java.time.Instant;

/**
 * A vessel operator's confirmation of the terminal's requested arrival.
 * {@code requestedArrival} is the time being confirmed; sending it lets the
 * service refuse a confirmation of a request that has since been replaced.
 */
public record ConfirmRequest(Instant requestedArrival) {}
