ALTER TABLE reference_revocation.tasks ADD COLUMN replay_count INTEGER NOT NULL DEFAULT 0 CHECK (replay_count >= 0);

CREATE TABLE reference_revocation.key_state (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    key_id VARCHAR(64) NOT NULL,
    key_verifier BYTEA NOT NULL CHECK (octet_length(key_verifier) = 32),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE reference_revocation.operator_audit (
    id UUID PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    action VARCHAR(32) NOT NULL CHECK (action IN ('REPLAY', 'ROTATE_KEY', 'PURGE_COMPLETED')),
    task_id UUID,
    actor VARCHAR(64) NOT NULL,
    reason VARCHAR(128) NOT NULL,
    replay_count INTEGER,
    previous_attempts INTEGER,
    affected_count INTEGER NOT NULL,
    old_key_id VARCHAR(64),
    new_key_id VARCHAR(64)
);

CREATE INDEX tasks_completed_retention_idx ON reference_revocation.tasks (completed_at, id)
    WHERE completed_at IS NOT NULL;

CREATE FUNCTION reference_revocation.enforce_active_key_id() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    active_key_id VARCHAR(64);
BEGIN
    PERFORM pg_advisory_xact_lock_shared(5492486017847824977);
    SELECT key_id INTO active_key_id FROM reference_revocation.key_state WHERE id = 1 FOR SHARE;
    IF active_key_id IS NULL OR NEW.key_id <> active_key_id THEN
        RAISE EXCEPTION 'reference revocation key is not active';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER tasks_active_key_id BEFORE INSERT ON reference_revocation.tasks
    FOR EACH ROW EXECUTE FUNCTION reference_revocation.enforce_active_key_id();
