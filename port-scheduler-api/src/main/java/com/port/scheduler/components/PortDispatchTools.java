package com.port.scheduler.components;

import java.time.Instant;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component 
public class PortDispatchTools {
    
    @Tool(description = "Retrieve vessel telemetry data based on MMSI")
    public Map<String, Object> getVesselTelemetry(String mmsi) {
        return Map.of(
            "mmsi", mmsi,
            "vesselName", "CMA CGM Marco Polo",
            "speedKnots", 13.5,
            "distanceToPilotNm", 18.0,
            "estimatedArrivalIso", Instant.now().plusSeconds(4800).toString()
        );
    }

    @Tool(description = "Inspect berth availability and current vessel clearance status")
    public Map<String, Object> checkBerthSlot(String berthId) {
        return Map.of(
            "berthId", berthId,
            "occupied", true,
            "occupiedUntilIso", Instant.now().plusSeconds(9000).toString(),
            "maxDraftMeters", 15.5
        );
    }

    @Tool(description = "Lookup vessel physical specs and real-time navigation telemetry by vessel name or MMSI.")
    public Map<String, Object> lookupVesselTelemetry(String vesselIdentifier) {
        return Map.of(
            "vesselName", vesselIdentifier,
            "loaMeters", 399.9,
            "draftMeters", 15.2,
            "speedKnots", 15.5,
            "distanceToPilotNm", 20.0
        );
    }

    @Tool(description = "Check berth availability, max draft, LOA limits, and current clearance time.")
    public Map<String, Object> getBerthStatus(String berthId) {
        if ("B-1".equalsIgnoreCase(berthId)) {
            return Map.of(
                "berthId", "B-1",
                "isOccupied", true,
                "occupiedUntilMinutes", 180, // Occupied for 3 hours
                "maxDraftMeters", 16.0,
                "maxLoaMeters", 400.0
            );
        }
        return Map.of(
            "berthId", berthId,
            "isOccupied", false,
            "occupiedUntilMinutes", 0,
            "maxDraftMeters", 14.0,
            "maxLoaMeters", 300.0
        );
    }

    @Tool(description = "Executes an official dispatch action or notifies harbor control.")
    public String executeDispatchDecision(String action, String details) {
        // Logs or sends Kafka message / database write
        return "ACTION_COMMITTED: " + action + " -> " + details;
    }
}
