package com.port.scheduler.model.view;

import com.port.scheduler.entity.visit.EventSource;
import com.port.scheduler.entity.visit.Milestone;
import com.port.scheduler.entity.visit.TimeClassifier;

import java.time.Instant;
import java.util.List;

/**
 * A visit with its history: every timestamp anyone reported for it, and every
 * position report with the ETA the model gave. Unlike the DCSA feed, the
 * ledger here includes the model's own estimates.
 */
public record VisitDetailView(VisitView visit, List<EventView> events, List<VisitView.PositionReport> reports) {

    /** {@code eventTime} is the moment the event refers to; {@code recordedAt} is when it was reported. */
    public record EventView(Milestone milestone, TimeClassifier classifier, Instant eventTime, Instant recordedAt,
                            EventSource source, String berthCode, String remarks) {}
}
