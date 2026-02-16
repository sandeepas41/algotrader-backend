-- Change strategies.config from JSON to CLOB to prevent H2 double-encoding.
-- H2's JSON type wraps String values in extra quote layers on each read/write cycle,
-- corrupting polymorphic Jackson @type discriminators needed for strategy recovery.
ALTER TABLE strategies ALTER COLUMN config CLOB;
