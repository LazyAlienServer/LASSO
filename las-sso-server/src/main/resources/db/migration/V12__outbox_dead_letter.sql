ALTER TABLE outbox_events
    ADD COLUMN dead_lettered_at timestamptz;

CREATE INDEX ix_outbox_events_dead_lettered
    ON outbox_events (dead_lettered_at)
    WHERE dead_lettered_at IS NOT NULL;
