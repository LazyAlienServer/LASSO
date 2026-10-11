# ADR-0017: Single external Nginx for the containerized account site

- Status: accepted topology, 2026-10-11; target execution pending
- [中文](../zh-CN/adr/0017-single-external-nginx-container-edge.md)
- Refines [ADR-0016](0016-single-host-compose-account-site-launch.md)

## Decision

The user selected one external Nginx on the Docker host. It terminates public HTTPS and forwards unchanged paths to SSO's container port 9000 through a host IPv4 loopback binding (example `127.0.0.1:19000`). PostgreSQL and management 9091 stay unpublished. A trusted collector may join the private monitoring network. This is a host-installed Nginx, not another container's localhost or a remote proxy.

Supply a standalone Compose file for SSO/PostgreSQL/offline maintenance, its non-secret input template and a host-Nginx site template. Keep the original bundled-proxy template as an independently selected alternative; do not require a proxy image/TLS mount in the host-edge Compose. Retain the original project name, PostgreSQL volume, signing key and external secrets when adapting an existing installation. Stop the old proxy before handing its public ports to the host Nginx.

The host edge replaces all client-controlled Forwarded/alternate forwarding metadata with a single canonical HTTPS host/port and the actual client address. Full account/API/OIDC paths are preserved. Public actuator paths return 404. Do not derive the issuer from the loopback upstream or use arbitrary request Host. The host's configuration/renewal, permissions and firewall remain operator-owned.

## Release boundary

Official domain, public CA and renewal are deferred to the formal target-launch stage at the user's request. Software preparation continues with isolated, explicitly identified TLS fixtures, not invented production inputs. The production profile still requires a valid HTTPS issuer/configuration; a fixture is not public-CA acceptance. Software delivery, target opening and actual-consumer integration remain separate.

The existing `0.1.0-dev.20261009.2` is immutable and does not acquire the new templates/tooling. Source changes need a new clean, identified candidate and corresponding checks. This decision authorizes implementation/docs, not Git commit/push, hosted release or operations on the user's server.

## Consequences

The deployment validator must distinguish bundled and host-edge service/port/network boundaries, reject non-loopback application publication or management/database exposure, and check signing kid syntax. Independent parsing and fixture traffic checks are evidence for the templates; actual Linux listener reachability, certificate trust/renewal, forwarding, Cookie/login, image security, monitoring and recovery are verified on the target before opening.
