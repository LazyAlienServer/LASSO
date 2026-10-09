-- Run with the database owner after every successful offline migration, before Web starts.
-- Default application grants cover new domain tables; Flyway history is migration-owner-only.
REVOKE ALL ON TABLE public.flyway_schema_history FROM las_sso_runtime;
GRANT SELECT ON TABLE public.flyway_schema_history TO las_sso_runtime;
