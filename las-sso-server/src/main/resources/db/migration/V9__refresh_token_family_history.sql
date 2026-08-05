CREATE TYPE refresh_token_status AS ENUM ('CURRENT', 'ROTATED', 'REVOKED');

CREATE TABLE oauth2_refresh_token_history (
    token_hash bytea PRIMARY KEY,
    token_family_id uuid NOT NULL,
    authorization_id varchar(100) NOT NULL,
    status refresh_token_status NOT NULL,
    issued_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    rotated_at timestamptz,
    revoked_at timestamptz,
    CONSTRAINT ck_refresh_token_history_period CHECK (expires_at > issued_at),
    CONSTRAINT ck_refresh_token_history_status_times CHECK (
        (status = 'CURRENT' AND rotated_at IS NULL AND revoked_at IS NULL)
        OR (status = 'ROTATED' AND rotated_at IS NOT NULL AND revoked_at IS NULL)
        OR (status = 'REVOKED' AND revoked_at IS NOT NULL)
    )
);

CREATE INDEX ix_refresh_token_history_family
    ON oauth2_refresh_token_history (token_family_id);

CREATE UNIQUE INDEX uq_refresh_token_history_current_family
    ON oauth2_refresh_token_history (token_family_id)
    WHERE status = 'CURRENT';

CREATE OR REPLACE FUNCTION revoke_refresh_token_family_on_authorization_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE oauth2_refresh_token_history
       SET status = 'REVOKED',
           revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP)
     WHERE authorization_id = OLD.id
       AND status <> 'REVOKED';
    RETURN OLD;
END;
$$;

CREATE TRIGGER trg_revoke_refresh_token_family_on_authorization_delete
BEFORE DELETE ON oauth2_authorization
FOR EACH ROW
EXECUTE FUNCTION revoke_refresh_token_family_on_authorization_delete();
