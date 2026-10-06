ALTER TABLE users
    ADD COLUMN service_access_state_version bigint NOT NULL DEFAULT 1
        CHECK (service_access_state_version > 0),
    ADD COLUMN service_access_changed_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP;
