package com.port.scheduler.model.view;

import com.port.scheduler.entity.topology.OperationalStatus;
import com.port.scheduler.entity.topology.TerminalType;

import java.math.BigDecimal;
import java.util.List;

/**
 * The port, its terminals and its berths, for drawing the harbour. Master
 * data: it changes only by migration, so a caller may keep it.
 */
public record PortView(
        String unLocode,
        String name,
        String countryCode,
        /** IANA zone id, for showing times in port local time. */
        String timeZone,
        double latitude,
        double longitude,
        List<TerminalView> terminals,
        List<BerthView> berths
) {

    public record TerminalView(String code, String name, TerminalType type, OperationalStatus status) {}

    public record BerthView(
            String code,
            String name,
            int seq,
            String terminalCode,
            String quayCode,
            double latitude,
            double longitude,
            BigDecimal lengthMeters,
            BigDecimal depthMeters,
            BigDecimal maxLoaMeters,
            BigDecimal maxBeamMeters,
            BigDecimal maxDraftMeters,
            OperationalStatus status
    ) {}
}
