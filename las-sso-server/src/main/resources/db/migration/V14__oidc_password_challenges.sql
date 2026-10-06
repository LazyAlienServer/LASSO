CREATE TABLE oidc_password_challenges (
    id uuid PRIMARY KEY,
    expires_at timestamptz NOT NULL,
    completed_at timestamptz,
    consumed_at timestamptz,
    subject uuid,
    sid varchar(128),
    CONSTRAINT ck_oidc_password_challenge_completion CHECK (
        completed_at IS NULL OR (subject IS NOT NULL AND sid IS NOT NULL)
    ),
    CONSTRAINT ck_oidc_password_challenge_consumption CHECK (
        consumed_at IS NULL OR completed_at IS NOT NULL
    )
);

CREATE INDEX ix_oidc_password_challenges_expiry ON oidc_password_challenges (expires_at);
