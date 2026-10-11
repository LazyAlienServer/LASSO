# External Host-Nginx Preparation — 2026-10-11

[中文](../zh-CN/operations/external-nginx-validation-2026-10-11.md) · [ADR-0017](../adr/0017-single-external-nginx-container-edge.md) · [N01–N06](../../release/_source/docs/en/host-nginx-deployment.md) · [Earlier handbook checks](operation-handbook-validation-2026-10-11.md)

## Selected scope and source

Continuation: after the checks below the user explicitly authorized grouped local commits and clean `0.1.0-rc.1` build/scans/package/acceptance. The uncommitted references are this record's earlier verification scope, not the eventual candidate provenance. No push or target-server operation was requested.

The user confirmed single external same-host Nginx and deferred official domain/public HTTPS to formal target launch. Software preparation continues with identified isolated fixtures. Actual target DNS/CA/renewal, forwarding, login, monitoring, backup and capacity are not marked complete. The user reports SSO/PostgreSQL started on their server; this record is local verification, not evidence collected from that server.

All changes remain uncommitted above HEAD `297cdfc0dcf06a9db421064d5328b7bb71f07859`; no new release candidate is created. Historical `.2` archives remain immutable. A clean candidate needs explicit local commit authorization and an exact selected version, followed by its own build/scans/acceptance.

## Implemented preparation

- Standalone `compose.host-nginx.yml`: SSO/PostgreSQL/offline maintenance, one SSO port mapped only to IPv4 loopback, management/PostgreSQL unpublished, original project volume naming preserved. No bundled-proxy image/resource/TLS mount is needed.
- Separate host-edge non-secret env and host-Nginx site templates, full canonical forwarding include and N01–N06 bilingual install/adapt/upgrade/metrics guidance. Existing bundled template remains an alternative with its own helper.
- Validator `--topology host-nginx`: exact service scope, project/environment/image/resource identity, private networks, privileges and loopback-only app port; rejects wildcard/management/DB/maintenance publication and unsafe signing IDs. No external input values are printed on rejection.
- Infrastructure builder accepts PostgreSQL-only requests while keeping old two-image usage; receipts distinguish a dirty fixture from verified source.
- Explicit private bcrypt generation, cost/prefix/original-password distinction and kid/monitoring startup troubleshooting. Formal production inputs remain future target work, not weakened production gates.

## Completed checks

| Layer | Actual result |
| --- | --- |
| Python tool tests | 14 passed: nine existing release-tool cases plus five topology/infrastructure tests with negative variants |
| Real Docker Compose | Both source templates parsed; host validation passed without proxy inputs; same project gives the same postgres_data volume name |
| Nginx syntax/TLS probe traffic | Host site template and forwarding include tested in an isolated Nginx fixture against a probe backend |
| HTTP behavior | Canonical redirect 308, this site's wrong Host 421, actuator paths 404, full OIDC path/query preserved |
| Spoofed metadata | Forwarded/prefix removed; host/HTTPS port/proto/client-address overwritten by edge |
| Bilingual rendered handbooks | 18 pages/63 workflows each, all 59 API operations covered, 451 local links |
| Example parsing | 66 Bash, 16 PowerShell, two JavaScript blocks accepted; ten prior stubbed failure-stop checks remain passing |

The edge traffic fixture used a probe backend, an explicitly trusted one-day self-signed certificate and container test sockets. It verifies forwarding/template behavior, not real SSO OIDC acceptance, public CA, Linux host access control or certificate renewal.

## Standalone Compose runtime fixture

A separately identified dirty-workspace SNAPSHOT was built into a fixture image using the existing current Java artifacts and the source launcher. This is intentionally `unverifiedFixture=true`, `sourceCommit=null`, `productionApproved=false`; it is not a clean candidate or new formal delivery.

- JAR SHA256: `4f2f9f29cd56b2569f84eadb59a6a9485beb627754bfed861e7ad33b822121c1`.
- Fixture image ID: `sha256:7520fa4913b66ce19e2b8bc183c90801b80384c4c1541c5e638c300c8da82571`.
- New isolated project/database, independent random secret files/signing key and existing PostgreSQL18.4 fixture image; original local/user data untouched.
- Exact new standalone Compose: offline V1–V16 migration, migrator ownership/runtime permission adjustment and hidden-console bootstrap passed.
- Web readiness UP; host-loopback `/login` returned200. Docker inspection found only `9000/tcp` bound to `127.0.0.1:<fixture-port>`; no 9091/DB publication.
- Metrics returned401 without credentials and200 with the independent original password via stdin curl configuration. SSO restart returned to readiness UP.
- Fixture containers/networks/database volume, generated keys/config/secrets and image tag were removed. Restricted count-only/diagnostic evidence is retained outside the repository.

This did not execute host-installed Nginx on the Windows build machine, connect the edge probe fixture to real SSO, complete browser forced-change/recovery/OIDC/refresh/logout, test populated upgrade/restore or prove Linux ACLs. These still belong to exact-candidate and target exercises; the old 843-case JVM run is retained as its dated workspace result.

## Remaining release work

Final repository checks passed 589 UTF-8/text candidates, 1,765 local links, one declared sibling link, three locks, Wrapper checksum and sequential V1–V16. `git diff --check` passed with existing CRLF-conversion warnings only. Gitleaks8.30.1/redact100 scanned 605 tracked/unignored source candidates including this work: exit0/zero findings; private path/hash manifest and redacted report retained. The immutable `.2` ZIP remains SHA256 `1e9d5782536cb0eec00f9175060b3ed4291e81a14be72f230e701c2a001b38eb`. Source checks are not final artifact/history/image scans; the final scan is repeated after including these result paragraphs.

Review/commit the intended implementation/docs/tooling with user authorization, select the new candidate, run strict clean exact-version build/publication/current scans, independently package/extract/consume/build, then execute supported end-to-end account/OIDC/maintenance/upgrade/restore exercises. Formal domain/public HTTPS and the real server's operational acceptance follow at target launch as requested. No commit, push, remote hosting or actual production operation was performed here.
