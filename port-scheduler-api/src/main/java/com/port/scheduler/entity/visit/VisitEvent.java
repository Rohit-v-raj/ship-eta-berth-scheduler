package com.port.scheduler.entity.visit;

import com.port.scheduler.entity.topology.Berth;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * One entry in a visit's timestamp ledger: who said which time, and when.
 * Append-only. {@code eventTime} is the moment the event refers to;
 * {@code recordedAt} is when the system learnt it.
 */
@Entity
@Immutable
@Table(name = "visit_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VisitEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "visit_id")
    private VesselVisit visit;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private Milestone milestone;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 12)
    private TimeClassifier classifier;

    @Column(nullable = false)
    private Instant eventTime;

    @Column(nullable = false)
    private Instant recordedAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private EventSource source;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "berth_id")
    private Berth berth;

    @Column(length = 500)
    private String remarks;

    public static VisitEvent of(VesselVisit visit, Milestone milestone, TimeClassifier classifier,
                                Instant eventTime, Instant recordedAt, EventSource source, Berth berth) {
        VisitEvent e = new VisitEvent();
        e.visit = visit;
        e.milestone = milestone;
        e.classifier = classifier;
        e.eventTime = eventTime;
        e.recordedAt = recordedAt;
        e.source = source;
        e.berth = berth;
        return e;
    }
}
