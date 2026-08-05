CREATE TABLE rate_limit_buckets (
    scope varchar(64) NOT NULL,
    key_hash bytea NOT NULL,
    window_start timestamptz NOT NULL,
    window_expires_at timestamptz NOT NULL,
    attempt_count integer NOT NULL CHECK (attempt_count > 0),
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (scope, key_hash, window_start),
    CONSTRAINT ck_rate_limit_window CHECK (window_expires_at > window_start)
);

CREATE INDEX ix_rate_limit_buckets_expiry ON rate_limit_buckets (window_expires_at);

CREATE TABLE captcha_challenges (
    id uuid PRIMARY KEY,
    purpose varchar(32) NOT NULL,
    challenge_digest bytea NOT NULL,
    ip_prefix_hash bytea,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    CONSTRAINT ck_captcha_challenge_period CHECK (expires_at > created_at),
    CONSTRAINT ck_captcha_challenge_purpose CHECK (
        purpose IN ('REGISTER', 'LOGIN_RISK', 'RECOVERY')
    )
);

CREATE INDEX ix_captcha_challenges_expiry ON captcha_challenges (expires_at);

CREATE TABLE recovery_sessions (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id),
    session_token_hash bytea NOT NULL UNIQUE,
    credential_type varchar(32) NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    failed_attempts integer NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    CONSTRAINT ck_recovery_session_period CHECK (expires_at > created_at),
    CONSTRAINT ck_recovery_session_credential_type CHECK (
        credential_type IN ('RECOVERY_CODE', 'ADMIN_ISSUED')
    )
);

CREATE INDEX ix_recovery_sessions_expiry ON recovery_sessions (expires_at);
