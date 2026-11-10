-- A14: a file-triggered PIN reset must stay visible ("PIN was reset on <date>") until a new PIN is set,
-- including across restarts, so the moment of the reset is persisted rather than held in memory.
ALTER TABLE settings ADD COLUMN pin_reset_at TIMESTAMP WITH TIME ZONE NULL;
