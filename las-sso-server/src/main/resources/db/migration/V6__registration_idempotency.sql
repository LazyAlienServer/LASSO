CREATE TABLE registration_idempotency (
    idempotency_key uuid PRIMARY KEY,
    request_hash bytea NOT NULL,
    user_id uuid NOT NULL UNIQUE REFERENCES users(id),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    CONSTRAINT ck_registration_idempotency_period CHECK (expires_at > created_at)
);

CREATE INDEX ix_registration_idempotency_expiry
    ON registration_idempotency (expires_at);
