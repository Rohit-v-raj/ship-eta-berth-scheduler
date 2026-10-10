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
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One continuous quay wall. Berths are named stretches of it, positioned by
 * metre mark from the quay's start.
 */
@Entity
@Table(name = "quay")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Quay extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "terminal_id")
    private Terminal terminal;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "length_m", nullable = false, precision = 7, scale = 2)
    private BigDecimal lengthMeters;
}
