# Acceptance: package integrity and opening criteria

[Index](README.md) · [中文](../zh-CN/acceptance.md) · [Release notes](../../RELEASE-NOTES.md)

## 1. Evidence inside this package

Run `python scripts/verify-package.py` at package root; expect exit 0.

| File | Read |
| --- | --- |
| release-manifest | Version/commit/tree/artifact and file hashes |
| SHA256SUMS | Integrity, not signature |
| verification-summary | Commands/tools/times/module tests/scan dates and reviews |
| dependencies | Locks/catalog/Wrapper/nested JAR inventory |
| migration-manifest | Embedded migration hashes |
| source | Exact clean git archive and dated historical records |

Verifier rejects missing/extra/hash/version/source/migration mismatches and nonlocal handbook links.

## 2. Use evidence correctly

- Complete DB-required test reports must have zero failures/errors/skips.
- Extract opaque JARs/use locks for actual dependency coverage.
- Zero findings is not proof of absence; inspect dates/packages/reviews.
- Historical TLS/restore/Grafana/short-AT load does not become current-version acceptance.
- Fixture certificate/Windows access does not prove public CA/Linux custody/target soak.
- Stable archive order/timestamps, no independent two-builder reproducibility claim.

## 3. Account-site opening

| Area | Pass when | Record |
| --- | --- | --- |
| Inputs/startup | Official issuer/owner/independent key-secret/production | Environment/config revision |
| Administrator | Offline bootstrap, first change, recovery custody and reset | Operator/UUID/audit reference |
| Database | Fresh/populated upgrade/grants/index time-space/DB recovery | Schema/version/time |
| TLS/network | Public CA, renewal, spoof resistance, Secure Cookie and private ports | External probes |
| Lifecycle | Freeze/deletion/hold/cancel/execute, manual recovery and password guards | Sanitized results |
| Monitoring | Private authentication, real firing/resolved, disk/backup/clock | Recipient acknowledgement |
| Backup | Encrypted off-host restore, new key/epoch and old-credential rejection | Measured RPO/RTO |
| Capacity/security | Target ramp/soak/history, current image/JDK/proxy scan and DAST | Limits/findings |
| Opening | Candidate, rollback, limited traffic and first operations cycle | Owner acceptance |

## 4. Per launched consumer

| Area | Pass when |
| --- | --- |
| OIDC | Two clients, Code/PKCE/state/nonce, stable UUID and consent |
| Refresh/logout | Coalescing, reuse denial, local/global and duplicate/late Back-channel |
| Local state | Agreed restart logout, accepted-task durability and explicit treatment of pre-submit/unknown remote results |
| Recent password | Originating session/pending action/15 minutes; refresh does not advance auth_time |
| Eligibility | One-minute refusal and epoch/cache/inflight barriers |

Account-site first launch may have no clients. SSO cannot implement consumer business authorization on its behalf.

## Record

Record version/hash, date, target, owner, command, expected/actual result and follow-up. Sanitized results only; no password, Token, private key or recovery code.

## V01 Software-package acceptance workflow

**Expected:** Exact software-package provenance/integrity and supported isolated workflows have actual evidence; unexecuted target checks remain explicit.

**Failure:** Preserve the failed evidence and correct the candidate; historical or syntax-only results cannot replace missing current-version execution.

1. Independently extract to a fresh directory, check a trusted ZIP checksum, run the verifier and confirm exact version/kind/source/artifacts/API/embedded migrations with exit 0.
2. Read actual verification dates/counts/skips/scan database/scope. Historical or focused three-case reports are not this version's full run; software release is not deployment approval.
3. Install in isolation using selected N01–N06, the bundled eight Docker steps or J01. Execute supported U/A/I workflows with dedicated fixtures, not destructive operations on real users.
4. Declared upgrade/restore support requires representative O03/B02 and epoch/key/consumer barriers; record actual commands, read-back and platform limitations.
5. Syntax checks are not deployment, OpenAPI is not whole-OIDC compliance, and SDK compilation is not LASLOG login. The owner reviews scope/defects/exceptions and retains immutable distribution.

## V02 Target and consumer opening workflow

**Expected:** The exact candidate meets the target's agreed inputs, operating checks and launched-consumer scope, with owner acceptance before expansion.

**Failure:** A blocking target or consumer check keeps the affected access closed; apply the recorded repair/rollback decision and retest.

1. Complete section 3 with actual network/secrets/database/public CA/external-port/traffic/resource inputs.
2. Execute M01–M04, B01–B02 and T01 with real notifications/recovery objectives and an operator able to perform the steps. Account-site-only opening needs no business client.
3. Each launched consumer performs I01–I06 and section 4 negatives; identity, eligibility and business authority remain independent.
4. Deploy the exact candidate, smoke in the window and define stop-expansion/rollback conditions before limited observation. First scheduled backup/notifications/growth review is a post-opening responsibility, not a prematurely checked result.
5. Record version/environment/routes/owner/capacity/limits/support, obtain acceptance before expanding and stay closed on blocking failure.
