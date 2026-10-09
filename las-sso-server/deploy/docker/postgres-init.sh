#!/bin/sh
set -eu
# Runs only on an empty volume. Passwords enter this one psql process from protected files.
runtime_password=$(cat /run/secrets/db_runtime_password)
migrator_password=$(cat /run/secrets/db_migrator_password)
backup_password=$(cat /run/secrets/db_backup_password)
admin_password=$(cat /run/secrets/db_admin_password)
for value in "$admin_password" "$runtime_password" "$migrator_password" "$backup_password"; do
    if [ "${#value}" -lt 12 ]; then
        echo 'Database passwords require independent high-entropy values of at least 12 bytes.' >&2
        exit 1
    fi
done
if [ "$admin_password" = "$runtime_password" ] || [ "$admin_password" = "$migrator_password" ] \
    || [ "$admin_password" = "$backup_password" ] || [ "$runtime_password" = "$migrator_password" ] \
    || [ "$runtime_password" = "$backup_password" ] || [ "$migrator_password" = "$backup_password" ]; then
    echo 'Database role passwords must be independent.' >&2
    exit 1
fi
unset admin_password value
export runtime_password migrator_password backup_password
if ! psql -X --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" -v ON_ERROR_STOP=1 >/dev/null 2>&1 <<'SQL'
SET log_statement = 'none';
SET log_min_error_statement = 'panic';
\getenv runtime_password runtime_password
\getenv migrator_password migrator_password
\getenv backup_password backup_password
SELECT format('CREATE ROLE las_sso_runtime LOGIN PASSWORD %L', :'runtime_password') \gexec
SELECT format('CREATE ROLE las_sso_migrator LOGIN PASSWORD %L', :'migrator_password') \gexec
SELECT format('CREATE ROLE las_sso_backup LOGIN PASSWORD %L', :'backup_password') \gexec
REVOKE CONNECT, TEMPORARY ON DATABASE las_sso FROM PUBLIC;
GRANT CONNECT ON DATABASE las_sso TO las_sso_runtime, las_sso_migrator, las_sso_backup;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO las_sso_migrator;
GRANT USAGE ON SCHEMA public TO las_sso_runtime, las_sso_backup;
ALTER DEFAULT PRIVILEGES FOR ROLE las_sso_migrator IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO las_sso_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE las_sso_migrator IN SCHEMA public
    GRANT SELECT ON TABLES TO las_sso_backup;
ALTER DEFAULT PRIVILEGES FOR ROLE las_sso_migrator IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO las_sso_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE las_sso_migrator IN SCHEMA public
    GRANT SELECT ON SEQUENCES TO las_sso_backup;
SQL
then
    echo 'Database role initialization failed; investigate the isolated target before retrying.' >&2
    exit 1
fi
unset runtime_password migrator_password backup_password
echo 'SSO migration, runtime and backup roles initialized.'
