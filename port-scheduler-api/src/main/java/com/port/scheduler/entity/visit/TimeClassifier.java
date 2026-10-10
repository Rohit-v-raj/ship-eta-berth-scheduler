package com.port.scheduler.entity.visit;

/**
 * How firm a timestamp is. Estimated, requested and planned are the three
 * steps of agreeing a just-in-time arrival: the vessel (or the ETA model)
 * estimates, the terminal requests, and the vessel confirms the request,
 * which makes it planned. Actual is what happened.
 */
public enum TimeClassifier {
    ESTIMATED,
    REQUESTED,
    PLANNED,
    ACTUAL
}
