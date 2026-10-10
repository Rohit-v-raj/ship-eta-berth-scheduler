package com.port.scheduler.entity.topology;

import com.port.scheduler.entity.BaseEntity;
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

/**
 * A berth: a stretch of quay a vessel can lie alongside.
 *
 * Deliberately has no "occupied" flag. Occupancy is a fact about time, so it
 * lives in {@code BerthAllocation}; a berth is occupied while it has an open
 * allocation. The limits here (LOA, beam, draft, air draft, DWT) are what a
 * vessel must fit within. Latitude/longitude is the destination the ETA model
 * predicts towards.
 */
@Entity
@Table(name = "berth")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Berth extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quay_id")
    private Quay quay;

    /** Business key used by the API, e.g. B1. */
    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    /** Order in which free berths are offered. */
    @Column(nullable = false)
    private int seq;

    @Column(name = "quay_start_m", precision = 7, scale = 2)
    private BigDecimal quayStartMeters;

    @Column(name = "quay_end_m", precision = 7, scale = 2)
    private BigDecimal quayEndMeters;

    @Column(name = "length_m", nullable = false, precision = 7, scale = 2)
    private BigDecimal lengthMeters;

    /** Water depth alongside. */
    @Column(name = "depth_m", nullable = false, precision = 5, scale = 2)
    private BigDecimal depthMeters;

    @Column(name = "max_loa_m", precision = 6, scale = 2)
    private BigDecimal maxLoaMeters;

    @Column(name = "max_beam_m", precision = 5, scale = 2)
    private BigDecimal maxBeamMeters;

    @Column(name = "max_draft_m", precision = 5, scale = 2)
    private BigDecimal maxDraftMeters;

    @Column(name = "max_air_draft_m", precision = 5, scale = 2)
    private BigDecimal maxAirDraftMeters;

    @Column(name = "max_dwt_t")
    private Integer maxDeadweightTonnes;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private OperationalStatus status;
}
