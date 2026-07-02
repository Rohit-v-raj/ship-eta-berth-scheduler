package com.port.scheduler.model;

/**
 * Response: the predicted ETA plus the berth/queue assignment.
 * {@code status} is either "BERTHED" (a berth was free) or "QUEUED".
 */
public record ScheduleResponse(
        String vesselId,
        double etaMinutes,
        double etaHours,
        String status,
        String berthId,
        String berthName,
        Integer queueNumber
) {}
