package com.port.scheduler.exceptions;

/** The ETA model service could not be reached, or did not answer in time. */
public class EtaServiceUnavailableException extends RuntimeException {
    public EtaServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
