-- How long the terminal expects this visit to occupy a berth, in minutes.
-- Kept on the visit, not only on the allocation, so that a queued vessel still
-- has it when a berth is finally allocated to it.

ALTER TABLE vessel_visit ADD COLUMN planned_service_min INT NULL;
