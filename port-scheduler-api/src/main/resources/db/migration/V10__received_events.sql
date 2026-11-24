-- DCSA events arrive at least once and in no promised order. The terminal
-- keeps the id of every event it has applied, so a repeat is not applied
-- again, and for each vessel the sender's time of the latest event applied,
-- so an estimate or an omitted call sent before it is not applied late.

CREATE TABLE received_event (
    event_id    CHAR(36)    NOT NULL,
    updated_at  DATETIME(6) NULL,
    received_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
    PRIMARY KEY (event_id)
);

ALTER TABLE vessel
    ADD COLUMN last_event_sent_at DATETIME(6) NULL;
