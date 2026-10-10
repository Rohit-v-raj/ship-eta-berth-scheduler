-- Static infrastructure: port -> terminal -> quay -> berth.
-- Master data: these rows describe things that exist, not things that happen.
-- Whether a berth is occupied is never stored here; it is derived from
-- berth_allocation (V3).
--
-- Conventions for every table in this schema:
--   * surrogate BIGINT id plus a unique business key
--   * timestamps are UTC, DATETIME(6); the server time zone is not UTC, so the
--     defaults use UTC_TIMESTAMP rather than CURRENT_TIMESTAMP
--   * enum-like columns are VARCHAR with a CHECK constraint
--   * version is the optimistic-lock counter maintained by Hibernate

CREATE TABLE port (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    un_locode          VARCHAR(5)   NOT NULL,
    name               VARCHAR(100) NOT NULL,
    country_code       VARCHAR(2)   NOT NULL,
    time_zone          VARCHAR(40)  NOT NULL,
    latitude           DOUBLE       NOT NULL,
    longitude          DOUBLE       NOT NULL,
    pilot_boarding_lat DOUBLE       NULL,
    pilot_boarding_lon DOUBLE       NULL,
    created_at         DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    updated_at         DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    version            BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_port_un_locode (un_locode)
);

CREATE TABLE terminal (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    port_id       BIGINT       NOT NULL,
    code          VARCHAR(20)  NOT NULL,
    name          VARCHAR(100) NOT NULL,
    terminal_type VARCHAR(20)  NOT NULL,
    operator_name VARCHAR(100) NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at    DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    updated_at    DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    version       BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_terminal_code (code),
    CONSTRAINT fk_terminal_port FOREIGN KEY (port_id) REFERENCES port (id),
    CONSTRAINT ck_terminal_type CHECK (terminal_type IN
        ('CONTAINER', 'DRY_BULK', 'LIQUID_BULK', 'RORO', 'GENERAL_CARGO', 'PASSENGER', 'MULTIPURPOSE')),
    CONSTRAINT ck_terminal_status CHECK (status IN ('ACTIVE', 'MAINTENANCE', 'CLOSED'))
);

-- A quay is one continuous quay wall. Berths are named stretches of it,
-- positioned by metre mark, which is what continuous berth planning needs later.
CREATE TABLE quay (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    terminal_id BIGINT       NOT NULL,
    code        VARCHAR(20)  NOT NULL,
    name        VARCHAR(100) NOT NULL,
    length_m    DECIMAL(7,2) NOT NULL,
    created_at  DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    updated_at  DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    version     BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_quay_terminal_code (terminal_id, code),
    CONSTRAINT fk_quay_terminal FOREIGN KEY (terminal_id) REFERENCES terminal (id),
    CONSTRAINT ck_quay_length CHECK (length_m > 0)
);

-- Physical limits (LOA, beam, draft, air draft, DWT) are data so allocation can
-- check whether a vessel fits. latitude/longitude is the destination the ETA
-- model predicts towards.
CREATE TABLE berth (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    quay_id         BIGINT       NOT NULL,
    code            VARCHAR(20)  NOT NULL,
    name            VARCHAR(100) NOT NULL,
    seq             INT          NOT NULL,
    quay_start_m    DECIMAL(7,2) NULL,
    quay_end_m      DECIMAL(7,2) NULL,
    length_m        DECIMAL(7,2) NOT NULL,
    depth_m         DECIMAL(5,2) NOT NULL,
    max_loa_m       DECIMAL(6,2) NULL,
    max_beam_m      DECIMAL(5,2) NULL,
    max_draft_m     DECIMAL(5,2) NULL,
    max_air_draft_m DECIMAL(5,2) NULL,
    max_dwt_t       INT          NULL,
    latitude        DOUBLE       NOT NULL,
    longitude       DOUBLE       NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    updated_at      DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    version         BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_berth_code (code),
    KEY ix_berth_seq (seq),
    CONSTRAINT fk_berth_quay FOREIGN KEY (quay_id) REFERENCES quay (id),
    CONSTRAINT ck_berth_status CHECK (status IN ('ACTIVE', 'MAINTENANCE', 'CLOSED')),
    CONSTRAINT ck_berth_length CHECK (length_m > 0),
    CONSTRAINT ck_berth_quay_range CHECK (
        quay_start_m IS NULL OR quay_end_m IS NULL OR quay_end_m > quay_start_m)
);
