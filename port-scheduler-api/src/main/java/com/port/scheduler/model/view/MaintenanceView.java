package com.port.scheduler.model.view;

import java.time.Instant;

/** A planned maintenance window on a berth: no vessel may lie there from {@code from} until {@code to}. */
public record MaintenanceView(Long id, String berthCode, String berthName, Instant from, Instant to, String reason) {}
