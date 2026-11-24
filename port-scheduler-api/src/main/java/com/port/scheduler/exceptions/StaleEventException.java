package com.port.scheduler.exceptions;

/**
 * An event that was sent before the latest one already applied for its
 * vessel, and arrived after it. It is not applied; the sender is told why.
 */
public class StaleEventException extends RuntimeException {
    public StaleEventException(String message) {
        super(message);
    }
}
