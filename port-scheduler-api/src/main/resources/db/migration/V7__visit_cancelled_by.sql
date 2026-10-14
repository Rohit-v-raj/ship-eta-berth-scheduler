-- Who ended a call that did not take place. The DCSA Port Call standard tells
-- the two apart: a call omitted by the carrier (VESSEL), and a berth service
-- cancelled by the terminal that was to provide it (TERMINAL).
-- Every call cancelled so far was omitted by its vessel's operator.

ALTER TABLE vessel_visit
    ADD COLUMN cancelled_by VARCHAR(20) NULL,
    ADD CONSTRAINT ck_visit_cancelled_by CHECK (cancelled_by IS NULL OR cancelled_by IN ('VESSEL', 'TERMINAL'));

UPDATE vessel_visit SET cancelled_by = 'VESSEL' WHERE phase = 'CANCELLED';
