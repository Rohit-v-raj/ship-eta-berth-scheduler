package com.port.scheduler.exceptions;

/** The request is valid but does not fit the visit's current state. */
public class VisitConflictException extends RuntimeException {
    public VisitConflictException(String message)
    {
        super(message);
    }
}
