-- Planned maintenance on a berth: a window in which no vessel may lie there.
-- Windows are known in advance and recorded by the terminal planner. One
-- that overlaps a vessel's booking, or another window on the same berth, is
-- refused by the service before it reaches this table.

CREATE TABLE berth_maintenance (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    berth_id   BIGINT       NOT NULL,
    starts_at  DATETIME(6)  NOT NULL,
    ends_at    DATETIME(6)  NOT NULL,
    reason     VARCHAR(200) NULL,
    created_at DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    updated_at DATETIME(6)  NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    version    BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY ix_maintenance_berth_time (berth_id, starts_at),
    CONSTRAINT fk_maintenance_berth FOREIGN KEY (berth_id) REFERENCES berth (id),
    CONSTRAINT ck_maintenance_window CHECK (ends_at > starts_at)
);
