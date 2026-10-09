# Docker deployment: package to first login

[Index](README.md) · [中文](../zh-CN/docker-deployment.md) · [Troubleshooting](troubleshooting.md)

**Scope:** Linux, one SSO instance, same-host Nginx, separate PostgreSQL, no initial CDN.

**Working directory:** Extracted package root. Commands use Bash; `/etc/las-sso` and `/var/tmp` are adjustable example paths.

## Route map

| Step | Task | Continue when |
| --- | --- | --- |
| 1 | Verify package/tools | Verification exits 0 |
| 2 | Build three images | Exact IDs and receipts retained |
| 3 | Configuration/secrets/key/TLS | Required files readable by intended UIDs |
| 4 | Fill and validate inputs | Validator exits 0 |
| 5 | Database and offline migration | V1–V16 complete, exit 0 |
| 6 | Bootstrap administrator | Hidden console succeeds |
| 7 | Web and first password change | Three healthy services, fresh administrator login |
| 8 | Target checks and opening | Monitoring/backup/TLS/external boundaries accepted |

## 1. Verify package and tools

Prepare Docker Engine, the Compose plugin and Python 3.10+. Generating the signing key also needs JDK 21 `keytool`.

```sh
python3 scripts/verify-package.py
docker version
docker compose version
```

**Expected:** `Verified {{VERSION}}`. Local image acceptance used Linux/amd64.

| Failure | Action |
| --- | --- |
| Missing/extra/checksum file error | Obtain an intact package |
| Docker daemon unavailable | Check service and operator permissions |
| Other architecture | Validate its images/runtime before deployment |

## 2. Build images

Images are not embedded in the ZIP. Build on a controlled online machine; the target may load already-built, verified identical images.

### 2.1 SSO image

```sh
WORK="/var/tmp/las-sso-{{VERSION}}"
mkdir -p "$WORK"
SERVER_SHA256="$(python3 -c 'import json; print(json.load(open("provenance/release-manifest.json"))["artifacts"]["server"]["sha256"])')"

python3 scripts/build_docker_image.py \
  --jar application/las-sso-server-{{VERSION}}.jar \
  --expected-sha256 "$SERVER_SHA256" \
  --version {{VERSION}} \
  --tag las-sso:{{VERSION}} \
  --release-manifest provenance/release-manifest.json \
  --output-dir "$WORK/server"
```

**Expected:** `$WORK/server/image-build.json` identifies source commit/JAR hash/image ID; `unverifiedFixture=false`.

### 2.2 Database and proxy

```sh
python3 scripts/build_docker_infrastructure.py \
  --postgres-tag las-sso-postgres:{{VERSION}} \
  --proxy-tag las-sso-proxy:{{VERSION}} \
  --output-dir "$WORK/infrastructure"

docker image inspect las-sso:{{VERSION}} --format '{{.Id}}'
docker image inspect las-sso-postgres:{{VERSION}} --format '{{.Id}}'
docker image inspect las-sso-proxy:{{VERSION}} --format '{{.Id}}'
```

**Expected:** Three `sha256:...` IDs, `infrastructure-images.json` and package lists.

- Pinned PostgreSQL/Nginx bases receive Alpine updates during build.
- PostgreSQL replaces old `gosu` with `su-exec`; a rebuild may change bytes.
- Scan exact output images and retain receipts. No package updates on runtime startup.
- Transfer with `docker image save/load` if needed; verify archive hash and loaded IDs.
- Compose uses `pull_policy: never`; load chosen images before deployment.

## 3. Prepare external files

Keep the package immutable. Store live settings outside it.

```text
/etc/las-sso/
├── deployment.env              non-secret inputs
├── config/server.properties    container app settings
├── secrets/                    nine secret files
├── signing.p12                 persistent signing key
└── tls/
    ├── fullchain.pem           official certificate chain
    └── privkey.pem             TLS private key
```

### 3.1 Templates

```sh
install -d -m 0700 /etc/las-sso /etc/las-sso/config /etc/las-sso/secrets /etc/las-sso/tls
install -m 0600 deploy/docker/server.properties.example /etc/las-sso/config/server.properties
install -m 0600 deploy/docker/deployment.env.example /etc/las-sso/deployment.env
```

Directory creation needs appropriate host privileges; adapt paths deliberately.

### 3.2 Secrets

| Filename | Content / user |
| --- | --- |
| `db-admin-password` | PostgreSQL administrator password |
| `db-runtime-password` | SSO runtime login password |
| `db-migrator-password` | Offline migration/maintenance password |
| `db-backup-password` | Read-only backup password |
| `signing-password` | PKCS12 password, same private-key password |
| `recovery-pepper` | Recovery digest secret |
| `transient-pepper` | Token/transient digest secret |
| `captcha-secret` | CAPTCHA HMAC secret |
| `monitoring-hash` | Independent collector password's `{bcrypt}` hash, cost 10–14 |

**Generate independently:** DB/key passwords minimum 12 UTF-8 bytes; three peppers/HMAC secrets minimum 32 bytes.

Write values directly to protected files, not deployment.env/argv/screen/Git. Only the collector holds its raw password; the app receives the hash. Preserve values across restart.

### 3.3 Signing key

Use a private interactive terminal with hidden password input. Store the same password in `secrets/signing-password`.

```sh
keytool -genkeypair \
  -alias las-sso-signing \
  -keyalg RSA -keysize 3072 -sigalg SHA256withRSA \
  -storetype PKCS12 \
  -keystore /etc/las-sso/signing.p12 \
  -dname "CN=LAS SSO Signing"
```

Choose certificate validity under the custody policy. Set matching alias; retain stable key/kid and independent encrypted backup.

### 3.4 TLS and Linux permissions

| File | Container reader |
| --- | --- |
| `server.properties`, `signing.p12`, app secrets | SSO UID `10001` |
| Four DB password files | Actual PostgreSQL database UID |
| Runtime/migrator password files | Both SSO10001 and PostgreSQL UID |
| `tls/privkey.pem` | Nginx UID `101` |

Inspect the actual database UID:

```sh
docker run --rm --network none --entrypoint id las-sso-postgres:{{VERSION}} -u postgres
```

Use restricted ownership/groups/POSIX ACLs for shared readers. Ordinary Compose secrets are host-file mounts, not encrypted vaults or automatic UID remapping.

On a Linux filesystem supporting POSIX ACLs, the following is an example. **Before running:** all nine secret files, key and TLS files exist; `setfacl` is installed; use the owning/root account in a private terminal.

```sh
PG_UID="$(docker run --rm --network none --entrypoint id las-sso-postgres:{{VERSION}} -u postgres)"
chmod 0600 /etc/las-sso/config/server.properties /etc/las-sso/signing.p12 /etc/las-sso/secrets/* /etc/las-sso/tls/*
setfacl -m u:10001:r /etc/las-sso/config/server.properties /etc/las-sso/signing.p12
for name in db-runtime-password db-migrator-password signing-password recovery-pepper transient-pepper captcha-secret monitoring-hash; do
  setfacl -m u:10001:r "/etc/las-sso/secrets/$name"
done
for name in db-admin-password db-runtime-password db-migrator-password db-backup-password; do
  setfacl -m "u:$PG_UID:r" "/etc/las-sso/secrets/$name"
done
setfacl -m u:101:rx /etc/las-sso/tls
setfacl -m u:101:r /etc/las-sso/tls/fullchain.pem /etc/las-sso/tls/privkey.pem
```

**Expected:** `getfacl` shows only intended readers and no other-user access. Recheck after file creation/rotation. Adapt IDs to actual user-namespace mappings when applicable.

**Expected:** Intended UIDs can read; unrelated ordinary host users cannot. Do not fix private-key errors with `chmod 644`.

Supply official-host public-CA chain/key and tested renewal/reload. Container health does not validate certificate expiry/trust.

## 4. Fill deployment.env

Edit `/etc/las-sso/deployment.env`; target measurements determine resource limits.

| Variable group | Value |
| --- | --- |
| `LAS_SSO_PROJECT` | Unique staging/production project |
| `LAS_SSO_IMAGE` | Complete immutable server ID or loaded repository digest |
| `LAS_SSO_DB_IMAGE` | Exact PostgreSQL image ID/digest |
| `LAS_SSO_PROXY_IMAGE` | Exact Nginx image ID/digest |
| `LAS_SSO_HOST` | DNS name, no scheme/port |
| `LAS_SSO_ISSUER` | `https://<official-host>[:external-port]`, no trailing slash |
| `LAS_SSO_SIGNING_KEY_ALIAS` / `LAS_SSO_SIGNING_KEY_ID` | Matching alias and stable unique kid |
| `LAS_SSO_BIND_ADDRESS` / `LAS_SSO_HTTP_PORT` / `LAS_SSO_HTTPS_PORT` | Reviewed host interface and external ports |
| `LAS_SSO_CONFIG_DIR` / `LAS_SSO_SECRET_DIR` | Example `/etc/las-sso/config` / `/etc/las-sso/secrets` |
| `LAS_SSO_KEY_PATH` / `LAS_SSO_TLS_DIR` | Example `/etc/las-sso/signing.p12` / `/etc/las-sso/tls` |
| `LAS_SSO_MEMORY_LIMIT` / `LAS_SSO_CPUS` | Finite SSO limits with JVM-native headroom |
| `LAS_SSO_DB_MEMORY_LIMIT` / `LAS_SSO_DB_CPUS` | PostgreSQL target limits |
| `LAS_SSO_PROXY_MEMORY_LIMIT` / `LAS_SSO_PROXY_CPUS` | Nginx target limits; preserve host headroom |

No servlet prefix in this topology. Container ports 9000/9091 use the supplied internal binding; host/systemd loopback settings are different.

```sh
python3 scripts/verify_docker_deployment.py \
  --env-file /etc/las-sso/deployment.env \
  --compose deploy/docker/compose.yml
```

**Expected:** `Docker input/Compose boundaries verified`, exit 0.

**Failure:** Check blanks, mutable tags, issuer/host/port mismatch, inherited shell overrides or zero limits. Do not publish raw Compose config output.

## 5. Database and migration

At package root, define the Bash helper used below:

```sh
dc() { docker compose --env-file /etc/las-sso/deployment.env -f "$PWD/deploy/docker/compose.yml" "$@"; }
dc up -d postgres
dc ps
```

**Expected:** PostgreSQL `healthy`; wait/recheck while starting.

```sh
dc --profile maintenance run --rm -T maintenance database-migrate
dc exec -T postgres psql -X -U postgres -d las_sso -v ON_ERROR_STOP=1 \
  < deploy/docker/postgres-runtime-permissions.sql
```

**Expected:** Migration completes with exit 0 and no HTTP listener; permission SQL succeeds.

| Behavior | Purpose |
| --- | --- |
| Embedded Flyway V1–V16 | One migration source |
| Migrator owns objects | Runtime does not execute DDL |
| Permission SQL | Runtime reads but cannot modify Flyway history; repeat after migration |
| Repeat migration command | Validates completed schema, exits normally |

**Initialization failure:** Retain private evidence; an existing volume does not rerun init. Do not delete populated volumes without a verified data decision.

## 6. Administrator bootstrap

Use a real interactive terminal. Do not use `-T` for passwords.

```sh
dc --profile maintenance run --rm --no-TTY=false maintenance admin-bootstrap --username=InitialAdmin
```

1. Verify chosen username, enter temporary password privately.
2. Confirm via hidden input.
3. Record user UUID/audit ID, no password.

**Expected:** Exit 0, exactly one administrator. The temporary credential expires in 24 hours and requires first password change.

| Situation | Action |
| --- | --- |
| Promote existing user | Replace username with `--user-id=<UUID>`, not both |
| `INTERACTIVE_CONSOLE_REQUIRED` | Real terminal and explicit TTY |
| Administrator already exists | Inspect state; use [offline reset](operations.md) |

## 7. Start Web and log in

```sh
dc up -d sso proxy
dc ps
dc exec -T sso curl --fail --silent http://127.0.0.1:9091/actuator/health/readiness
```

**Expected:** Three healthy services, readiness `UP`.

Through actual HTTPS:

1. Log in at `/login` with temporary password.
2. Complete forced change and log in again.
3. Generate recovery codes; keep private/off-host independently of DB.
4. Check `/admin/users` and `/admin/audit`.

**Network results:** Unknown Host returns 421, HTTP canonical redirect returns 308, public actuator returns 404. No database/9000/9091 host mappings.

## 8. Before opening

| Check | Success criterion |
| --- | --- |
| DNS/TLS | No browser certificate exception; renewal/reload tested |
| Cookie/proxy | Secure/HttpOnly/Lax/host-only, forged forwarding cannot change endpoints |
| Database/restart | Same key/secrets; DB interruption rejects readiness |
| Monitoring | Private authenticated scrape and real firing/resolved receipt |
| Backup | Encrypted off-host recoverability, declared RPO/RTO |
| Capacity/security | Target resource/current-image checks |

See [monitoring](monitoring.md), [backup](backup-and-recovery.md), [acceptance](acceptance.md).

Account-site-only launch may have no clients. Do not open unaccepted LASLOG/AlienCommon consumers.

## Command reference

At package root with `dc()` defined:

```sh
dc ps
dc logs --tail 100 sso
dc logs --tail 100 postgres
dc logs --tail 100 proxy
dc stop proxy sso
dc up -d sso proxy
```

Inspect logs privately, redact before sharing. `down --volumes` deletes the database volume; not a restart/upgrade command.

PowerShell: replace `dc` with full Compose arguments. Bash functions and `<` do not apply; pipe non-secret SQL with `Get-Content ... | docker compose ... exec -T postgres psql ...`. Passwords still require hidden TTY input.
