package com.port.scheduler.entity.visit;

import com.port.scheduler.entity.BaseEntity;
import com.port.scheduler.entity.topology.Berth;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A berth window given to a visit. This is where occupancy lives: a berth is
 * occupied while it has an allocation in an open status.
 *
 * The database allows one open allocation per berth and one per visit (unique
 * indexes over generated columns), so a double booking fails even if two
 * requests race past the application's own check.
 */
@Entity
@Table(name = "berth_allocation")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BerthAllocation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "visit_id", updatable = false)
    private VesselVisit visit;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "berth_id", updatable = false)
    private Berth berth;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private AllocationStatus status;

    private Instant plannedStart;

    private Instant plannedEnd;

    private Instant actualStart;

    private Instant actualEnd;

    /** Bow and stern metre marks on the quay, for continuous berth planning. */
    @Column(name = "position_from_m", precision = 7, scale = 2)
    private BigDecimal positionFromMeters;

    @Column(name = "position_to_m", precision = 7, scale = 2)
    private BigDecimal positionToMeters;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 10)
    private BerthingSide berthingSide;

    public BerthAllocation(VesselVisit visit, Berth berth, Instant plannedStart) {
        this.visit = visit;
        this.berth = berth;
        this.plannedStart = plannedStart;
        this.status = AllocationStatus.PLANNED;
    }

    /** Close the window: the berth is free again from {@code at}. */
    public void complete(Instant at) {
        this.status = AllocationStatus.COMPLETED;
        this.actualEnd = at;
    }
}
