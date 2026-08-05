ALTER TABLE outbox_events
    ADD COLUMN claim_token uuid,
    ADD COLUMN claimed_at timestamptz;

CREATE INDEX ix_outbox_events_reclaimable
    ON outbox_events (available_at, claimed_at)
    WHERE processed_at IS NULL;
