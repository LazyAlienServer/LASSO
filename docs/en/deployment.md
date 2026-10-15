# Deploy with one external Nginx

[Index](README.md) · [中文](../zh-CN/deployment.md) · [Configuration](configuration.md)

**Default:** Linux Docker host, one SSO/PostgreSQL Compose and host-installed Nginx. Nginx terminates HTTPS and reaches the loopback application port. Management9091/database are unpublished. Official public domain/CA/renewal is chosen at formal launch; software exercises use an explicitly trusted isolated HTTPS fixture.

## 1. Verify and build/load images

At the extracted package root, with Python3.10+, Docker/Compose and Java21 keytool:

```sh
python3 scripts/verify-package.py
```

Build the exact application from the packaged manifest (images are not in the ZIP):

```sh
set -euo pipefail
WORK="/var/tmp/las-sso-{{VERSION}}"
mkdir -p "$WORK"
HASH="$(python3 -c 'import json; print(json.load(open("provenance/release-manifest.json"))["artifacts"]["server"]["sha256"])')"
python3 scripts/build_docker_image.py --jar application/las-sso-server-{{VERSION}}.jar \
  --expected-sha256 "$HASH" --version {{VERSION}} --tag las-sso:{{VERSION}} \
  --release-manifest provenance/release-manifest.json --output-dir "$WORK/server"
python3 scripts/build_docker_infrastructure.py --postgres-tag las-sso-postgres:{{VERSION}} --output-dir "$WORK/database"
docker image inspect las-sso:{{VERSION}} --format '{{.Id}}'
docker image inspect las-sso-postgres:{{VERSION}} --format '{{.Id}}'
```

Retain receipts and scan exact image/host Nginx inputs. Transfer with save/load if needed and verify IDs. A rebuild is new bytes.

## 2. Supply external configuration

Follow [configuration](configuration.md) for the nine files, signing key and ACLs. Copy host-edge env/server.properties examples externally. Fill exact IDs, resource limits and canonical HTTPS issuer. Retain original project/volume for existing installations.

```sh
dc() { docker compose --env-file /etc/las-sso/deployment.env -f "$PWD/deploy/docker/compose.host-nginx.yml" "$@"; }
python3 scripts/verify_docker_deployment.py --env-file /etc/las-sso/deployment.env \
  --compose deploy/docker/compose.host-nginx.yml --topology host-nginx
```

Expected exit0, only `127.0.0.1:<APP_PORT>→9000` publication. Use this helper for subsequent normal/maintenance commands; it is a standalone file.

## 3. Migrate and initialize

```sh
set -euo pipefail
dc up -d --wait --wait-timeout 120 postgres
dc --profile maintenance run --rm -T maintenance database-migrate
dc exec -T postgres psql -X -U postgres -d las_sso -v ON_ERROR_STOP=1 < deploy/docker/postgres-runtime-permissions.sql
```

For a new database, bootstrap in a real terminal with hidden input:

```sh
dc --profile maintenance run --rm --no-TTY=false maintenance admin-bootstrap --username=InitialAdmin
```

Expected migration exit0 and sole administrator with24hour temporary password. Already initialized databases use [reset](operations.md), not another bootstrap. Existing volumes do not rerun PG role/password initialization.

## 4. Start SSO and host edge

```sh
set -euo pipefail
dc up -d --wait --wait-timeout 120 sso
dc exec -T sso curl --fail --silent --show-error --max-time 45 http://127.0.0.1:9091/actuator/health/readiness
curl --silent --show-error --output /dev/null --write-out '%{http_code}\n' http://127.0.0.1:19000/login
```

Readiness UP and login GET200; replace19000 if APP_PORT differs. Install external copies of `deploy/nginx/sso-container-site.conf.example` and `las-forwarding.conf`. Replace all host/certificate/listen/upstream values, install the include at its referenced location, then:

```sh
set -euo pipefail
nginx -t
systemctl reload nginx
```

The include overwrites Forwarded/host/protocol/port/client IP. Preserve full `/api/v1` and OIDC paths, reject this site's wrong Host and public actuator. Do not use another container's localhost as this host upstream.

## 5. Verify and open

1. HTTPS discovery issuer matches, HTTP redirects canonical, JWKS is public and Cookies are Secure/host-only. Wrong Host421/public actuator404; external app/DB/management unreachable.
2. U03 changes temporary password; fresh admin login/U07 saves recovery codes. Verify users/admin reads and expected CSRF/origin refusal.
3. Verify private monitoring, notifications, tested off-host recovery and reviewed capacity/security. [Troubleshooting](troubleshooting.md) handles current failures.

## Alternative layouts

- Existing bundled install: stop only its old proxy with the old helper, retain project/DB volume/key/secrets, select host-edge helper and recreate SSO. Avoid broad orphan/volume deletion.
- Bundled Nginx: select compose.yml and bundled env, build a proxy image with `--proxy-tag`, supply TLS/proxy limits. Stop/start proxy alongside SSO. Keep this helper separate.
- Host JAR: external host properties, offline production migration/bootstrap/reset with Web NONE/jobs disabled, then runtime-role Web. `deploy/systemd/las-sso.service` and `sso-site.conf.example` are host templates; management is loopback. Validate the actual Linux process manager/permissions.

Upgrade is in [operations](operations.md); restore is in [recovery](recovery.md). Start a failed gate closed and diagnose its current exception; do not delete the database to retry.
