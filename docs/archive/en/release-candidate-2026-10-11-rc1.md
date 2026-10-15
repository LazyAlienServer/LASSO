# Historical release candidate 0.1.0-rc.1 — 2026-10-11

[中文](../zh-CN/release-candidate-2026-10-11-rc1.md) · [Evidence](../../../release/verification/0.1.0-rc.1.json) · [Release entry](../../../release/README.md)

## Delivery and source

The user authorized grouped local commits and clean RC build/scans/package/acceptance. Four local commits: `ea7ef4a` refresh ownership, `ba81a2b` version/API tooling, `b626c88` external Nginx deployment, `8db0b20` bilingual procedures/evidence. This RC identifies clean source **`8db0b20e6c5898064ef59f657a15d5758b5dd166`**, tree `21ce6cf76e392a5a962c05603e59c6d03747155e`. Later acceptance-document commits do not change its source or bytes.

- Directory: `release/0.1.0-rc.1/`.
- ZIP: `release/las-sso-0.1.0-rc.1.zip`, **81,512,271 bytes**, 113 files/451 package-local links.
- ZIP SHA256: **`6c8be5bbfee723d529217430fde8515ef5f64648e4355590fb184a2eb5682c04`**.
- Source ZIP: `554bec04720c242329b8af4f6103f47040e7ade2074eeee1cdd5cdba9d1fa7e0`.
- Server JAR: `c0e35f22b941f0b5cf7fe421f977ddc59467a1bb6e06f8b0aab4d75db96d2319`.
- Starter: `1a916c9df33f2a0b5eab88795cdc49bce1115d4b3c6914f5d65f81608841f5ad`.
- Reference BFF: `d2d33254b9ff3281b2a4cea5d7541e9a4ee3d6cb5387c3f41fec595e263f820c`.

The Starter's content hash matches `.2` because its library implementation is unchanged; exact RC coordinates/POM/native metadata were published and consumed independently. Server/reference executable manifests use the exact RC version. Existing `.2` and earlier distributions remain immutable.

## Build, scans and package

Strict clean/re-run database-required three-module build: **597 server +196 Starter +50 reference =843**, zero failures/errors/skips, UTC07:26:35–07:33:40. Exact Starter publication passed. Fourteen Python tool tests, rendered bilingual18pages/63workflows, implemented OpenAPI49paths/59operations/54schemas and package-local API/version/reference checks passed.

Trivy0.75.0 with advisory DB UpdatedAt2026-10-11 identified241locked/157nested-JAR packages with zero reported dependency vulnerabilities. Java mapping update check exited0 and retained the seeded October9 mapping within its October12 freshness window; no failed-refresh fallback. Semgrep1.179.0 scanned514paths, six reviewed existing rule/path identities, zero errors. Gitleaks8.30.1 source/artifacts zero; the identical historical synthetic keystore-path finding retains exit1/report and review without suppression. Complete independently extracted package archive-depth3 secrets scan zero.

Independent ZIP extraction passed inventory/source/artifact/migration/API/links; tamper/extra/wrong-maintenance-hash/blank-topology inputs were refused. Native and POM-only SDK consumers compiled and checked dependency alignment. Python/PowerShell and Prometheus config/rules/fixtures/Alertmanager config passed. The package built its own exact server image without Git: `sha256:4357c1e441bfe1e6e16dbb4978f350e2a5695a125693865dafa40d711f6ee8be`, correct source/JAR labels and `unverifiedFixture=false`; package bytes verified unchanged afterward.

## Exact-artifact runtime acceptance

Used standalone host-edge Compose with generated private secrets/key and PostgreSQL18.4 fixture. One-hop TLS routing was represented by an isolated Nginx container using the package's site/forwarding templates; Windows did not execute host-installed Linux Nginx. Java/Node explicitly trusted a short-lived private CA; Edge used its fixture SPKI exception, not public-CA acceptance.

Passed fresh offline V1–V16, migrator/runtime grants, hidden-console bootstrap, actual browser first-change/new admin login, ALTCHA registration/ten codes, profile, mobile recovery, freeze/unfreeze, deletion restricted login/cancel, Secure host Cookie and same key/JDBC session after restart. Canonical metadata despite spoofed forwarding, Host421 and public actuator404 passed. App publication was loopback-only; DB/management unpublished.

Two administrator-registered clients and the exact reference JAR passed both default and application-owned chains: Code/PKCE/UserInfo/stable subject, automatic refresh, fixed family deadline and unadvanced auth_time, cross-client ROTATED RT refusal without owner revocation, wrong-password then valid prompt=login challenge, local SSO-preserving logout, global logout/real HTTPS Back-channel and forged return-state refusal. Fixture BFF refresh-before-expiry was15minutes to trigger immediate rotations while OP AT remained its default15minutes. Reauthentication used the original BFF state/nonce/verifier transaction with standard parameters added by the harness; this is not LASLOG pending-operation integration.

Same-V16 populated `.2`→RC binary transition preserved facts/epoch, with offline validation; not a new-DDL/representative-old-schema claim. Actual hidden reset produced an ACTIVE password, ended old admin sessions and new admin login passed. Actual backup-role custom dump/list, separate-empty-target migrator restore and post-restore Flyway/grants passed. No-console epoch command refused; real interactive epoch/key rotation preserved user facts and produced five zero safety-state counts. Old Cookie/AT/RT/code were refused and retained-user fresh password login passed. Readiness200→503→200, outage liveness200 and private metrics401/200 passed; fixture secrets absent from container logs.

Packaged reference maintenance separately passed V1/V2, no-console/cancel refusals, authenticated encrypted synthetic dead-task replay/stale generation refusal, completed purge/audit, drained key rotation/old-process fencing/new-key restart. It did not claim a live worker remote revocation receipt. All generated runtime credentials/keys/browser state and isolated containers/networks/DB volumes were removed; existing user/local data remained untouched.

Harness-only failures were corrected: refresh configuration needed BFF restart; reauthentication needed the original pending request; async outbox completion required a wait; restored-page navigation used a path/DOM readiness predicate. An early assertion printed a synthetic RT into a private error log; that log was removed and errors restricted to stage/type/locations. No runtime source fix was needed for these harness issues.

## Image findings and stable-release follow-up

**Do not describe the RC image as security-cleared.** Exact SSO image and effective rootfs report3High/45Medium/5Low/4Unknown. All three High are Go stdlibv1.26.7 in inherited `/usr/bin/pebble`: CVE-2026-78667/78669/97031, fixed stdlib1.26.9/1.27.2. The fixed SSO launcher does not invoke it or the inherited CA entrypoint, but the file is present. Remove/upgrade the unused tool, rescan and revalidate newly identified image inputs before stable-image acceptance; keep RC bytes unchanged.

PostgreSQL image-archive findings1Critical/24High/27Medium/3Low/5Unknown correspond to removed gosu metadata. Effective regular-rootfs scanning identifies54packages/zero reported vulnerabilities; actual gosu path is a symlink to su-exec and the old regular binary is absent. Both reports are retained. The reused unpatched Bookworm edge fixture has10Critical/150High/268Medium/208Low/23Unknown and is not recommended for production; selected deployment uses host-installed Nginx with independently scanned target OS/packages.

Formal public domain/CA/renewal, Linux access/firewall, actual monitor/Grafana/recipient, encrypted off-host recovery/RPO/RTO, realistic capacity and launched consumers remain target-stage work. RC is available for controlled evaluation; it is **not stable0.1.0 or production approval**. No push, hosted release or operation on the user's server occurred. The post-package evidence record is outside immutable package bytes, by design.
