package com.port.scheduler.entity.visit;

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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * One ETA produced for a visit, with the inputs it was produced from.
 * Append-only: a new position report means a new row, never an update.
 * Set against the visit's actual arrival, these rows measure how the model
 * performs on live traffic.
 */
@Entity
@Immutable
@Table(name = "eta_prediction")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class EtaPrediction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "visit_id")
    private VesselVisit visit;

    @Column(nullable = false)
    private Instant predictedAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private PredictionSource source;

    @Column(length = 50)
    private String modelName;

    @Column(length = 50)
    private String modelVersion;

    private Double lat;

    private Double lon;

    @Column(name = "sog_kn")
    private Double sogKnots;

    @Column(name = "cog_deg")
    private Double cogDegrees;

    private Double destLat;

    private Double destLon;

    private Double distanceNm;

    @Column(nullable = false)
    private double etaMinutes;

    /** Distance over speed: the physics baseline the model is judged against. */
    private Double naiveEtaMinutes;

    @Column(nullable = false)
    private Instant predictedArrival;
}
