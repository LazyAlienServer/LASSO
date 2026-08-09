CREATE TABLE operation_idempotency (
    actor_user_id uuid NOT NULL REFERENCES users(id),
    operation varchar(96) NOT NULL,
    idempotency_key varchar(128) NOT NULL,
    request_hash bytea NOT NULL,
    result_ref varchar(256),
    result_time timestamptz,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (actor_user_id, operation, idempotency_key),
    CONSTRAINT ck_operation_idempotency_period CHECK (expires_at > created_at)
);

CREATE INDEX ix_operation_idempotency_expiry
    ON operation_idempotency (expires_at);
