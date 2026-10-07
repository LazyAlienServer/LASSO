# Release Notes — {{VERSION}}

## Scope

New development packaging of the current committed Java21 engineering baseline. Includes SSO server, servlet Starter, reference BFF, independent bilingual operations/integration/user handbooks, external configuration templates, proxy/service/monitoring inputs and maintenance/verification tools.

Runtime functionality includes password identity/account lifecycle, JDBC sessions, OIDC Code+PKCE/UserInfo/RT rotation and reuse detection, browser-bound password reauthentication, global and Back-channel logout, first-party eligibility, private metrics and optional reference durable revocation. Flyway V1–V15 includes the HMAC-only ID Token logout-hint association fixing refreshed global logout. Versions/locks and actual nested-library inventory are retained under provenance; do not infer exact versions from generic documentation labels.

Engineering changes since historical `.20261006.7`: committed source history, pinned Java formatting/shared build conventions, explicit database-test skip rejection, portable repository checks, pinned CI definitions and concise module guides. The packaging version differs because source/build inputs changed.

## Verification

`provenance/verification-summary.json` records this build's actual module counts, exit code, source revision and executed command. Database-required builds reject skips. Historical acceptance: earlier `.7` real TLS default/custom BFF, Grafana Viewer desktop/mobile and two five-minute mixed runs; `.6` three alert firing/resolved paths; `.5` administrator/restore drills. Those are not automatically new-version process acceptance.

## Known Limitations

- Development delivery, no production deployment/approval, remote CI result, hosted signature or target-hardware sizing supplied by this package.
- Required settings/key loading/Flyway/DB readiness exist; complete production issuer/default-secret/client/admin maintenance startup validation remains a launch task. Initial ingress must remain closed under operator control.
- Default Starter Token/session/index/replay/sid markers and revocation work are process-local or HttpSession. Restart ends business login; persistent accepted revocation tasks require a consumer adapter or explicit reference profile.
- Reference durable queue cannot close the pre-submit crash gap. It has no automatic dead-task replay, receipt retention cleanup or multi-key rotation; drain/retain keys under controlled operations.
- ID-hint history grows with live authorizations; cascade follows authorization removal. Lost pre-upgrade hints are not reconstructed; pepper rotation can invalidate historical lookup.
- Production domains/CA renewal/firewall/CDN trust, keys/secrets/custody, backups/RPO/RTO, real notification routing, hours-scale soak, external consumer gates and complete UI/security checks still require their own evidence.
- No MFA/email requirement or business permission/Minecraft Claim; qualification events and multi-instance/cross-restart retained login remain deferred.

## Compatibility

Target tested Spring Boot4.1/Security7.1 with JDK21. Starter POM/native metadata carry its dependency alignment; application BOM/locks still determine its final runtime. Database upgrade uses embedded Flyway, not a second manual SQL copy. Do not downgrade application/schema or restore an old backup without the documented security epoch/key and consumer barriers.
