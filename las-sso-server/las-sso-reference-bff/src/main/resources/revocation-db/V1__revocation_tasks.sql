CREATE SCHEMA IF NOT EXISTS reference_revocation;

CREATE TABLE reference_revocation.tasks (
    id UUID PRIMARY KEY,
    registration_id TEXT NOT NULL,
    issuer TEXT NOT NULL,
    client_id TEXT NOT NULL,
    revocation_uri TEXT NOT NULL,
    token_type_hint TEXT NOT NULL CHECK (token_type_hint IN ('refresh_token', 'access_token')),
    key_id TEXT NOT NULL,
    nonce BYTEA,
    ciphertext BYTEA,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 8),
    claim_token UUID,
    lease_until TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    dead_lettered_at TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    CHECK ((claim_token IS NULL) = (lease_until IS NULL)),
    CHECK (completed_at IS NULL OR dead_lettered_at IS NULL),
    CHECK ((completed_at IS NULL AND nonce IS NOT NULL AND octet_length(nonce) = 12
             AND ciphertext IS NOT NULL AND octet_length(ciphertext) >= 16)
        OR (completed_at IS NOT NULL AND nonce IS NULL AND ciphertext IS NULL)),
    CHECK ((completed_at IS NULL AND dead_lettered_at IS NULL) OR claim_token IS NULL)
);

CREATE INDEX tasks_pending_idx ON reference_revocation.tasks (available_at, created_at)
    WHERE completed_at IS NULL AND dead_lettered_at IS NULL;
