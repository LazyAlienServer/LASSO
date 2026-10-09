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
| Local state | Agreed restart logout, accepted-task durability and pre-submit compensation |
| Recent password | Originating session/pending action/15 minutes; refresh does not advance auth_time |
| Eligibility | One-minute refusal and epoch/cache/inflight barriers |

Account-site first launch may have no clients. SSO cannot implement consumer business authorization on its behalf.

## Record

Record version/hash, date, target, owner, command, expected/actual result and follow-up. Sanitized results only; no password, Token, private key or recovery code.
