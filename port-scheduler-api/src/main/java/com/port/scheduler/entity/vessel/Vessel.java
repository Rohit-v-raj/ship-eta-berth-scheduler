package com.port.scheduler.entity.vessel;

import com.port.scheduler.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

/**
 * Vessel master data: what a ship is, independent of any particular call.
 *
 * {@code code} is the terminal's own short identifier and is what the API
 * calls {@code vesselId}. IMO number and MMSI are optional because a vessel
 * can be scheduled before its particulars are on record.
 */
@Entity
@Table(name = "vessel")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Vessel extends BaseEntity {

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(length = 100)
    private String name;

    /** Seven digits today; the IMO has said future numbers may run to eight. */
    @Column(unique = true, length = 8)
    private String imoNumber;

    @Column(unique = true, length = 9)
    private String mmsi;

    @Column(length = 10)
    private String callSign;

    @Column(length = 2)
    private String flagCountry;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "vessel_type", nullable = false, length = 20)
    private VesselType type = VesselType.OTHER;

    /** Length overall. */
    @Column(name = "loa_m", precision = 6, scale = 2)
    private BigDecimal loaMeters;

    @Column(name = "beam_m", precision = 5, scale = 2)
    private BigDecimal beamMeters;

    @Column(name = "max_draft_m", precision = 5, scale = 2)
    private BigDecimal maxDraftMeters;

    @Column(name = "air_draft_m", precision = 5, scale = 2)
    private BigDecimal airDraftMeters;

    private Integer grossTonnage;

    @Column(name = "deadweight_t")
    private Integer deadweightTonnes;

    private Integer teuCapacity;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private VesselStatus status = VesselStatus.ACTIVE;

    public Vessel(String code) {
        this.code = code;
    }
}
