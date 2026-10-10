-- Vessel master data.
-- code is the terminal's own short identifier for a ship and is what the API
-- calls vesselId. IMO number and MMSI are the international identifiers; both
-- are optional because small craft may have neither on record.

CREATE TABLE vessel (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    code          VARCHAR(32)  NOT NULL,
    name          VARCHAR(100) NULL,
    imo_number    VARCHAR(7)   NULL,
    mmsi          VARCHAR(9)   NULL,
    call_sign     VARCHAR(10)  NULL,
    flag_country  VARCHAR(2)   NULL,
    vessel_type   VARCHAR(20)  NOT NULL DEFAULT 'OTHER',
    loa_m         DECIMAL(6,2) NULL,
    beam_m        DECIMAL(5,2) NULL,
    max_draft_m   DECIMAL(5,2) NULL,
    air_draft_m   DECIMAL(5,2) NULL,
    gross_tonnage INT          NULL,
    deadweight_t  INT          NULL,
    teu_capacity  INT          NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at    DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    updated_at    DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    version       BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uq_vessel_code (code),
    UNIQUE KEY uq_vessel_imo (imo_number),
    UNIQUE KEY uq_vessel_mmsi (mmsi),
    CONSTRAINT ck_vessel_type CHECK (vessel_type IN
        ('CONTAINER', 'BULK_CARRIER', 'TANKER', 'RORO', 'GENERAL_CARGO', 'PASSENGER', 'FISHING', 'TUG', 'OTHER')),
    CONSTRAINT ck_vessel_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
