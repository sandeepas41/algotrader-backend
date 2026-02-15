-- Remove strategy_id from positions table.
-- Position-strategy linking is now managed solely through strategy_legs.position_id.
ALTER TABLE positions DROP COLUMN IF EXISTS strategy_id;
