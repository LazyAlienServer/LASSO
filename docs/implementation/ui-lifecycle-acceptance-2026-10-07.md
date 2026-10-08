# P04 Core UI And Lifecycle Acceptance

[中文](../zh-CN/implementation/ui-lifecycle-acceptance-2026-10-07.md) · [ADR-0014](../adr/0014-manual-recovery-verification-and-secret-ui.md) · [Manual operations](../operations/manual-recovery-and-lifecycle.md)

## Existing Work Reused

P04 is completion of missing evidence, not a new UI implementation. Account/recovery/deletion/admin screens and underlying APIs already existed. Historical [administrator maintenance](../operations/administrator-maintenance-acceptance.md) verified hidden bootstrap, first change, recovery download and offline reset with old-session/Token refusal. [Old-code compatibility](recovery-credential-entropy.md), [deletion concurrency](deletion-concurrency.md), [promotion](admin-promotion-deletion.md) and [tombstone](admin-tombstone-views.md) provide database/MockMvc/template evidence. Earlier OIDC/TLS/default/application BFF acceptance remains tied to its original artifacts, not silently promoted to this run.

## Current Browser And Maintenance Layer

Current SNAPSHOT server JAR including P02/P03, JDK21, disposable PostgreSQL18.4, actual PowerShell/Windows PTY, Edge154 headless/Playwright1.55.1 and axe-core4.14.0. Production profile with a generated persistent signing key and direct HTTPS fixture on loopback; browser uses a narrowly scoped generated-certificate SPKI trust exception, Node negatives verify fixture CA/SNI/hostname. No production DB/key, public CA or deployment is used. Isolated fixture rate buckets are cleared between independent scenarios; accelerated recovery/credential/deletion dates and short deletion-job polling are test setup, not changed production defaults.

| Scenario | Verified outcome |
| --- | --- |
| Bootstrap/first change | Real hidden console bootstrap; production Web; first-change UI, current fields cleared and normal new-password login |
| Register | Actual ALTCHA widget/request/create UI; ten80-bit codes; save acknowledgement; actual clipboard feedback/readback (Windows CRLF normalized), cleared clipboard; download content matches; revisit does not reveal prior codes |
| Ordinary recovery |375px form/error focus; short password rejected; Secure/HttpOnly/Strict restricted Cookie; completion ends old logged-in browser; used code rejected |
| Old-code recovery | Explicit old three-group HMAC fixture uses real browser flow; accepted without rewriting the generator; new password works |
| Expiry/manual recovery | Ten-minute recovery expiry returns to first step; real admin issue/reissue; old and expired manual credentials reject; current manual credential completes recovery;30-minute expiry accelerated |
| Sensitive writes | Missing CSRF/foreign Origin refused with no target credential/deletion mutation; wrong request password cannot request deletion |
| Deletion | Browser request→restricted page; private admin hold reason not exposed, public summary shown; owner cancels HELD with password; due fixture executes through real scheduler; admin browser displays tombstone/no write buttons |
| Release/freeze/signout | Hold→release preserves original execute_after, then owner cancels; freeze invalidates old login, unfreeze needs new login, administrator force-signout ends browser access |
| One-time results | Closing/save/Escape clears manual credential/client Secret DOM; actual create/rotate; two actual same-key issue requests emulate response uncertainty and UI keeps plaintext-unavailable error visible |
| Keyboard/layout | Native modal focus remains in dialog or browser chrome, Escape/return behavior; focusable audit history responds to PageDown;375×812/812×375/768×1024/1440×1000 plus320px/720px reflow samples; no horizontal overflow |
| Existing-user promotion | Stop Web, restore separate fixture clone, remove admin flag only as no-admin clone setup; effective deletion refused by real hidden-console command without credential/audit mutation; explicit clone cancellation permits unique promotion; browser verifies expired temporary credential refusal and valid forced-change→new-admin login |

Owner cancellation was verified through the real main fixture UI; cancellation in the promotion clone is SQL setup, not a claim that an operator may cancel for the user. Temporary expiry is date acceleration, not a24-hour wait. Business permission/qualification/15-minute consumer gates and actual QQ claimant verification are not performed by these fixtures.

## Defects Fixed And Regressions

- Manual credential/client Secret retained in a hidden closed dialog: synchronous clear on save/close/Escape plus close/pagehide cleanup.
- Registration copy awaited clipboard then dereferenced expired `event.currentTarget`: retain button reference before await; success/failure feedback works without script error.
- Idempotent secret-unavailable errors were placed in a closed dialog: keep confirmation visible until usable plaintext/success outcome.
- Password/credential fields survived hidden success steps or native dialog close: reset on successful transitions and close/cancel/pagehide; revealed inputs return to password type.
- Rotated code output now has save acknowledgement/unsaved-navigation warning and pagehide erasure; page history does not resurrect plaintext. Browser clipboard/download copies remain user-owned.
- Management confirmations show immutable user/request/client targets; dialog names/descriptions, login heading reference and brand visible-name match corrected.
- ALTCHA background/text contrast corrected; audit/deletion/client scroll regions now keyboard focusable.

Tracked [browser regressions](../../scripts/browser/README.md) exercise source DOM/JS/native dialog/clipboard/history with isolated stub protocol responses and no SSO credentials. This is separate from real JAR/PostgreSQL integration. `npm ci`/`npm test` use pinned Playwright and installed Edge; no CI-browser execution is claimed.

## Accessibility And Remaining Scope

Main24 and supplementary17 rendered states had no reported axe A/AA/2.1 A/AA violations after fixes, no horizontal overflow or page script errors. Decorative backgrounds leave automatic contrast incomplete results; a solid-background follow-up on five representative desktop/mobile/landscape states found zero contrast violations, with one disabled rotate control still not assessed automatically. This is bounded evidence, not whole-site WCAG certification, a human screen-reader test or a blanket conclusion for every text/control. Small dense metadata and actual mobile-device/screen-reader behavior need target review. Reduced-motion was enabled.720px equivalent reflow/320px tests replace an invalid body-CSS-zoom specimen; this is not browser-level zoom certification.

User approved two independent manual recovery evidence bases and minimum no-secret case recording. Runbook/templates are complete; actual operator assignment/training, key/recovery-code custody, real claimant verification and dispute handling remain target/operator work. Partner consent/browser integration remains conditional before partner launch. Old release bytes remain unchanged.

## Final Verification

Strict forced JDK21/Docker build: **562 server +196 Starter +50 reference =808**, zero failures/errors/skips. `npm ci --ignore-scripts` and `npm test` in `scripts/browser` passed four interaction groups; separate from JUnit counts. No application Java/dependency/SSO migration changes. Final server SNAPSHOT SHA-256 `2d11bc988aa77cecb653480dbc1f3d9d913c7e3e8d9c65803916eb88405288f7`, base `ae27c3a` plus this recorded P04 worktree. Main/supplementary/lifecycle flows passed on that JAR. Promotion/temporary-expiry fixture was also exercised in this task; its no-admin/cancelled clone setup is explicit. Source commit after review identifies changes, not a replacement release manifest.

Initial fixture failures (download header expectation, HTTP error-status expectation, asynchronous UI wait, DB date constraint, Node test-domain DNS and Windows clipboard CRLF) were corrected in test tools without changing product policy. Native dialog Tab may focus browser chrome (`document.body`) but cannot reach underlying page controls; the original overstrict assertion was corrected. Body CSS zoom does not simulate viewport media-query reflow and its overflow result is not used as browser zoom proof. Actual UI defects above were separately repaired and rerun. Raw screenshots/logs/private fixtures remain outside source; the operator runbook contains no actual credentials. Latest package `0.1.0-dev.20261007.1` remains historical and does not contain P02–P04.

Final source review:524 files scanned by Gitleaks8.30.1/redact100, zero findings; runtime log checked against retained fixture secret values and JWT patterns with no matches (targeted, not a whole-environment scan). Repository502 UTF-8/text candidates/1215 local links/one declared sibling link, three locks/Wrapper checksum/15 migrations passed. Fixture JVM/PostgreSQL and external private account/config/PKCS12 files were stopped/removed after final revalidation; no production operation or push. Stable-state screenshot review was repeated after status loading, with the same bounded contrast result. No final dependency/image/DAST scan or whole-site accessibility certification is claimed.
