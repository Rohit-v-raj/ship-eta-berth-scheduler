package com.port.scheduler.entity.visit;

import lombok.Getter;

import java.util.Arrays;

/**
 * How firm a timestamp is. Estimated, requested and planned are the three
 * steps of agreeing a just-in-time arrival: the vessel (or the ETA model)
 * estimates, the terminal requests, and the vessel confirms the request,
 * which makes it planned. Actual is what happened.
 *
 * {@code code} is the DCSA Port Call classifier code.
 */
@Getter
public enum TimeClassifier {
    ESTIMATED("EST"),
    REQUESTED("REQ"),
    PLANNED("PLN"),
    ACTUAL("ACT");

    private final String code;

    TimeClassifier(String code) {
        this.code = code;
    }

    public static TimeClassifier fromCode(String code) {
        return Arrays.stream(values()).filter(c -> c.code.equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown classifierCode '" + code + "'; use EST, REQ, PLN or ACT"));
    }
}
