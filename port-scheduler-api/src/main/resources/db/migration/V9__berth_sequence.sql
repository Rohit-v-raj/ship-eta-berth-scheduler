-- A berth holds a sequence of bookings across the planning week, not one at a
-- time. V3 refused a second open allocation on a berth; that rule now covers
-- only the vessel alongside, so a berth may have one ACTIVE allocation and any
-- number of PLANNED ones. That the planned windows do not overlap is checked
-- by the service, under the berth row lock, since MySQL cannot refuse
-- overlapping ranges itself.

ALTER TABLE berth_allocation
    DROP INDEX uq_alloc_one_open_per_berth,
    DROP COLUMN open_berth_id;

ALTER TABLE berth_allocation
    ADD COLUMN active_berth_id BIGINT GENERATED ALWAYS AS
        (CASE WHEN status = 'ACTIVE' THEN berth_id END) VIRTUAL,
    ADD UNIQUE KEY uq_alloc_one_active_per_berth (active_berth_id);
