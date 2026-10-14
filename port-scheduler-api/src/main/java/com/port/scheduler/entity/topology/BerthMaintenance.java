package com.port.scheduler.entity.topology;

import com.port.scheduler.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Planned maintenance on a berth: from {@code startsAt} until {@code endsAt}
 * no vessel may lie there. Like occupancy, it is a fact about time, so the
 * berth itself carries no "under maintenance" flag; {@code Berth.status} is
 * kept for a berth taken out of service for good.
 */
@Entity
@Table(name = "berth_maintenance")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BerthMaintenance extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "berth_id", updatable = false)
    private Berth berth;

    @Column(nullable = false, updatable = false)
    private Instant startsAt;

    @Column(nullable = false, updatable = false)
    private Instant endsAt;

    @Column(length = 200, updatable = false)
    private String reason;

    public BerthMaintenance(Berth berth, Instant startsAt, Instant endsAt, String reason) {
        this.berth = berth;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.reason = reason;
    }

    /** Whether this window and the period from {@code from} until {@code to} share any time. */
    public boolean overlaps(Instant from, Instant to) {
        return startsAt.isBefore(to) && from.isBefore(endsAt);
    }
}
