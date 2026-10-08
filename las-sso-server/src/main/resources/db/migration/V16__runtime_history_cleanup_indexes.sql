CREATE INDEX ix_refresh_history_authorization ON oauth2_refresh_token_history (authorization_id);
CREATE INDEX ix_refresh_history_reclaimed_expiry ON oauth2_refresh_token_history (expires_at, revoked_at, token_hash)
    WHERE status = 'REVOKED';
CREATE INDEX ix_user_sessions_expiry ON user_sessions ((LEAST(idle_expires_at, absolute_expires_at)), id)
    WHERE status = 'ACTIVE';
CREATE INDEX ix_user_sessions_terminal ON user_sessions (revoked_at, id)
    WHERE status IN ('REVOKED', 'EXPIRED');
CREATE INDEX ix_authorization_token_expiry ON oauth2_authorization ((GREATEST(authorization_code_expires_at, access_token_expires_at,
    oidc_id_token_expires_at, refresh_token_expires_at, user_code_expires_at, device_code_expires_at)), id);
CREATE INDEX ix_recovery_codes_terminal ON recovery_codes ((GREATEST(used_at, revoked_at)), id)
    WHERE used_at IS NOT NULL OR revoked_at IS NOT NULL;
CREATE INDEX ix_admin_recovery_terminal ON admin_recovery_credentials ((GREATEST(used_at, revoked_at, expires_at)), id)
    WHERE used_at IS NOT NULL OR revoked_at IS NOT NULL;
CREATE INDEX ix_outbox_success_retention ON outbox_events (processed_at, id) WHERE processed_at IS NOT NULL;
CREATE INDEX ix_outbox_live_snapshot ON outbox_events (event_type, created_at) WHERE processed_at IS NULL;
