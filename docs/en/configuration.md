# Configuration and secrets

[Index](README.md) · [中文](../zh-CN/configuration.md) · [Deployment](deployment.md)

Keep real settings outside the package/source. Templates describe inputs; they do not provision DNS, TLS, collectors or backups. Docker uses configtree secret filenames mapped by Compose, not raw secret environment variables.

## Select the layout

| Layout | Templates |
| --- | --- |
| Default Docker + host Nginx | `deploy/docker/compose.host-nginx.yml`, `deployment.host-nginx.env.example`, `server.properties.example` |
| Bundled proxy | `deploy/docker/compose.yml`, `deployment.env.example` |
| Host JAR | `config/server/server.properties.example`; optional monitoring.properties.example |
| Reference BFF/queue | `config/reference-bff/*.properties.example`, independent client/queue secrets |

Source checkout keeps host/reference examples in `release/templates/config/`; deployment files are in `las-sso-server/deploy/`. Package paths above are stable.

Docker example external layout: `/etc/las-sso/deployment.env`, `config/server.properties`, `secrets/`, `signing.p12`. For an existing installation retain its project name, volume, key and peppers.

## Non-secret inputs

| Input | Meaning |
| --- | --- |
| LAS_SSO_PROJECT | Existing or distinct project, determines volume/network names |
| LAS_SSO_IMAGE / LAS_SSO_DB_IMAGE | Loaded immutable IDs/digests, never moving tags |
| LAS_SSO_HOST / ISSUER | Browser canonical host and HTTPS issuer; no trailing slash/prefix in this layout |
| LAS_SSO_APP_PORT | Unused host loopback port, example19000; not the issuer port |
| LAS_SSO_HTTP_PORT / LAS_SSO_HTTPS_PORT | Actual public edge ports |
| LAS_SSO_CONFIG_DIR / LAS_SSO_SECRET_DIR / LAS_SSO_KEY_PATH | Absolute protected external locations |
| LAS_SSO_SIGNING_KEY_ALIAS | Existing PKCS12 entry, example las-sso-signing |
| LAS_SSO_SIGNING_KEY_ID | Public stable kid: 1–128 `[A-Za-z0-9._-]`; reject las-local/las-test and example markers |
| LAS_SSO_MEMORY_LIMIT / LAS_SSO_CPUS; LAS_SSO_DB_MEMORY_LIMIT / LAS_SSO_DB_CPUS | Reviewed finite limits with host/JVM headroom |

Host edge needs no proxy image, TLS mount or proxy resource input. Container server/management listen internally on0.0.0.0; publication/network boundaries keep them private. Host-JAR settings instead use loopback. Changing issuer changes the identity namespace and requires coordinated consumer configuration.

## Generate independent secret files

Use private Bash, OpenSSL, JDK21 keytool, htpasswd and POSIX ACL tools. Stop on failure; inspect permissions without printing secret contents. For a **new installation**:

```sh
(
  set +x
  set -euo pipefail
  umask 077
  SECRET_DIR='/etc/las-sso/secrets'
  install -d -m 0700 "$SECRET_DIR"
  names=(db-admin-password db-runtime-password db-migrator-password db-backup-password signing-password recovery-pepper transient-pepper captcha-secret)
  for name in "${names[@]}"; do test ! -e "$SECRET_DIR/$name"; done
  set -o noclobber
  for name in "${names[@]}"; do openssl rand -hex 32 > "$SECRET_DIR/$name"; done
)
```

Every file is independently random. DB/key passwords need≥12UTF-8 bytes; pepper/HMAC≥32. Ordinary restart reuses them. Changing peppers can invalidate old digests; rotation is a separately reviewed recovery action.

Create monitoring-hash with an independent24–48character random ASCII password, kept in a password manager/collector file (bcrypt≤72bytes):

```sh
(
  set +x
  set -euo pipefail
  umask 077
  HASH_FILE='/etc/las-sso/secrets/monitoring-hash'
  test ! -e "$HASH_FILE"
  line="$(htpasswd -nBC 12 las-sso-metrics)"
  hash="${line#las-sso-metrics:}"
  [[ "$hash" =~ ^\$2[aby]\$12\$[./A-Za-z0-9]{53}$ ]]
  set -o noclobber
  printf '{bcrypt}%s' "$hash" > "$HASH_FILE"
)
```

htpasswd prompts twice. SSO receives `{bcrypt}`+hash, cost10–14; collector receives original password, userlas-sso-metrics. No username prefix/quotes/spaces. Existing rotation retains old protected recovery material, replaces both settings and recreates SSO as needed.

## Signing key and permissions

Generate persistent signing material, distinct from TLS and queue AES keys:

```sh
keytool -genkeypair -alias las-sso-signing -keyalg RSA -keysize 3072 \
  -sigalg SHA256withRSA -storetype PKCS12 -validity 365 \
  -keystore /etc/las-sso/signing.p12 -dname 'CN=LAS SSO Signing' \
  -storepass:file /etc/las-sso/secrets/signing-password \
  -keypass:file /etc/las-sso/secrets/signing-password
```

Signing alias must match, private/store password same. Select certificate validity/custody for the environment. Keep key/kid stable across restarts.

1. Mode0600 for configuration/key/secret files. Grant SSO UID10001 read access to server.properties/signing.p12 and runtime/signing/pepper/CAPTCHA/monitoring files.
2. Query PostgreSQL UID with `docker run --rm --network none --entrypoint id <exact-db-image> -u postgres`; grant it the four DB passwords. Runtime/migrator files need both readers.
3. Use `setfacl -m u:<uid>:r <file>` or equivalent restricted ownership/groups, inspect with getfacl. Verify actual user-namespace mappings. Host Nginx TLS permissions use host readers, not container UID101.
4. Copy Docker server.properties and fill deployment.env, then run the deployment validator. Input validation does not read PKCS12 or prove target custody; startup reports the exact current error.
