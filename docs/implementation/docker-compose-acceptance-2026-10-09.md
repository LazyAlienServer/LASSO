# Docker Compose local acceptance, 2026-10-09

[中文](../zh-CN/implementation/docker-compose-acceptance-2026-10-09.md) · [Environment/owner matrix](../operations/docker-compose-deployment.md) · [Executable instructions](../../las-sso-server/deploy/docker/README.md)

## Scope and exact artifacts

P01 confirms SSO-account-site-only first opening, single-host Compose, same-host Nginx, no initial CDN, separate SSO/PostgreSQL and user-owned initial operations/custody. This record closes local Docker implementation/acceptance only; actual P06–P10, target capacity/security and final candidate remain pending.

The isolated image is explicitly an **unverified-source fixture**, built on checkout base `11eca6b1a4d85ee5032905224f9ed31daf84e83f` plus intended worktree changes. It is not a clean-release source claim. Exact JAR manifest/start class/version/hash and restricted build context were checked. No new release was created; `0.1.0-dev.20261007.1` remains unchanged and excludes these changes.

| Artifact | Local identity |
| --- | --- |
| Server | `0.1.0-SNAPSHOT`, SHA256 `fbd8fad3dbe105fa5da5b42306d4c77af3dadce101f5a3cec3dae64301a3caa0` |
| SSO image | `sha256:de23bc34a3aebdee84c0e70ce28f63975878bfd283c3cdaf1db48f817f9f2e46` |
| Patched PostgreSQL image | `sha256:09e2b24f9794c76db7b73ad0f398a9dfab5ca6ce9742989491679d253634587c`, PostgreSQL18.4 |
| Patched proxy image | `sha256:67822e9fadd8ea32223d3f87348f786ff9de67d45c8b641fe1adef01b01dd4f6`, Nginx1.29.8 |
| JRE base | Temurin21 JRE digest `cff19e6215689161eb6162c11b86b0c60ddf802164f2eaf48d570f8fb79a36c5`, JRE21.0.12.1+1-LTS |

Build tool delivery mode requires clean matching `package_release.py` source/version/server-hash/test evidence, or verified extracted-package provenance. Fixture mode leaves sourceCommit null and labels unverified fixture. It does not relabel an arbitrary old JAR with current HEAD. Deployment validation requires immutable IDs/digests and rejects issuer/host/port mismatch, inherited mismatched host, missing input, zero/unlimited resource limits and raw secret input without printing values.

## Commands and results

JDK21 strict three-module build:

```text
.\gradlew.bat spotlessApply build -PrequireDatabaseTests=true --rerun-tasks --no-daemon --console=plain
python scripts/verify.py tests --require-no-skips
```

**834 tests:588 server +196 starter +50 reference BFF; zero failures/errors/skips.** Added two runner tests plus actual PostgreSQL production-context repeated `database-migrate`, no servlet context and V1–V16 completion. The earlier compile failure assigning Flyway's final validation field was repaired using its actual immutable result constructor. No dependency/lock or existing migration changes.

Local Docker29.4.0/Compose v5.1.2, Linux/amd64 containers on Windows Docker Desktop. Final patched Compose fixture used random private secrets, a two-day self-signed certificate for `auth.las.test`, random loopback published proxy ports, and explicit768MiB SSO/512MiB DB/128MiB proxy test limits; **none is a target sizing/domain choice**. TLS HTTP transport checked its explicit fixture certificate and hostname; Edge154/Playwright1.55.1 used a fixture-only SPKI exception, not public CA acceptance.

| Real path | Result |
| --- | --- |
| Empty DB initialization/migration | Separate admin/migrator/runtime/backup roles; migrator owns tables; offline V1–V16 applied and repeat succeeds/exit0 |
| Runtime permissions | Can read/write domain data; cannot CREATE table or UPDATE Flyway history after post-migration permission SQL |
| Backup permissions | SELECT allowed, domain UPDATE denied; actual authenticated custom pg_dump starts `PGDMP`; private dump removed |
| Hidden console | Actual Compose TTY bootstrap succeeded, password not echoed; nonTTY reset rejected, password argv rejected |
| Offline reset | Unique administrator/YES/two hidden inputs succeeded, credential ACTIVE, old active sessions zero and new-password account login; bootstrap-only forced-change policy preserved |
| Administrator first login | Required-password-change page, successful change, re-login/account page through TLS |
| Container isolation | SSO UID10001/proxy UID101, read-only rootfs, cap_dropALL/no-new-privileges; no app/database host port bindings |
| Proxy | Canonical HTTPS308/unknown HTTP+HTTPSHost421/HSTS; spoofed Forwarded/Prefix/host/proto/port did not alter discovery/endpoints; public actuator404 |
| Cookie | `__Host-las_sso`, Secure/HttpOnly/Lax/Path=/, host-only and not readable through document.cookie |
| Persistent restart | SSO restart retained JDBC login and identical public signing JWKS |
| Signing/metrics negatives | Wrong PKCS12 password refused; private metrics unauthenticated401/authenticated200 without raw password argv |
| DB interruption | Readiness503 while liveness200; DB restart returned readiness200 and preserved account login |
| Logs/metadata | Container logs and Docker runtime config did not contain exact fixture secret/password values; bounded logs, maintenance logging:none |
| Browser | No page JavaScript errors in exercised flow |

Template defects found by real execution were fixed: quoted tmpfs comma options, all Nginx temp paths moved from read-only image directories to `/tmp`, proxy health added. Windows PTY's CRLF produced an empty second hidden-password line; the disposable harness was corrected to send one carriage return. Linux interactive console behavior and application password policy were preserved.

## Image security results and limits

Trivy0.75.0 vulnerability DB updated `2026-10-08T15:33:46.578791096Z`. Java mapping DB update from mirror.gcr.io and ghcr.io failed with unexpectedEOF; explicitly reused mapping DB updated `2026-10-06T01:08:12.735023878Z`. This is a current vulnerability scan with older package-mapping coverage, not a fully refreshed final-release scan.

| Target | Identified package rows | Reported findings |
| --- | --- | --- |
| SSO image | 246 OS/Java rows | Medium40, Low4; no High/Critical in this report |
| Upstream PG18.4 Alpine | 57 | Critical1/High31/Medium33/Low20/Unknown2; repaired OS packages plus old Go gosu |
| Upstream Nginx1.29 Alpine | 72 | High44/Medium83/Low50/Unknown2; repairable system packages |
| Patched proxy image | 71 | Zero reported findings |
| Patched PG image archive | 58 | Critical1/High21/Medium21/Low2/Unknown1 associated with old Go binary metadata retained from base layers |
| Patched PG effective rootfs | 54 installed package rows | Zero reported findings; `/usr/local/bin/gosu` is symlink to `/sbin/su-exec`, old Go executable absent |
| Patched proxy effective rootfs | 71 | Zero reported findings |

Both upstream tags remained at their pinned digests after pull. Bookworm alternatives were also examined and reported more High/Critical findings, so no distro change was made. Build-time Alpine upgrades fixed OS findings; PostgreSQL's official entrypoint now reaches su-exec at its existing gosu path. Actual empty-volume init/restart passed. Raw image-archive findings are retained, not suppressed; runtime-rootfs analysis distinguishes removed base-layer metadata from executable runtime exposure. No finding is counted as absent merely because scanner exit0.

SSO remaining OS Medium/Low findings, including fixable freetype, require review/update and target-current re-scan before a final candidate. Zero reports do not prove absence of vulnerabilities. No target authenticated DAST, real CA/renewal/firewall, live proxy traffic capacity or production OS/JDK validation is claimed.

## Evidence custody and remaining work

Private reports and disposable harnesses are under the approved external temporary work area in `p06-compose-verification`, `p06-compose-final`, `p06-compose-patched`, `p06-image-scans`, `p06-infrastructure-build` and `p06-tooling`; server receipt is ignored `.build-docker/image-build.json`. Retain redacted count/hash evidence with the eventual immutable delivery, not only a temporary folder. Close-out verified all three fixture projects have no remaining containers/networks/volumes and their external keys/secrets/config/private state are deleted; the dump was deleted after verification. No production resources were operated.

Gitleaks8.30.1 `--redact=100` inspected562 current source candidates, zero reported matches; exact fixture secrets were separately absent from runtime logs/metadata. Five Python tools compiled and shell syntax checks passed in the actual Alpine image. Repository checks verified546 UTF-8/text files,1290 local links, three locks, Wrapper checksum and16 sequential migrations. The historical package again verified70files/104links. Secret/pattern scans have detection limits; these counts do not prove every flaw absent. Source candidate totals are for this record before final evidence text edits.

Target Linux UID/ACL/file-secret custody is not proven by Windows bind mounts. Official domain/hardware/traffic/backup schedule/location/retention/RPO/RTO/channel/storage/window remain in the input matrix. Real collector/notification, encrypted off-host backup/restore/new-key+epoch/operator, representative migration/time/disk and final-candidate scan remain gates. Consumers need their own P11/P12 when released.
