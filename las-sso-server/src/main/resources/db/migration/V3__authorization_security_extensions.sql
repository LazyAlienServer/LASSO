CREATE TYPE registered_client_type AS ENUM ('FIRST_PARTY', 'REVIEWED_PARTNER');
CREATE TYPE registered_client_status AS ENUM ('ACTIVE', 'DISABLED');

CREATE TABLE registered_client_metadata (
    registered_client_id varchar(100) PRIMARY KEY REFERENCES oauth2_registered_client(id),
    client_type registered_client_type NOT NULL,
    status registered_client_status NOT NULL DEFAULT 'ACTIVE',
    config_version bigint NOT NULL DEFAULT 1 CHECK (config_version > 0),
    resource_audiences text[] NOT NULL,
    require_pkce boolean NOT NULL DEFAULT true,
    updated_at timestamptz NOT NULL
);

CREATE TABLE registered_client_backchannel_logout_uris (
    registered_client_id varchar(100) PRIMARY KEY REFERENCES oauth2_registered_client(id),
    uri text NOT NULL,
    session_required boolean NOT NULL DEFAULT true,
    CONSTRAINT ck_backchannel_uri_absolute_https_or_loopback CHECK (
        uri ~ '^https://'
        OR uri ~ '^http://(localhost|127[.]0[.]0[.]1)(:[0-9]+)?/'
    )
);

CREATE TABLE oauth2_authorization_security (
    authorization_id varchar(100) PRIMARY KEY REFERENCES oauth2_authorization(id) ON DELETE CASCADE,
    credentials_version bigint NOT NULL CHECK (credentials_version > 0),
    security_epoch varchar(64) NOT NULL,
    client_config_version bigint NOT NULL CHECK (client_config_version > 0),
    sid varchar(128),
    token_family_id uuid,
    family_created_at timestamptz,
    family_absolute_expires_at timestamptz,
    CONSTRAINT ck_token_family_period CHECK (
        family_absolute_expires_at IS NULL
        OR (family_created_at IS NOT NULL AND family_absolute_expires_at > family_created_at)
    )
);

CREATE INDEX ix_authorization_security_sid
    ON oauth2_authorization_security (sid)
    WHERE sid IS NOT NULL;
