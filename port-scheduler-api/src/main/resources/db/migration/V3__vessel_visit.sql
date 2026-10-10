-- Marine operations: the vessel visit (port call) and everything that hangs off it.
--
-- Times follow the Estimated / Requested / Planned / Actual pattern used for
-- just-in-time port calls:
--   eta_* estimated (by the vessel or the ML model)
--   rta_* requested by the terminal (a virtual-arrival slot)
--   pta_* planned, i.e. agreed
--   ata_* / atd_* actual
-- vessel_visit holds the current value of each; visit_event keeps the history.

CREATE TABLE vessel_visit (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    visit_ref          VARCHAR(32)  NOT NULL,
    vessel_id          BIGINT       NOT NULL,
    terminal_id        BIGINT       NULL,
    inbound_voyage_no  VARCHAR(20)  NULL,
    outbound_voyage_no VARCHAR(20)  NULL,
    phase              VARCHAR(20)  NOT NULL,
    eta_berth          DATETIME(6)  NULL,
    rta_berth          DATETIME(6)  NULL,
    pta_berth          DATETIME(6)  NULL,
    ata_berth          DATETIME(6)  NULL,
    etd_berth          DATETIME(6)  NULL,
    atd_berth          DATETIME(6)  NULL,
    arrival_draft_m    DECIMAL(5,2) NULL,
    departure_draft_m  DECIMAL(5,2) NULL,
    priority           INT          NOT NULL DEFAULT 0,
    remarks            VARCHAR(500) NULL,
    created_at         DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    updated_at         DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    version            BIGINT       NOT NULL DEFAULT 0,
    -- NULL once the call is over, so the unique index below only binds open
    -- visits: a vessel can have many past visits but one current one.
    open_vessel_id     BIGINT GENERATED ALWAYS AS
        (CASE WHEN phase IN ('DEPARTED', 'CANCELLED') THEN NULL ELSE vessel_id END) VIRTUAL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_visit_ref (visit_ref),
    UNIQUE KEY uq_visit_one_open_per_vessel (open_vessel_id),
    KEY ix_visit_phase (phase),
    CONSTRAINT fk_visit_vessel FOREIGN KEY (vessel_id) REFERENCES vessel (id),
    CONSTRAINT fk_visit_terminal FOREIGN KEY (terminal_id) REFERENCES terminal (id),
    CONSTRAINT ck_visit_phase CHECK (phase IN
        ('ANNOUNCED', 'INBOUND', 'ARRIVED', 'BERTHED', 'WORKING', 'COMPLETED', 'DEPARTED', 'CANCELLED'))
);

-- One row per berth window given to a visit. A berth is occupied when it has
-- an open (PLANNED or ACTIVE) allocation.
CREATE TABLE berth_allocation (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    visit_id        BIGINT       NOT NULL,
    berth_id        BIGINT       NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    planned_start   DATETIME(6)  NULL,
    planned_end     DATETIME(6)  NULL,
    actual_start    DATETIME(6)  NULL,
    actual_end      DATETIME(6)  NULL,
    position_from_m DECIMAL(7,2) NULL,
    position_to_m   DECIMAL(7,2) NULL,
    berthing_side   VARCHAR(10)  NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    updated_at      DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    version         BIGINT       NOT NULL DEFAULT 0,
    -- Same trick as vessel_visit: the database itself refuses a second open
    -- allocation on a berth, or a second one for a visit. When time-window
    -- planning arrives (several future PLANNED windows per berth) the berth
    -- rule relaxes to ACTIVE only.
    open_berth_id   BIGINT GENERATED ALWAYS AS
        (CASE WHEN status IN ('PLANNED', 'ACTIVE') THEN berth_id END) VIRTUAL,
    open_visit_id   BIGINT GENERATED ALWAYS AS
        (CASE WHEN status IN ('PLANNED', 'ACTIVE') THEN visit_id END) VIRTUAL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_alloc_one_open_per_berth (open_berth_id),
    UNIQUE KEY uq_alloc_one_open_per_visit (open_visit_id),
    CONSTRAINT fk_alloc_visit FOREIGN KEY (visit_id) REFERENCES vessel_visit (id),
    CONSTRAINT fk_alloc_berth FOREIGN KEY (berth_id) REFERENCES berth (id),
    CONSTRAINT ck_alloc_status CHECK (status IN ('PLANNED', 'ACTIVE', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_alloc_side CHECK (berthing_side IS NULL OR berthing_side IN ('PORT', 'STARBOARD'))
);

-- Append-only. Every ETA the system has produced for a visit, with the inputs
-- it was produced from. Compared with vessel_visit.ata_berth this gives the
-- model's real-world error.
CREATE TABLE eta_prediction (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    visit_id          BIGINT      NOT NULL,
    predicted_at      DATETIME(6) NOT NULL,
    source            VARCHAR(20) NOT NULL,
    model_name        VARCHAR(50) NULL,
    model_version     VARCHAR(50) NULL,
    lat               DOUBLE      NULL,
    lon               DOUBLE      NULL,
    sog_kn            DOUBLE      NULL,
    cog_deg           DOUBLE      NULL,
    dest_lat          DOUBLE      NULL,
    dest_lon          DOUBLE      NULL,
    distance_nm       DOUBLE      NULL,
    eta_minutes       DOUBLE      NOT NULL,
    naive_eta_minutes DOUBLE      NULL,
    predicted_arrival DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY ix_eta_visit_time (visit_id, predicted_at),
    CONSTRAINT fk_eta_visit FOREIGN KEY (visit_id) REFERENCES vessel_visit (id),
    CONSTRAINT ck_eta_source CHECK (source IN ('ML_MODEL', 'VESSEL_DECLARED', 'MANUAL'))
);

-- Append-only ledger of port-call timestamps: who said what time, and when.
-- event_time is the moment the event refers to; recorded_at is when we learnt it.
CREATE TABLE visit_event (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    visit_id    BIGINT       NOT NULL,
    milestone   VARCHAR(30)  NOT NULL,
    classifier  VARCHAR(12)  NOT NULL,
    event_time  DATETIME(6)  NOT NULL,
    recorded_at DATETIME(6)  NOT NULL,
    source      VARCHAR(20)  NOT NULL,
    berth_id    BIGINT       NULL,
    remarks     VARCHAR(500) NULL,
    PRIMARY KEY (id),
    KEY ix_event_visit_time (visit_id, recorded_at),
    CONSTRAINT fk_event_visit FOREIGN KEY (visit_id) REFERENCES vessel_visit (id),
    CONSTRAINT fk_event_berth FOREIGN KEY (berth_id) REFERENCES berth (id),
    CONSTRAINT ck_event_milestone CHECK (milestone IN
        ('ARRIVAL_PILOT_STATION', 'ARRIVAL_BERTH', 'CARGO_OPS_START', 'CARGO_OPS_COMPLETE', 'DEPARTURE_BERTH')),
    CONSTRAINT ck_event_classifier CHECK (classifier IN ('ESTIMATED', 'REQUESTED', 'PLANNED', 'ACTUAL')),
    CONSTRAINT ck_event_source CHECK (source IN ('VESSEL', 'TERMINAL', 'PORT_AUTHORITY', 'ML_MODEL', 'SYSTEM'))
);
