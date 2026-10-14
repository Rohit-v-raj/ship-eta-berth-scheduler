package com.port.scheduler.model.view;

import com.port.scheduler.entity.topology.OperationalStatus;
import com.port.scheduler.entity.vessel.VesselType;
import com.port.scheduler.entity.visit.AllocationStatus;

import java.time.Instant;
import java.util.List;

/**
 * The berth plan over a period: one lane per berth, each with the windows
 * given to visits and the maintenance planned on it. Open windows are always
 * included, completed ones and maintenance when they overlap the period.
 */
public record BerthPlanView(Instant from, Instant to, List<Lane> berths) {

    public record Lane(String berthCode, String berthName, String terminalCode, OperationalStatus status,
                       List<Window> windows, List<MaintenanceView> maintenance) {}

    public record Window(String visitRef, String vesselCode, String vesselName, VesselType vesselType,
                         AllocationStatus status, Instant plannedStart, Instant plannedEnd,
                         Instant actualStart, Instant actualEnd,
                         Instant etaBerth, Instant rtaBerth, Instant ptaBerth) {}
}
