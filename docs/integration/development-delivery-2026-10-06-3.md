# TLS proxy and Secure Cookie development delivery — 2026-10-06

- ID **0.1.0-dev.20261006.3**, local identified combination, not production deployment. Previous delivery IDs/content remain historical.
- [Chinese](../zh-CN/integration/development-delivery-2026-10-06-3.md), [manifest](development-delivery-2026-10-06-3.json), [TLS acceptance/procedure](../operations/tls-proxy-acceptance.md).

## Scope and repair

The prior Nginx example overwrote X-Forwarded-Host/Proto/For but left standard Forwarded and X-Forwarded-Prefix client-controlled. A real Nginx1.29.8/Boot4.1.0/Security7.1.1 probe generated an HTTP evil-host/prefix callback instead of the registered BFF HTTPS callback. SSO exact callback validation still rejects an unregistered URI; the finding is external-origin/path disturbance, not proven authorization-Code theft.

New `deploy/nginx/las-forwarding.conf` strips alternate forwarding metadata and supplies canonical server_name/scheme/listenerPort/sourceIP. Every proxying location must use the full include; the deployment guide is corrected for both public and restricted actuator locations. Unknown hosts need an explicit rejecting default server and HTTP-to-HTTPS fixed-origin redirects. Framework forwarding itself is not a peer trust/allowlist filter; private app ports/firewall and a controlled single-hop edge are required. CDN/multihop/path-prefix deployments require separate trust rules.

Reference BFF gains opt-in `https` profile: externally supplied issuer/clientID/clientSecret, Secure/HttpOnly/SameSiteLax/host-only __Host-las_reference/Path=/ and aligned starter cookie cleanup. It does not provision certificates, register clients or restrict the listener; deployment supplies those. Defaults remain local HTTP. The SSO base Secure __Host Cookie remains unchanged.

No core Java, public API, dependencies, lockfiles, migrations, credential factors or business policies changed. New profile and include are deployment/reference inputs; the prior forwarding defect was reproduced before the include was used. Same forged request now generates the correct registered callback. Token/state/nonce values are never output by probes.

## Build and artifact consumption

From las-sso-server/, Java21/Docker:

```powershell
.\gradlew.bat test :las-sso-reference-bff:compileJava :bootJar :las-sso-reference-bff:bootJar :las-sso-spring-boot-starter:generatePomFileForStarterPublication :las-sso-spring-boot-starter:generateMetadataFileForStarterPublication "-PlasSsoServerVersion=0.1.0-dev.20261006.3" "-PlasSsoStarterVersion=0.1.0-dev.20261006.3" --rerun-tasks --no-daemon --console=plain
.\gradlew.bat :las-sso-spring-boot-starter:publishStarterPublicationToLasSsoRepository "-PlasSsoPublishRepository=$DevelopmentRepositoryUri" "-PlasSsoStarterVersion=0.1.0-dev.20261006.3" --no-daemon --console=plain
```

Forced build about **10:39–10:43 Asia/Shanghai**: **486server+193starter+37referenceBFF=716**, zero failures/errors/skips,20tasks executed, both bootJARs and publication metadata. Database tests ran. Shutdown pool warnings from stopped Testcontainers fixtures retain their known scope; no failed/skipped result is hidden. Counts unchanged because actual TLS/proxy acceptance is an external real-process/browser harness, not a new Gradle mock suite for reversible configuration.

The starter .3 was published only into isolated local file repository; independent native.module and POM-only projects compiled public old/new APIs and resolved all Security7.1.1 with no composite/inherited locks. Starter bytehash unchanged is intentional. Reference bootJAR contains the actual https profile. No MavenCLI, hosted signature/distribution or deployment occurred.

## Real topology and trust

Final JARs run under EclipseTemurin21.0.12.1 on a dedicated Docker network with PostgreSQL18.4 and Nginx1.29.8. Applications/database publish no host ports. Only proxy HTTP/HTTPS listeners map to workstation loopback. Two container-local DNS aliases and per-process browser resolver map the fixture HTTPS origins; no hosts-file edits. Persistent RSA3072 SSO key and independent short-lived TLS CA/leaf. Final proxy mounts only config/forwarding include/TLS cert-key, not signing key or ClientSecret; app mounts are limited similarly. Test containers are an acceptance fixture, not hardened production manifests.

Node request transport validates CA/SNI/hostname with explicit fixture CA and rejects unknown CA/wronghostname. SSO/BFF server-side HTTPS discovery/Token/JWKS/UserInfo/revocation/Back-channel clients use an explicit JVM truststore, no permissive verifier. Headless Edge/Playwright1.55.1 uses a process-specific resolver and one leaf-SPKI certificate exception for this privateCA, not ignore-allHTTPS/global trust installation. Thus browsers really exchange SecureCookie over HTTPS; production/publicCA and OS certificate renewal/trust are not accepted here. The two origins are different hosts on one site; arbitrary cross-site SameSite flows remain unverified.

Both default and application-owned BFF modes passed **with final manifest-hashed JARs, actual repository include and opt-in https profile**:

- correct Discovery issuer/endpoints, HTTPS redirectURI despite hostile Forwarded/Prefix/For/Proto/Host/Port, fixed HTTP308/no cookie, unknownHost421, HSTS and actuator404;
- actual ALTCHA registration, two independent same-subject password logins and Code/PKCE/JWKS/Token/UserInfo callback;
- host-only __Host Cookie, Secure/HttpOnly/Lax/Path=/, no Domain, JavaScript cannot read it at either origin; browser HTTP request does not send it;
- standard reauth parameters injected by harness only: other browser401, foreignOrigin403, wrong password401/no evidence increase, proper re-entry with later signed auth_time/stable sid/HTTPS issuer;
- missing localCSRF403 retains login, current logout clears its own Cookie and preserves other browser/SSO, ordinary SSO reentry;
- global logout exact HTTPS returnstate/forgedstate400, actual trusted-CA HTTPS signed Back-channel clears other browser and SSO metadata;
- fixture threshold2 CAPTCHA, actual widget proof and threshold3 block use the observed edge network; forged different Forwarded/IP headers cannot evade it, plain/encodedlogin valid-password requests remain429/RetryAfter.

Rate-limit rows were cleared only in the isolated database between mode runs; threshold2/3/jitter0 is not a live production20/50 rollover/capacity guarantee. Main SecureCookie/login flows are browser-managed, not hand-transported over HTTP. Raw-HTTP/Origin negative probes carry only private-memory Cookie/CSRF to exercise the boundary. Reauthentication harness parameters are not an application pending-operation/fifteen-minute gate. No verified identity permissions were added.

Initial environment issues were startup502 from BFF discovery before SSO readiness, missing BusyBox CA option, readiness-tool mismatch and unbounded probe retries; they were corrected separately, not classified as product bugs. The header defect has its distinct red/green real-network evidence. Eight final container logs passed targeted fixturepassword/keypassword/pepper/clientsecret and JWT/Bearer/recovery/cookie pattern checks; full scanning remains open.

## Provenance

Base8cee3ed62fd7c53664e8026b99664c73fae5f19d / branchcodex/fix-sso-quality-review **plus uncommitted worktree**. Source286files fingerprint `cdd5763e10b60ee8aa909d8861901c61316825ce7c15e1143a76b01af445b7e6` now includes `deploy` scope and the https profile; not a direct284-scope comparison. Manifest12artifact/script/migration/config hashes plus3unchanged locks, paths relative to las-sso-server, excludes docs/build outputs/secret external fixtures.

| Artifact | SHA-256 |
| --- | --- |
| Server bootJAR | 0f7027c217b284ef80fe1f4fe647d6d2792f9aba90b94989de4ff84352db269c |
| Reference BFF bootJAR | 387bb87aca5709f4250d5fe61c502d06a91a5c09d4d5796f934aab9e8c8283d8 |
| Starter JAR | 35385b3cb40f63a08a2236f3f33898e02a7956b5c2b1fa385bec309b1dc3ba20 |

Accepted container image digests: Nginx `sha256:5616878291a2eed594aee8db4dade5878cf7edcb475e59193904b198d9b830de`; EclipseTemurin `sha256:cff19e6215689161eb6162c11b86b0c60ddf802164f2eaf48d570f8fb79a36c5`. These identify pulled fixtures, not production support/scan claims.

Default versions remainSNAPSHOT; .3 changed inputs do not overwrite prior IDs. Hashes identify local content, not release signatures or committed reproducibility. No commit/push/productionDB/official certificate/remote publication/global trust/hosts change occurred. Cleanup of fixture containers/network/key-secret files is recorded separately below.

Final cleanup removed all four dedicated application/proxy/database containers and database volume, their private network, external issuer/client/password/pepper settings, generated TLS certificate/private key and binary signing/trust stores. No global CA/hosts installation had been made. Acceptance ports closed; fixture logs retain only the checked non-secret execution evidence.

## Remaining

OfficialdomainDNS/cert issuance-renewal/exact clientURIs, realfirewall/host/containerhardening/CDNforwardtrust/pathprefix/cross-site flows, productionIPthresholdwindows/refresh timing/sizing/monitoring/fullSCA-SAST-Secret scans and applicationconsumer adoption remain. B1pre-submitcrash/persistentSessionindexreplay/custommultiinstance, queueDeadKeyrotation, backupRPO/RTO/keycustody/staleJWKSbarriers/actualconsumer15m-epochgates and hosted signedcommitted release remain separate. This completes isolated single-hop TLS/proxy/SecureCookie acceptance and repairs deployment inputs; production readiness is not declared.
