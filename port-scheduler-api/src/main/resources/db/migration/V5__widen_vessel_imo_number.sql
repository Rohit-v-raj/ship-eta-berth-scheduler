-- IMO ship identification numbers are seven digits today, but the IMO has said
-- future numbers may be extended to eight, and the DCSA Port Call standard
-- already allows eight. Widen the column so such a number is not refused.

ALTER TABLE vessel MODIFY imo_number VARCHAR(8) NULL;
