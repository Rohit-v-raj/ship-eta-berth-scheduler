-- A ship that arrives before its berth is ready anchors and waits. It reports
-- its arrival at the anchorage and its departure from it (DCSA Port Call 2.0:
-- the ANCHORAGE service, STRT and CMPL), and the terminal may bring it in
-- sooner than its estimate: it is already here.

ALTER TABLE vessel_visit
    ADD COLUMN ata_anchorage DATETIME(6) NULL,
    ADD COLUMN atd_anchorage DATETIME(6) NULL;

ALTER TABLE visit_event
    DROP CHECK ck_event_milestone;

ALTER TABLE visit_event
    ADD CONSTRAINT ck_event_milestone CHECK (milestone IN
        ('ARRIVAL_PILOT_STATION', 'ANCHORAGE_START', 'ANCHORAGE_COMPLETE', 'ARRIVAL_BERTH',
         'CARGO_OPS_START', 'CARGO_OPS_COMPLETE', 'DEPARTURE_BERTH'));
