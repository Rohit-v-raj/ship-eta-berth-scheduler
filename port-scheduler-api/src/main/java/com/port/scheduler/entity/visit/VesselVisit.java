package com.port.scheduler.entity.visit;

import com.port.scheduler.entity.BaseEntity;
import com.port.scheduler.entity.topology.Terminal;
import com.port.scheduler.entity.vessel.Vessel;
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
 * One call of a vessel at the port: the central transaction of the system.
 * Berth allocations, ETA predictions and events all belong to a visit.
 *
 * The time fields hold the current value of each timestamp, named by how firm
 * it is: eta (estimated), rta (requested by the terminal), pta (planned: the
 * vessel's confirmation of the rta), ata / atd (actual). The history behind
 * them is in {@link VisitEvent}.
 *
 * A vessel has at most one open visit; the database enforces this.
 */
@Entity
@Table(name = "vessel_visit")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VesselVisit extends BaseEntity {

    @Column(nullable = false, unique = true, updatable = false, length = 32)
    private String visitRef;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "vessel_id", updatable = false)
    private Vessel vessel;

    /** Set once a berth is allocated; a visit can be announced before that. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "terminal_id")
    private Terminal terminal;

    @Column(length = 20)
    private String inboundVoyageNo;

    @Column(length = 20)
    private String outboundVoyageNo;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private VisitPhase phase;

    private Instant etaBerth;

    private Instant rtaBerth;

    private Instant ptaBerth;

    private Instant ataBerth;

    private Instant etdBerth;

    private Instant atdBerth;

    @Column(name = "arrival_draft_m", precision = 5, scale = 2)
    private BigDecimal arrivalDraftMeters;

    @Column(name = "departure_draft_m", precision = 5, scale = 2)
    private BigDecimal departureDraftMeters;

    /** How long the terminal expects this visit to occupy a berth. */
    @Column(name = "planned_service_min")
    private Integer plannedServiceMinutes;

    @Column(nullable = false)
    private int priority;

    @Column(length = 500)
    private String remarks;

    public VesselVisit(String visitRef, Vessel vessel, VisitPhase phase) {
        this.visitRef = visitRef;
        this.vessel = vessel;
        this.phase = phase;
    }

    /** The vessel has left the berth: the call is over. */
    public void depart(Instant at) {
        this.phase = VisitPhase.DEPARTED;
        this.atdBerth = at;
    }
}
