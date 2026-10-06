CREATE TABLE oauth2_id_token_hint_history (
    token_hash bytea PRIMARY KEY,
    authorization_id varchar(100) NOT NULL REFERENCES oauth2_authorization(id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL
);

CREATE INDEX ix_id_token_hint_history_authorization
    ON oauth2_id_token_hint_history (authorization_id);
