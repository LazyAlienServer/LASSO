# Operation workflow index — {{VERSION}}

[Handbook](README.md) · [中文](../zh-CN/operation-index.md) · [API fields](api-reference.md) · [API workflows](api-operations.md)

**Use:** Find the task/actor, then follow its numbered procedure: prerequisites/location → inputs → steps → expected result → failure handling. Success at one stage does not prove later stages. Replace paths/configuration with reviewed targets; private files/hidden prompts carry secrets, examples are not automatically authorized production commands.

## Users and administrators

| Task | Location/identity | Complete procedure |
| --- | --- | --- |
| Register/save initial codes | Anonymous SSO browser | [User U01](user-guide.md) |
| Login/risk prompts | User SSO browser | [U02](user-guide.md) |
| First temporary-password change | Restricted SSO login | [U03](user-guide.md) |
| Display name/login name | Normal user SSO session | [U04–U05](user-guide.md) |
| Normal password/code rotation | User/current password | [U06–U07](user-guide.md) |
| Recovery/manual credential | Browser/valid credential | [U08](user-guide.md) |
| Device revoke/logout | User; verify global scope | [U09](user-guide.md) |
| Client authorization revoke | User/target client | [U10](user-guide.md) |
| Request/inspect/cancel deletion | Ordinary user | [U11](user-guide.md) |
| User/tombstone search | SSO admin read | [Admin A01](administrator-guide.md) |
| Freeze/unfreeze/force logout | Admin/own password/reason | [A02–A03](administrator-guide.md) |
| Verify/issue/deliver/reissue recovery | Admin/verification record | [A04](administrator-guide.md) |
| Create client/deliver settings-secret | Admin/consumer owner | [A05](administrator-guide.md) |
| Edit/disable/enable client | Admin/change window | [A06–A07](administrator-guide.md) |
| Rotate client secret | Admin/backend private config | [A08](administrator-guide.md) |
| Hold/release deletion | Admin/exact request ID | [A09](administrator-guide.md) |
| Audit/security filters/pages | Admin read | [A10](administrator-guide.md) |

## Installation, operation and restoration

| Task | Location/prerequisites | Complete procedure |
| --- | --- | --- |
| Selected container install with one external host Nginx | Package root/host Linux/loopback edge | [N01–N06](host-nginx-deployment.md) |
| Verify package/images/external settings/secrets/permissions | Package root/Linux/Docker | [Docker1–4](docker-deployment.md) |
| Fresh database/migration/admin/first login | Package root/exact project/TTY | [Docker5–8](docker-deployment.md) |
| Selected JAR/systemd method | Package root/target Linux | [J01](jar-deployment.md) |
| Daily checks/stop/resume/restart | Package root/existing install | [O01](operations.md) |
| Offline sole-admin reset | No writers/real TTY | [O02](operations.md) |
| Software upgrade/compatible rollback | New package/original project-data-key/backup | [O03](operations.md) |
| CA renewal/reload | External TLS/proxy | [O04](operations.md) |
| Dump/check/encrypted off-host backup | Backup role/selected storage | [B01](backup-and-recovery.md) |
| Isolated restore/epoch-key-consumer barriers | Separate restore.env | [B02 and §2](backup-and-recovery.md) |
| Scrape/rules/notifications | Collector/AM deployment | [M01](monitoring.md) |
| Grafana data/Viewer checks | Existing Grafana/Prometheus | [M02](monitoring.md) |
| Firing/resolved drill | Isolation/authorized window | [M03](monitoring.md) |
| Rotate collector password | Coordinated SSO/collector | [M04](monitoring.md) |
| History report/classification/tuning | Read-only backup role | [H01–H02](history-retention.md) |
| Reference queue/replay/purge/AES rotation | Real PowerShell/independent DB | [R01–R04](reference-revocation.md) |
| Layered diagnosis/repair verification | Private bounded logs | [T01](troubleshooting.md) |
| Software/target acceptance | Independent tests/owner | [V01–V02](acceptance.md) |

## API and consumer integration

| Task | Procedure |
| --- | --- |
| OpenAPI/authentication/result handling | [F01–F02](api-operations.md) |
| Registration/two-step recovery | [F03](api-operations.md) |
| Each account/admin operation | [F04–F05](api-operations.md) and U/A procedures |
| Eligibility/pagination | [F06](api-operations.md) |
| OIDC code/forms/reauthentication/UserInfo | [F07](api-operations.md) |
| Refresh/revoke/introspect/local-global logout | [F08](api-operations.md) |
| Private health/metrics | [F09](api-operations.md) |
| Verification record | [F10](api-operations.md) |
| Registration/fixed SDK/one chain | [I01–I02](external-integration.md) |
| Browser isolation/restart/refresh faults/logout | [I03–I04](external-integration.md) |
| One-minute eligibility/fifteen-minute password | [I05](external-integration.md) |
| Complete/maintain integration | [I06](external-integration.md) |

## Verify each stage before proceeding

1. Start with procedure ID/exact target/version and its identity/origin/backup prerequisites.
2. Check every command exit/status, stop on failure; pasting the entire block and reading only the last command is insufficient.
3. Use appropriate read-back/audit/fresh login/actual notification; documentation/syntax success is not execution.
4. Unknown result first queries state, not blind secret/refresh repetition; protocol200 not successor-family cleanup.
5. Record safe outcomes/unexecuted platform boundaries. Software delivery, target opening and consumer acceptance are separate, not expanded V1 scope.
