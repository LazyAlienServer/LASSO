# Explicit artifacts for administrator maintenance

- Status: artifact selection implemented/launch-boundary checked on2026-10-05; actual interactive empty-DB bootstrap/first-change/reset drill completed2026-10-06, production/promotion/other-platform acceptance separate.
- [Chinese](../zh-CN/operations/maintenance-artifact-selection.md).

`scripts/bootstrap-admin.ps1` and `scripts/reset-admin-password.ps1` accept `-JarPath` and optional `-ExpectedSha256`. Existing invocations retain the fixed 0.1.0-SNAPSHOT default; scripts never choose a newest JAR. For an identified delivery supply the exact JAR and trusted manifest digest:

```powershell
.\scripts\bootstrap-admin.ps1 -Username InitialAdmin -Profile production -JarPath $ServerJar -ExpectedSha256 $ServerJarSha256
.\scripts\bootstrap-admin.ps1 -UserId $ExistingUserId -Profile production -JarPath $ServerJar -ExpectedSha256 $ServerJarSha256
.\scripts\reset-admin-password.ps1 -Profile production -JarPath $ServerJar -ExpectedSha256 $ServerJarSha256
```

Relative paths use the caller's current directory; defaults use the script location. Literal filesystem paths with spaces are supported. The shared resolver rejects missing/non-JAR files and digest mismatch before Java launch. Scripts print resolved path/SHA-256 and forward Java's exit code. Digest equality is not authenticity without a trusted source. Passwords remain in the application's hidden console; no password parameter or automatic bootstrap was added.

Nine external PowerShell cases exercised actual scripts: username/UUID bootstrap and reset, spaced path, digest match/mismatch/format, missing file and exit propagation. Java was replaced at the launch boundary; this does not validate actual hidden-console/database operation. The fixture initially misinterpreted PowerShell -Command exit handling, corrected with an explicit wrapper. No administrator or production database was changed.

The final .3 JAR was then launched by both actual scripts with its hash against only the disposable PostgreSQL acceptance database and no console. Both returned nonzero INTERACTIVE_CONSOLE_REQUIRED; user/credential-version/audit/session/authorization/epoch snapshots were unchanged. This verifies real Java no-console rejection, not successful hidden password entry/bootstrap/reset.

Subsequent [administrator acceptance](administrator-maintenance-acceptance.md) / [Chinese](../zh-CN/operations/administrator-maintenance-acceptance.md) verifies actual scripts/JAR/hash/Windows PTY successful hidden bootstrap and offline reset, browser forced change/recovery-code download and old security-state refusal. The original no-console paragraph records that stage's limits, not the latest overall status.
