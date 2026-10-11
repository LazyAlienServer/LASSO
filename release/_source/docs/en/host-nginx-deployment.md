# Docker with one external host Nginx — {{VERSION}}

[Index](README.md) · [中文](../zh-CN/host-nginx-deployment.md) · [Bundled proxy alternative](docker-deployment.md) · [Monitoring](monitoring.md) · [Operations](operations.md)

**Selected topology:** Nginx installed on the Docker host, one SSO container and separate PostgreSQL. The host edge owns HTTPS; SSO is published only on `127.0.0.1:19000` in this example. Management 9091 and PostgreSQL are not published. Another Nginx container or remote host needs a different connection/trust design.

```text
Browser → host Nginx HTTPS → 127.0.0.1:19000 → SSO:9000 → PostgreSQL
                                            └→ private 9091 ← collector
```

**Working directory:** Extracted package root, Bash. Formal public domain/CA/renewal are configured at target launch. Until then software checks use an isolated, named HTTPS fixture; the production profile still requires a valid HTTPS issuer. Do not put public examples or a loopback HTTP URL into the issuer. All paths are illustrative; preserve the package and use external configuration copies.

## N01 Verify software and prepare two images

**Prerequisites:** Docker/Compose, Python 3.10+, JDK21 keytool for signing material, host Nginx and a reviewed Linux owner/resource layout.

1. Check a trusted distribution checksum, then `python3 scripts/verify-package.py`; require exit 0 and exact version/source/artifact identity.
2. Build/load the exact SSO image using [Docker step 2.1](docker-deployment.md); retain its image-build receipt and immutable image ID.
3. Build/load PostgreSQL only; the host edge does not need the bundled proxy image:

```sh
set -euo pipefail
WORK="/var/tmp/las-sso-{{VERSION}}"
mkdir -p "$WORK"
python3 scripts/build_docker_infrastructure.py \
  --postgres-tag las-sso-postgres:{{VERSION}} \
  --output-dir "$WORK/infrastructure"
docker image inspect las-sso:{{VERSION}} --format '{{.Id}}'
docker image inspect las-sso-postgres:{{VERSION}} --format '{{.Id}}'
```

4. Retain receipts and scan the exact two images plus the actual host Nginx/OS. A rebuild is new bytes, not the old receipt.

**Expected:** Verified package, two identified images and host Nginx available. **Failure:** Repair source/image/version mismatch; do not relabel a dev/RC binary or bypass dirty-source checks.

## N02 Prepare external inputs, key and monitoring hash

**Prerequisites:** Private external directories/owner, chosen fixture or formal issuer, reviewed CPU/memory limits. For an existing installation retain its project name, database volume, key and peppers.

1. Copy `deploy/docker/server.properties.example` externally as server.properties and `deployment.host-nginx.env.example` as deployment.env. Use [Docker step 3](docker-deployment.md) for nine secret files, signing key and SSO/PostgreSQL file permissions. Host TLS files belong to the actual host Nginx readers, not container UID101.
2. Set the exact SSO/PostgreSQL image IDs, configuration/secret/key paths, finite limits, current signing alias and stable key ID. `LAS_SSO_APP_PORT=19000` selects a free host loopback port; host/issuer/HTTP/HTTPS ports describe the browser edge. No proxy image/TLS-directory/proxy limit is required by this Compose.
3. Key ID is 1–128 ASCII letters/digits/dot/underscore/hyphen; reject las-local/las-test prefixes and local-only/test-only/change-me/changeme markers. Alias matches keytool; ID is the public kid, not a password or path. See [startup errors](troubleshooting.md).
4. Monitoring secret contains `{bcrypt}` plus a bcrypt hash with cost 10–14, not plaintext or a complete htpasswd record. Follow [private generation](monitoring.md); keep the original password for the collector. Do not quote the hash or interpolate its dollar characters through shell arguments.
5. Define the same helper for normal and maintenance commands, then run the host-edge validator:

```sh
set -euo pipefail
dc() { docker compose --env-file /etc/las-sso/deployment.env -f "$PWD/deploy/docker/compose.host-nginx.yml" "$@"; }
python3 scripts/verify_docker_deployment.py \
  --env-file /etc/las-sso/deployment.env \
  --compose deploy/docker/compose.host-nginx.yml --topology host-nginx
```

**Expected:** Host-edge validation exits 0; SSO has one loopback application publication, no DB/management/maintenance publication. **Failure:** Check inherited shell overrides, kid and exact input paths; the validator does not verify actual secret contents or Nginx/TLS. Do not combine this standalone Compose with the bundled Compose.

## N03 Fresh database, offline migration and initial administrator

**Prerequisites:** N01/N02 pass; this is a new database. Existing usable administrator is not bootstrapped again.

1. Start PostgreSQL and wait for health. Run the exact same image in offline migration mode, then apply runtime Flyway-history read-only permissions:

```sh
set -euo pipefail
dc up -d --wait --wait-timeout 120 postgres
dc --profile maintenance run --rm -T maintenance database-migrate
dc exec -T postgres psql -X -U postgres -d las_sso -v ON_ERROR_STOP=1 < deploy/docker/postgres-runtime-permissions.sql
```

2. Require exit 0 after each command; verify V1–V16 and migration ownership. Existing-volume initialization does not rerun role/password creation.
3. In a real terminal bootstrap the sole administrator using hidden password input:

```sh
dc --profile maintenance run --rm --no-TTY=false maintenance admin-bootstrap --username=InitialAdmin
```

4. Record only administrator UUID/audit/time; retain the temporary credential privately. Start SSO and verify private readiness:

```sh
set -euo pipefail
dc up -d --wait --wait-timeout 120 sso
dc exec -T sso curl --fail --silent --show-error --max-time 45 http://127.0.0.1:9091/actuator/health/readiness
curl --silent --show-error --output /dev/null --write-out '%{http_code}\n' http://127.0.0.1:19000/login
```

**Expected:** PostgreSQL/SSO healthy, readiness UP and local login GET 200. **Failure:** Inspect bounded private SSO logs for the current gate/DB/key/monitoring error; healthy PostgreSQL alone is not usable SSO. Preserve existing data and avoid down --volumes.

## N04 Install the one host edge and verify forwarding

**Prerequisites:** Host Nginx, chosen HTTPS issuer/certificate and N03 health. Public certificate/DNS/renewal remain formal-launch work; an isolated fixture must be explicitly trusted for that fixture only.

1. Copy `deploy/nginx/sso-container-site.conf.example` to the existing host Nginx's loaded site directory. Replace every example hostname, certificate path and port, and match the upstream to LAS_SSO_APP_PORT. Copy `deploy/nginx/las-forwarding.conf` externally to `/etc/nginx/snippets/las-sso-forwarding.conf`.
2. Keep one canonical host and full account/API/OIDC paths. The include clears alternate forwarding headers and overwrites them with the host edge's scheme/port/client IP. For a nonstandard public TLS port update listen/redirect/issuer consistently. Do not add another proxy hop or trust client-provided X-Forwarded-For.
3. The host Nginx reads its own TLS material. Test the installed complete configuration, reload only after success (systemd example):

```sh
set -euo pipefail
nginx -t
systemctl reload nginx
```

4. Through the selected HTTPS host verify discovery issuer/JWKS, HTTP canonical redirect, unknown-host rejection for this site and public actuator 404. Confirm the host application binding is IPv4 loopback only and app/DB/management cannot be reached from an untrusted machine.
5. Browser U03 changes the temporary password, then fresh login/U07 preserves recovery codes. Verify A01/A10 reads, CSRF/origin refusal and spoofed forwarding not changing addresses. Retain safe results, no authentication captures.

**Expected:** One host edge serves the correct HTTPS site, login and protocol metadata; private boundaries hold. **Failure:** Diagnose host Nginx/listener/TLS/issuer/upstream, not disable TLS, Secure Cookie or CSRF. Other host sites keep their own configured default behavior.

## N05 Adapt an existing bundled install, restart and upgrade

**Prerequisites:** Tested backup, recorded current project/database/image/config and an authorized window. No new signing key, pepper, bootstrap or empty project is implied.

1. With the old helper stop the bundled proxy and close/drain that site's ingress. Keep the original LAS_SSO_PROJECT, PostgreSQL image/data volume and external settings. Change the helper to the standalone host file; use N02 validation and verify the rendered PostgreSQL volume remains `<project>_postgres_data`.
2. Start/recreate SSO with the host file to apply loopback publication. The unused proxy may remain stopped as an orphan; do not use broad remove-orphans/down --volumes cleanup. Only the explicitly identified old proxy is retired.
3. Ordinary restart uses `dc restart sso`, then readiness. An env/image/port change requires reviewed `dc up -d --no-deps --force-recreate sso`; restart does not load new environment. N04 controls host ingress separately.
4. Software upgrades follow O03 using this helper and `--topology host-nginx`; stop SSO/other writers after draining the host edge, migrate/apply permissions, then `dc up -d --wait --wait-timeout 120 sso` and verify. Do not run bundled `proxy` commands on this topology.
5. O02 reset and B01/B02 backup/restore use the same standalone target helper and stopped writers. Host certificate renewal uses `nginx -t` followed by the host's reload method, not `dc exec proxy`. Test backup/restore in a separate project and apply epoch/key/consumer barriers before cutover.

**Expected:** Same data/key/project, exact updated software and verified host ingress. **Failure:** Preserve backup/state and inspect actual migration commits. Binary rollback needs known schema compatibility; a failed target stays closed.

## N06 View metrics and finish acceptance

**Prerequisites:** Normal N03/N04 operation, independent collector password and selected monitoring platform.

1. A private terminal can inspect text metrics without publishing 9091; enter the collector password when curl prompts (omit `-T`):

```sh
dc exec sso curl --fail --silent --show-error --user las-sso-metrics http://127.0.0.1:9091/actuator/prometheus
```

2. For continuous collection, connect Prometheus to `<project>_monitoring`, target `sso:9091` and mount its protected original-password file. Do not use another container's 127.0.0.1; see M01–M04. Grafana displays data from Prometheus, not by opening SSO management publicly.
3. Verify scrape up=1, snapshot available/fresh, actual panel data and firing/resolved notifications. Rehearse independent backup/maintenance/resource limits and V01's candidate-specific supported paths.
4. At formal target launch supply the real public domain/CA/renewal, validate changed issuer/client settings and V02 before opening. Domain changes are identity/configuration changes, not a silent alias for fixture identity.

**Expected:** Private authenticated metrics and honest version/target acceptance records. **Failure:** 401 checks original password versus hash; stale snapshot checks DB/sampler. A fixture or text dump alone does not prove public launch, graphs or notification delivery.
