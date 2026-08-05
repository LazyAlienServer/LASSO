CREATE TYPE account_status AS ENUM ('ACTIVE', 'FROZEN', 'DELETED');
CREATE TYPE credential_state AS ENUM ('ACTIVE', 'PASSWORD_CHANGE_REQUIRED', 'DISABLED');
CREATE TYPE session_status AS ENUM ('ACTIVE', 'REVOKED', 'EXPIRED');
CREATE TYPE deletion_status AS ENUM ('NONE', 'PENDING', 'HELD', 'CANCELLED', 'EXECUTED');

CREATE TABLE users (
    id uuid PRIMARY KEY,
    display_name varchar(64) NOT NULL,
    avatar_resource_id varchar(128),
    account_status account_status NOT NULL DEFAULT 'ACTIVE',
    is_sso_admin boolean NOT NULL DEFAULT false,
    profile_version bigint NOT NULL DEFAULT 1 CHECK (profile_version > 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    frozen_at timestamptz,
    deleted_at timestamptz,
    CONSTRAINT ck_users_deleted_at CHECK (
        (account_status = 'DELETED' AND deleted_at IS NOT NULL)
        OR account_status <> 'DELETED'
    )
);

CREATE UNIQUE INDEX uq_users_single_sso_admin
    ON users ((is_sso_admin))
    WHERE is_sso_admin = true;

CREATE TABLE usernames (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id),
    username varchar(24) COLLATE "C" NOT NULL,
    valid_from timestamptz NOT NULL,
    valid_to timestamptz,
    changed_by_user_id uuid NOT NULL REFERENCES users(id),
    change_reason varchar(64) NOT NULL,
    CONSTRAINT ck_usernames_format CHECK (username ~ '^[A-Za-z0-9_]{3,24}$'),
    CONSTRAINT ck_usernames_period CHECK (valid_to IS NULL OR valid_to > valid_from)
);

CREATE UNIQUE INDEX uq_usernames_exact_history ON usernames (username);
CREATE UNIQUE INDEX uq_usernames_one_current_per_user
    ON usernames (user_id)
    WHERE valid_to IS NULL;

CREATE TABLE password_credentials (
    user_id uuid PRIMARY KEY REFERENCES users(id),
    password_hash text NOT NULL,
    state credential_state NOT NULL DEFAULT 'ACTIVE',
    credentials_version bigint NOT NULL DEFAULT 1 CHECK (credentials_version > 0),
    password_changed_at timestamptz NOT NULL,
    temporary_expires_at timestamptz,
    failed_attempts integer NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    locked_until timestamptz
);

CREATE TABLE recovery_codes (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id),
    code_hash bytea NOT NULL,
    generation integer NOT NULL CHECK (generation > 0),
    created_at timestamptz NOT NULL,
    used_at timestamptz,
    revoked_at timestamptz
);

CREATE INDEX ix_recovery_codes_user_generation
    ON recovery_codes (user_id, generation);

CREATE TABLE admin_recovery_credentials (
    id uuid PRIMARY KEY,
    target_user_id uuid NOT NULL REFERENCES users(id),
    issued_by_user_id uuid NOT NULL REFERENCES users(id),
    credential_hash bytea NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    revoked_at timestamptz,
    reason text NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE TABLE user_sessions (
    id uuid PRIMARY KEY,
    sid varchar(128) NOT NULL UNIQUE,
    user_id uuid NOT NULL REFERENCES users(id),
    status session_status NOT NULL DEFAULT 'ACTIVE',
    remembered boolean NOT NULL DEFAULT false,
    admin_session boolean NOT NULL DEFAULT false,
    credentials_version bigint NOT NULL CHECK (credentials_version > 0),
    security_epoch varchar(64) NOT NULL,
    created_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    idle_expires_at timestamptz NOT NULL,
    absolute_expires_at timestamptz NOT NULL,
    last_password_auth_at timestamptz NOT NULL,
    revoked_at timestamptz,
    revoke_reason varchar(64),
    ip_prefix_hash bytea,
    user_agent_summary varchar(256),
    CONSTRAINT ck_user_sessions_expiry CHECK (
        idle_expires_at > created_at AND absolute_expires_at > created_at
    )
);

CREATE INDEX ix_user_sessions_active_user
    ON user_sessions (user_id)
    WHERE status = 'ACTIVE';

CREATE TABLE account_deletion_requests (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES users(id),
    status deletion_status NOT NULL,
    requested_at timestamptz NOT NULL,
    execute_after timestamptz NOT NULL,
    cancelled_at timestamptz,
    executed_at timestamptz,
    hold_until timestamptz,
    held_by_user_id uuid REFERENCES users(id),
    hold_reason text,
    CONSTRAINT ck_deletion_execute_after CHECK (execute_after > requested_at)
);

CREATE UNIQUE INDEX uq_deletion_request_active_user
    ON account_deletion_requests (user_id)
    WHERE status IN ('PENDING', 'HELD');

CREATE TABLE admin_audit_events (
    id uuid PRIMARY KEY,
    occurred_at timestamptz NOT NULL,
    actor_user_id uuid,
    actor_session_id uuid,
    action varchar(128) NOT NULL,
    target_type varchar(64) NOT NULL,
    target_id varchar(200) NOT NULL,
    request_id uuid NOT NULL,
    source_ip_prefix_hash bytea,
    result varchar(32) NOT NULL,
    reason text,
    details_json jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX ix_admin_audit_events_occurred_at
    ON admin_audit_events (occurred_at DESC);

CREATE TABLE security_events (
    id uuid PRIMARY KEY,
    occurred_at timestamptz NOT NULL,
    user_id uuid,
    event_type varchar(128) NOT NULL,
    severity varchar(32) NOT NULL,
    request_id uuid NOT NULL,
    source_ip_prefix_hash bytea,
    client_id varchar(100),
    details_json jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX ix_security_events_occurred_at
    ON security_events (occurred_at DESC);

CREATE TABLE outbox_events (
    id uuid PRIMARY KEY,
    aggregate_type varchar(64) NOT NULL,
    aggregate_id varchar(200) NOT NULL,
    event_type varchar(128) NOT NULL,
    payload_json jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    available_at timestamptz NOT NULL,
    processed_at timestamptz,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_error_code varchar(128)
);

CREATE INDEX ix_outbox_events_pending
    ON outbox_events (available_at)
    WHERE processed_at IS NULL;

CREATE TABLE system_security_state (
    id smallint PRIMARY KEY CHECK (id = 1),
    security_epoch varchar(64) NOT NULL,
    updated_at timestamptz NOT NULL,
    change_reason varchar(128) NOT NULL
);

INSERT INTO system_security_state (id, security_epoch, updated_at, change_reason)
VALUES (1, gen_random_uuid()::text, CURRENT_TIMESTAMP, 'INITIAL_SCHEMA');
