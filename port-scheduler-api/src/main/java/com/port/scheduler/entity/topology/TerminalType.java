package com.port.scheduler.entity.topology;

/** What a terminal handles. A type, not a subclass: every terminal has the same shape. */
public enum TerminalType {
    CONTAINER,
    DRY_BULK,
    LIQUID_BULK,
    RORO,
    GENERAL_CARGO,
    PASSENGER,
    MULTIPURPOSE
}
