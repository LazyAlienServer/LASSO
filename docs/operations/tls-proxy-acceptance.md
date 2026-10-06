# TLS proxy, forwarding boundary and Secure Cookie acceptance

- Verified2026-10-06 with actual Nginx/TLS/Java containers and Edge. [Chinese](../zh-CN/operations/tls-proxy-acceptance.md), [delivery .20261006.3](../integration/development-delivery-2026-10-06-3.md).
- Source inputs: `las-sso-server/deploy/nginx/las-forwarding.conf` and reference-BFF `application-https.yml`. These are explicit deployment/reference examples, not a production deployment or a trusted-proxy enforcement filter.

## Discovered forwarding defect

The prior Nginx guide replaced X-Forwarded-Host/Proto/Port/For but did not remove a client's standard Forwarded or X-Forwarded-Prefix. A real request through Nginx to Spring's framework forwarding handler generated:

```text
Normal redirect_uri: https://bff.las.test:9443/login/oauth2/code/las-sso
Forged redirect_uri: http://evil.example/forged/login/oauth2/code/las-sso
```

The probe uses no real user grant and prints only the generated redirectUri, not OAuth state/nonce/Code. SSO exact redirect matching remains a second barrier and rejects an unregistered evil URI; this finding does not establish stolen authorization Codes. It does establish that a client-controlled header disturbed the external-host/path boundary and could prevent or misroute login initiation.

The supplied Nginx include now removes Forwarded and prefix/alternate forwarding metadata, and writes one canonical Host/X-Forwarded-Host, scheme, port and observed source IP. The same forged probe produces the correct registered HTTPS callback. Include it in every proxying location; Nginx does not inherit an outer set of proxy_set_header directives when a child location defines its own set. Use exactly one literal canonical server_name per application TLS server, including appropriate unknown-host rejection/default servers. A path-prefix deployment requires its own validated prefix rule; do not re-enable arbitrary client prefixes.

## Required topology/configuration

- Only the controlled TLS edge is externally reachable. SSO/BFF application ports and PostgreSQL must be private/loopback or firewall-restricted; framework forwarding does not independently authenticate the sender of each header.
- The single-hop edge replaces inbound forwarding metadata rather than appending an untrusted chain. For a CDN/multiple proxy topology, establish explicit upstream trust/IP normalization first; this tested single-hop include is not proof of an arbitrary forwarding chain.
- Reject unknown hostnames, redirect HTTP to a fixed HTTPS origin, configure a real certificate and align external issuer/client callback/logout/Back-channel URIs exactly.
- Place signing keys only in SSO and its external secret settings; TLS private keys only at the edge. The reference BFF needs only its chosen confidential client secret and issuer.
- Inspect access/error logging separately. Avoid query strings and request bodies containing Tokens/authorization Codes/credentials. The acceptance edge disables access logging; actual production errors/log aggregation still require secret review.

Example include, from a TLS server with exactly one canonical host and a private upstream:

```nginx
location / {
    proxy_pass http://127.0.0.1:9000;
    proxy_http_version 1.1;
    include /etc/nginx/las-forwarding.conf;
}
```

Copy the recorded repository include to the selected deployment path. Its server_port/scheme are the actual edge listener values; an upstream load balancer using another external port needs an explicit canonical override, not client-supplied values. It has no database migration/dependency and does not prescribe a workstation port/domain.

## Reference BFF HTTPS profile

The opt-in `https` profile removes reference-local identity defaults by requiring environment-selected issuer/client settings and aligns the Cookie name with Starter logout handling:

```text
LAS_SSO_ISSUER=<exact external HTTPS issuer>
LAS_SSO_CLIENT_ID=<registered confidential client>
LAS_SSO_CLIENT_SECRET=<external secret>
```

```powershell
.\gradlew.bat :las-sso-reference-bff:bootRun --args="--spring.profiles.active=https --spring.config.additional-location=$HttpsConfigUri"
```

It sets framework forwarding, host-only `__Host-las_reference`, Secure/HttpOnly/SameSite=Lax/Path=/ and the same `las.sso.client.session-cookie-name`. `application-security` can be combined explicitly. No Domain is set; upstream HTTP is permitted only inside the controlled private trust boundary. Default local BFF remains HTTP/local-only; this profile alone does not restrict the listener, verify certificate issuance, provision secrets or register clients. Exact origins and private listener/firewall settings must come from deployment. The SSO base configuration already supplies its Secure `__Host-las_sso` Cookie.

## Real executed evidence

Dedicated Docker network, PostgreSQL18.4, EclipseTemurin Java21.0.12.1 containers, Nginx1.29.8, final .20261006.3 bootJARs, persistent RSA3072 SSO key, independent disposable TLS CA and leaf certificate for auth.las.test/bff.las.test. Only proxy listeners are mapped to workstation loopback. The applications/database publish no host ports. The final proxy mounts only Nginx config/forwarding include/TLS cert/private key, not the SSO signing key/client secret. App mounts are similarly explicit. This is network isolation/port-binding evidence, not validation of production firewall rules or host-administrator restrictions.

Node requests validate the CA chain/SNI/certificate hostname using per-request CA trust. Unknown CA and wrong hostname are explicitly rejected. Server-side JVM clients use a fixture-only PKCS#12 truststore; no trust-all TLS code was added. Edge uses a per-process host resolver and **only this leaf's SPKI exception** for its private CA, not general ignoreHTTPS errors or global trust/hosts installation. Browser traffic is genuinely HTTPS with real Secure cookies, but public CA/OS browser trust-store provisioning remains deployment acceptance. The two names share one registrable site; unrelated-site SameSite behavior is not accepted by this drill.

Default and application-owned BFF chains both pass:

| Area | Actual assertions |
| --- | --- |
| Edge routing | CA/hostname-validated discovery/endpoints, fixed issuer, registered HTTPS callback even under forged Forwarded/X-Forwarded-* and Prefix, HTTP308 fixed origin/no cookie, unknownHost421, actuator404, HSTS present |
| Private upstream | SSO/BFF/DB Docker PortBindings empty; only proxy loopback listener published |
| Registration/login | Actual ALTCHA widget/API/CSRF registration and two independent password logins, real Code/PKCE/Token/JWKS/UserInfo |
| Cookies | Host-only __Host names, Secure/HttpOnly/SameSite=Lax/Path=/, no Domain attribute, not visible to document.cookie at either origin, browser HTTP request does not send the login Cookie |
| Reauthentication | Standard promptlogin/max_age0 harness request, browser-bound challenge, foreign browser401/Origin403, wrong password401/no new evidence, correct password advances signed auth_time/stable sid/HTTPS issuer |
| Current/global logout | Missing local CSRF403 keeps login, current logout clears owning __Host Cookie while other browser stays logged in, ordinary SSO reentry, correct global return state, forged return400, actual CA-validating HTTPS signed Back-channel ends other browser |
| Proxy network risk | Different forged Forwarded/For/RealIP values still share the observed network budget; CAPTCHA required after two fixture failures, actual proof consumed, block after third; correct credentials plain and two literal encoded login paths still429/Retry-After despite spoofed headers |

Risk thresholds are fixture2/3 and jitter0, not production20/50 rollover/saturation evidence. Dedicated rate-limit rows are cleared between modes; no real database policy changed. Current tests use browser registration/login and HTTP negative probes; no custom resource server/database schema/business permission or consumer15-minute gate was introduced. Core Java code and dependency locks are unchanged in this delivery.

Initial fixture failures were ordering/tooling: BFF discovery started before SSO readiness and got502; BusyBox wget lacks the attempted CA option; Python readiness failed while the hostname-validating Node probe worked; a timed-out initial request loop was bounded and replaced. They are not product defects. The forwarding-prefix defect was separately reproduced under the prior header specimen and fixed with the actual repository include. The complete final-JAR/profile/include flow then passed in both modes.

## Boundaries left open

Official DNS/domain/client configuration, publicly trusted production certificate/renewal, host/container hardening and firewall/allowlist, multiple CDNs/proxies/prefix deployments, cross-site identity domains, real threshold windows/live refresh expiry, certificate/key rotation, stale JWKS disaster barriers, sustained capacity/monitoring/full scans and business consumer adoption remain separate. This closes the isolated single-hop TLS/browser/proxy boundary and fixes the example header input; it does not announce production readiness.
