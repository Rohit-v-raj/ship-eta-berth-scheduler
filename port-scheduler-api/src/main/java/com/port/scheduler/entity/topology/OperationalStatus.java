package com.port.scheduler.entity.topology;

/** Whether a piece of infrastructure (terminal, berth) can be used right now. */
public enum OperationalStatus {
    ACTIVE,
    MAINTENANCE,
    CLOSED
}
