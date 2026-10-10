-- Demo topology: one port, two terminals, four berths.
--
-- The location is real: Floro is the busiest mooring area in the project's AIS
-- data (148 arrivals by 47 vessels), and the berth coordinates are observed
-- mooring positions there, so the ETA model predicts inside its training region.
-- Terminal names, types and berth dimensions are invented for the demo.

INSERT INTO port (un_locode, name, country_code, time_zone, latitude, longitude)
VALUES ('NOFRO', 'Floro', 'NO', 'Europe/Oslo', 61.6000, 5.0330);

INSERT INTO terminal (port_id, code, name, terminal_type, operator_name)
SELECT id, 'FRO-CT', 'Floro Container Terminal', 'CONTAINER', 'Demo Terminal Operator' FROM port WHERE un_locode = 'NOFRO';

INSERT INTO terminal (port_id, code, name, terminal_type, operator_name)
SELECT id, 'FRO-MP', 'Floro Multipurpose Terminal', 'MULTIPURPOSE', 'Demo Terminal Operator' FROM port WHERE un_locode = 'NOFRO';

INSERT INTO quay (terminal_id, code, name, length_m)
SELECT id, 'Q1', 'Container Quay', 400.00 FROM terminal WHERE code = 'FRO-CT';

INSERT INTO quay (terminal_id, code, name, length_m)
SELECT id, 'Q1', 'Multipurpose Quay', 300.00 FROM terminal WHERE code = 'FRO-MP';

INSERT INTO berth (quay_id, code, name, seq, quay_start_m, quay_end_m, length_m, depth_m,
                   max_loa_m, max_beam_m, max_draft_m, max_dwt_t, latitude, longitude)
SELECT q.id, 'B1', 'Berth 1', 1, 0.00, 200.00, 200.00, 12.00, 190.00, 32.00, 10.50, 40000, 61.6120, 5.0700
FROM quay q JOIN terminal t ON t.id = q.terminal_id WHERE t.code = 'FRO-CT' AND q.code = 'Q1';

INSERT INTO berth (quay_id, code, name, seq, quay_start_m, quay_end_m, length_m, depth_m,
                   max_loa_m, max_beam_m, max_draft_m, max_dwt_t, latitude, longitude)
SELECT q.id, 'B2', 'Berth 2', 2, 200.00, 400.00, 200.00, 12.00, 190.00, 32.00, 10.50, 40000, 61.6110, 5.0700
FROM quay q JOIN terminal t ON t.id = q.terminal_id WHERE t.code = 'FRO-CT' AND q.code = 'Q1';

INSERT INTO berth (quay_id, code, name, seq, quay_start_m, quay_end_m, length_m, depth_m,
                   max_loa_m, max_beam_m, max_draft_m, max_dwt_t, latitude, longitude)
SELECT q.id, 'B3', 'Berth 3', 3, 0.00, 150.00, 150.00, 9.00, 140.00, 24.00, 7.50, 15000, 61.6130, 5.0720
FROM quay q JOIN terminal t ON t.id = q.terminal_id WHERE t.code = 'FRO-MP' AND q.code = 'Q1';

INSERT INTO berth (quay_id, code, name, seq, quay_start_m, quay_end_m, length_m, depth_m,
                   max_loa_m, max_beam_m, max_draft_m, max_dwt_t, latitude, longitude)
SELECT q.id, 'B4', 'Berth 4', 4, 150.00, 300.00, 150.00, 9.00, 140.00, 24.00, 7.50, 15000, 61.6100, 5.0710
FROM quay q JOIN terminal t ON t.id = q.terminal_id WHERE t.code = 'FRO-MP' AND q.code = 'Q1';
