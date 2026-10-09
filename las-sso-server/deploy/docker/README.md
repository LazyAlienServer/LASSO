# Single-host Docker Compose deployment

[中文](README.zh-CN.md)

This deployment implements the selected first launch: the SSO account site, one SSO instance, PostgreSQL and same-host Nginx, without a CDN. LASLOG/AlienCommon and the reference BFF are registered and accepted separately when launched. Target domains, hardware, capacity, backup/RPO/RTO and notification channels must be supplied by the operator. Local fixture limits are not production sizing.

## 1. Immutable images and external inputs

Use Docker Engine/Compose on a reviewed Linux host. The local check used Linux/amd64 containers on Docker Desktop with Compose v5.1.2; that does not validate the target Linux distribution, firewall or other CPU architectures.

The server Dockerfile consumes an exact executable JAR, never builds source or copies a worktree into the image. From a clean repository, first produce strict release build evidence with `scripts/package_release.py build <new-version>`. Then run the following scripts from the repository root (all script paths below are root-relative):

```text
python scripts/build_docker_image.py --jar <exact-server.jar> --expected-sha256 <trusted-64-hex> --version <new-version> --tag <chosen-local-tag> --build-evidence <exact-build-evidence.json>
python scripts/build_docker_infrastructure.py --postgres-tag <chosen-db-tag> --proxy-tag <chosen-proxy-tag> --output-dir <external-existing-parent/new-receipts>
```

For an extracted immutable package, run `python scripts/verify-package.py` from its root, use the server path/hash/version from its manifest, and replace `--build-evidence` with `--release-manifest provenance/release-manifest.json --output-dir <external-existing-parent/new-image-receipts>`. Both infrastructure and server tools work without Git in a verified package. Keep staging/receipts outside the immutable package. `--allow-dirty-fixture` is explicitly unverified development evidence and cannot identify the JAR as a clean source commit.

PostgreSQL/Nginx use digest-pinned upstream bases and build-time Alpine security updates. PostgreSQL replaces the upstream Go-based `gosu` with Alpine `su-exec` at the original entrypoint path. The repositories are mutable: retain resulting image IDs, package lists, receipts and current scans; rebuilds are new bytes. Never update packages at container startup. Save/transfer exact images via controlled storage, verify archive hashes and inspect IDs after loading. Only immutable `sha256:<image-ID>` or `repository@sha256:<digest>` references go into `deployment.env`. Local tags are convenient build handles, not deployment identity; `pull_policy: never` requires the chosen images already loaded.

Copy `deployment.env.example` outside the source/package and fill every required value. It contains only non-secret inputs. For this root-path topology, `LAS_SSO_ISSUER` must equal `https://<LAS_SSO_HOST>[:external-port]`; do not set a servlet prefix. Use a distinct Compose project and secret/data locations for preproduction and production. Resource limits must be measured/reviewed, including host headroom for PostgreSQL, JVM native memory, proxy and collectors. Image EXPOSE metadata does not publish a host port.

## 2. Files, permissions and network

Create protected external configuration, secret, PKCS12 and TLS locations. Copy `server.properties.example` as `server.properties`. Do not substitute the host/systemd loopback configuration: container app9000 and management9091 listen inside the container on `0.0.0.0`, while network membership and absence of host ports restrict access.

| External secret filename | Consumer |
| --- | --- |
| `db-admin-password` | PostgreSQL initialization/admin custody only |
| `db-runtime-password` | SSO runtime database login |
| `db-migrator-password` | Offline migrations/maintenance |
| `db-backup-password` | Read-only backup login |
| `signing-password` | Persistent PKCS12, same private-key password |
| `recovery-pepper`, `transient-pepper`, `captcha-secret` | Three independent persistent high-entropy secrets |
| `monitoring-hash` | `{bcrypt}` cost10–14 hash, independent raw password held only by collector |

Generate independent values through a CSPRNG/protected secret mechanism: DB/key passwords at least12 UTF-8 bytes, peppers/CAPTCHA at least32; length is not entropy. No secrets in `deployment.env`, Docker build args, shell tracing, image, source, release receipt or command-line password options. The init script rejects short or reused DB-role passwords. Existing volumes do not rerun initialization, and changing a mounted password file does not rotate an existing PostgreSQL role.

Ordinary Compose secrets are read-only host-file mounts, not an encrypted secret vault; file ownership/modes remain the operator's responsibility. On Linux make app configuration/key/app secrets readable by container UID10001 only (for example UID10001 ownership, mode0400 files and protected parent directories); TLS key readable by UID101 only; database secret files readable by the PostgreSQL initialization/maintenance owner. Do not solve read errors with world-readable private keys. Verify actual container readability and unauthorized-host-user refusal. Protect Docker socket/group access as host-level administrative authority. Local Windows bind mounts cannot certify Linux UID/ACL behavior.

Nginx UID101 and SSO UID10001 run read-only with dropped capabilities, no-new-privileges and bounded `/tmp`; PostgreSQL's official initialization still needs root to initialize its volume and drops to its database user. Only proxy host80/443 are published (or reviewed explicit ports/interface). PostgreSQL has only an internal database network; maintenance only that network; proxy only application network; management is not proxied. Other containers on an SSO network are trusted infrastructure, not arbitrary workloads. Nginx clears alternate forwarding headers and overwrites host/proto/external port/client IP; a later CDN or multi-hop proxy needs separate trust design/acceptance.

Supply `tls/fullchain.pem` and `tls/privkey.pem` for the real hostname, public CA and a tested renewal/reload job. Unknown hosts return421, HTTP redirects308 to canonical HTTPS, `/actuator` and descendants return404, access logging is off to avoid authentication query/payload capture. Container proxy health uses a local certificate exception solely as a process/upstream check; it does not validate CA trust, hostname, expiration or renewal.

Validate before creating resources (use the actual compose path in the source tree or extracted package):

```text
python scripts/verify_docker_deployment.py --env-file <external/deployment.env> --compose <this-directory/compose.yml>
```

It verifies non-secret inputs, immutable image references, issuer/proxy agreement and rendered isolation/privilege boundaries. DNS, host firewall, CA, file custody, capacity and backups remain target checks. Keep inherited shell environment consistent with the chosen input file.

## 3. Empty database: migrate, bootstrap, then Web

Commands below run in this deployment directory; retain `--env-file` and `-f` on every invocation. Keep public ingress closed by target firewall/interface policy. Password operations require an actual interactive terminal; never pipe password input or record its transcript.

```sh
docker compose --env-file /protected/deployment.env -f compose.yml up -d postgres
docker compose --env-file /protected/deployment.env -f compose.yml --profile maintenance run --rm -T maintenance database-migrate
docker compose --env-file /protected/deployment.env -f compose.yml exec -T postgres psql -X -U postgres -d las_sso -v ON_ERROR_STOP=1 < postgres-runtime-permissions.sql
docker compose --env-file /protected/deployment.env -f compose.yml --profile maintenance run --rm --no-TTY=false maintenance admin-bootstrap --username=InitialAdmin
docker compose --env-file /protected/deployment.env -f compose.yml up -d sso proxy
```

`database-migrate` uses the embedded Flyway initializer, validates completion, starts no HTTP listener/jobs and needs neither administrator nor console. Production Web refuses an empty administrator table. Run `postgres-runtime-permissions.sql` after each successful offline migration: runtime may read Flyway history but cannot rewrite it. Migrator owns schema/objects; runtime has domain DML, no schema CREATE; backup has SELECT. Flyway validation remains enabled on Web startup; missing migrations cannot be applied by the runtime role.

`admin-bootstrap` may use `--user-id=<existing-UUID>` instead of username, never both. Temporary password is hidden-input, expires in24hours and requires first-login change. Confirm exactly one administrator; log in through reviewed TLS, change password, then generate/retain recovery codes privately. Clients may remain empty for an SSO-only account-site launch. Do not auto-register local/reference clients or reopen traffic just because container health is green.

PowerShell has no shell `<` redirection: send the non-secret SQL file through standard input using an operator-approved equivalent, or copy it into the database container and run `psql -f`; do not include password values in the command. Compose v5 TTY auto-detection under automated Windows PTY may need explicit `--no-TTY=false`; password bytes remain console-only.

## 4. Maintenance and upgrade

Close/drain ingress and stop Web writers before maintenance:

```sh
docker compose --env-file /protected/deployment.env -f compose.yml stop proxy sso
docker compose --env-file /protected/deployment.env -f compose.yml --profile maintenance run --rm --no-TTY=false maintenance admin-reset-password
```

Maintenance uses the exact same verified server image/key/config, a separate migrator credential, no management listener, disabled scheduling/session cleanup and `logging: none`. Non-TTY reset/bootstrap fail; password command-line options and arbitrary configuration overrides fail. Operator console output can still be sensitive and must remain private. Reset displays the unique administrator and requires YES; it sets a new ACTIVE password, unlike bootstrap's temporary credential. Verify fresh login, old-session refusal and renewed recovery-code custody before reopening.

For upgrades, retain verified off-host backup and old images/config, stop writers, select the exact new image, run offline migration, reapply runtime permissions, start and verify. V16 ordinary indexes need target-data time/disk rehearsal. Flyway is forward-only; binary rollback is allowed only for verified schema compatibility. A binary with the production gate rejects unknown future migrations. Do not delete the persistent volume (`down --volumes`) as an upgrade/rollback step.

Restore old snapshots into an isolated database with ingress/jobs stopped. Follow the documented expected-epoch confirmation, new signing key/kid and global reset; clear consumer sessions/caches/old evidence and reject delayed old-generation replies before reopening. The maintenance command is `rotate-security-epoch-after-restore --expected-epoch=<recorded-UUID> --reason=<non-secret-reference>` and requires interactive confirmation. Copying a database or rolling back a JAR alone is not recovery.

## 5. Monitoring, backup and target gates

Attach only a reviewed collector to the private monitoring network and scrape `sso:9091/actuator/prometheus` with username `las-sso-metrics` and an independent protected password file. Existing Prometheus/Alertmanager/Grafana inputs require actual network addresses and real notification configuration. The Compose stack does not provision collectors, a public Grafana or a backup scheduler. Verify real delivered/resolved notifications for service/database/snapshot/outbox/reuse, plus host disk/resources/clock/certificate and backup freshness/failure. Health/snapshot caching is not notification delivery.

The backup role can run authenticated `pg_dump --format=custom` over the private database network; keep its password in protected file/process environment, never argv. Record hash, date, exact PG/schema/image and verification metadata without database contents. Encrypt, transfer off-host, verify retention/recoverability and notify failures. A Docker volume or same-host dump is not off-host backup. Define RPO/RTO and restore a real backup in isolation; preserve audit/tombstone/consent/username facts and separate key/password custody. No backup frequency/retention target is invented here.

Before opening the SSO account site, record:

| Gate | Evidence required |
| --- | --- |
| P06 | Actual host/DNS/public CA/renewal; unknown Host, canonical header spoof refusal, Secure Cookie/Origin, upstream/DB/metrics inaccessible from outside |
| P07 | Linux UID/mode/mount custody, stable key/secrets after restart, wrong-key refusal, approved rotation and responsible operator |
| P08 | Target migrations/permissions, runtime noDDL/read-only Flyway history, backup read-only, jobs/DB outage/recovery and schema-compatible rollback |
| P09 | Private authenticated collector/UI, real recipient acknowledgement, failure/resolved alerts and credential rotation |
| P10 | Encrypted scheduled off-host backup, actual isolated restore/new-key+epoch/old-credential refusal, measured RPO/RTO and operator recovery |

Target capacity/security/final candidate/limited-opening gates also remain. Apply P11/P12 when a named consumer launches; SSO-only scope does not require LASLOG/AlienCommon beforehand. Retain candidate-specific evidence; never replace an older immutable package with changed bytes.
