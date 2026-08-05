ALTER TABLE password_credentials
    ADD COLUMN last_failed_at timestamptz;

CREATE INDEX ix_password_credentials_locked_until
    ON password_credentials (locked_until)
    WHERE locked_until IS NOT NULL;
