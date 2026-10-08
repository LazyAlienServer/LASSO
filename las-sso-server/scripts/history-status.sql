-- Read-only operator report. psql -v ON_ERROR_STOP=1 -f history-status.sql
-- Supply actual configured retention days when overridden; never pass DB passwords as arguments.
\if :{?session_days}
\else
\set session_days 90
\endif
\if :{?credential_days}
\else
\set credential_days 90
\endif
\if :{?authorization_days}
\else
\set authorization_days 30
\endif
\if :{?refresh_days}
\else
\set refresh_days 30
\endif
\if :{?outbox_days}
\else
\set outbox_days 30
\endif
BEGIN READ ONLY;
SET LOCAL statement_timeout = '10s';
SET LOCAL lock_timeout = '500ms';

SELECT 'user_sessions' AS resource, count(*) AS rows,
       count(*) FILTER (WHERE status = 'ACTIVE' AND LEAST(idle_expires_at, absolute_expires_at) <= CURRENT_TIMESTAMP) AS expired_unvisited,
       count(*) FILTER (WHERE status IN ('REVOKED', 'EXPIRED') AND revoked_at < CURRENT_TIMESTAMP - :'session_days'::integer * INTERVAL '1 day') AS aged_terminal
FROM user_sessions;

SELECT 'authorizations' AS resource, count(*) AS rows,
       count(*) FILTER (WHERE GREATEST(a.authorization_code_expires_at, a.access_token_expires_at,
           a.oidc_id_token_expires_at, a.refresh_token_expires_at, a.user_code_expires_at,
           a.device_code_expires_at, s.family_absolute_expires_at) < CURRENT_TIMESTAMP - :'authorization_days'::integer * INTERVAL '1 day') AS aged_deadline_candidates
FROM oauth2_authorization a LEFT JOIN oauth2_authorization_security s ON s.authorization_id = a.id;
-- Candidate count is an estimate: unknown expiry, parent/child locks and family protection still exclude rows.

SELECT 'refresh_history' AS resource, count(*) AS rows,
       count(*) FILTER (WHERE h.status = 'REVOKED' AND h.expires_at < CURRENT_TIMESTAMP - :'refresh_days'::integer * INTERVAL '1 day'
           AND h.revoked_at < CURRENT_TIMESTAMP - :'refresh_days'::integer * INTERVAL '1 day'
           AND NOT EXISTS (SELECT 1 FROM oauth2_authorization a WHERE a.id = h.authorization_id)) AS eligible_orphans
FROM oauth2_refresh_token_history h;
SELECT 'id_token_hints' AS resource, count(*) AS rows FROM oauth2_id_token_hint_history;
SELECT max(hints) AS largest_hint_fanout FROM (SELECT count(*) AS hints FROM oauth2_id_token_hint_history GROUP BY authorization_id) grouped;
SELECT max(history_rows) AS largest_rt_fanout FROM (SELECT count(*) AS history_rows FROM oauth2_refresh_token_history GROUP BY authorization_id) grouped;

SELECT 'recovery_codes' AS resource, count(*) AS rows,
       count(*) FILTER (WHERE GREATEST(used_at, revoked_at) < CURRENT_TIMESTAMP - :'credential_days'::integer * INTERVAL '1 day') AS aged_terminal
FROM recovery_codes;
SELECT 'manual_credentials' AS resource, count(*) AS rows,
       count(*) FILTER (WHERE (used_at IS NOT NULL OR revoked_at IS NOT NULL) AND GREATEST(used_at, revoked_at, expires_at) < CURRENT_TIMESTAMP - :'credential_days'::integer * INTERVAL '1 day') AS aged_terminal
FROM admin_recovery_credentials;
SELECT 'outbox' AS resource, count(*) AS rows,
       count(*) FILTER (WHERE processed_at IS NULL AND dead_lettered_at IS NULL) AS pending,
       count(*) FILTER (WHERE dead_lettered_at IS NOT NULL) AS dead,
       count(*) FILTER (WHERE processed_at < CURRENT_TIMESTAMP - :'outbox_days'::integer * INTERVAL '1 day' AND dead_lettered_at IS NULL AND claim_token IS NULL AND claimed_at IS NULL
           AND event_type IN ('BACKCHANNEL_LOGOUT_REQUESTED', 'GLOBAL_LOGOUT_REQUESTED')) AS aged_success
FROM outbox_events;

SELECT relname, n_live_tup, n_dead_tup, last_autovacuum, last_autoanalyze,
       pg_total_relation_size(relid) AS relation_and_indexes_bytes
FROM pg_stat_user_tables ORDER BY pg_total_relation_size(relid) DESC;
COMMIT;
