package com.port.scheduler.entity.topology;

import com.port.scheduler.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The port the terminals belong to, identified by its UN/LOCODE. The pilot
 * boarding place is the reference point just-in-time arrival times are agreed
 * against.
 */
@Entity
@Table(name = "port")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Port extends BaseEntity {

    @Column(nullable = false, unique = true, length = 5)
    private String unLocode;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 2)
    private String countryCode;

    /** IANA zone id, e.g. Europe/Oslo. Stored times are UTC; this is for display. */
    @Column(nullable = false, length = 40)
    private String timeZone;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    private Double pilotBoardingLat;

    private Double pilotBoardingLon;
}
