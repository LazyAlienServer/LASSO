"""Read-only Discovery/JWKS/readiness smoke check; no credential or token output."""

import argparse
import json
import urllib.request
from urllib.parse import urlsplit


def get_json(url):
    with urllib.request.urlopen(url, timeout=10) as response:
        if response.status != 200:
            raise ValueError("Endpoint did not return 200")
        return json.load(response)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--issuer", required=True)
    parser.add_argument("--readiness-url", required=True, help="Private health listener; monitoring moves this to management port")
    args = parser.parse_args()
    issuer = args.issuer.rstrip("/")
    if urlsplit(issuer).scheme != "https":
        parser.error("Use the canonical HTTPS issuer; no insecure TLS option is supported")
    discovery = get_json(issuer + "/.well-known/openid-configuration")
    if discovery.get("issuer") != args.issuer:
        raise ValueError("Canonical issuer mismatch")
    for key in ("authorization_endpoint", "token_endpoint", "jwks_uri", "userinfo_endpoint", "end_session_endpoint", "revocation_endpoint"):
        if urlsplit(discovery.get(key, "")).scheme != "https":
            raise ValueError("Missing/non-HTTPS discovery endpoint")
    keys = get_json(discovery["jwks_uri"]).get("keys", [])
    if not keys or any(set(key) & {"d", "p", "q", "dp", "dq", "qi", "k"} for key in keys):
        raise ValueError("Missing public JWKS or private material exposed")
    if not any(key.get("kty") == "RSA" and key.get("kid") for key in keys):
        raise ValueError("Expected identified RSA public signing key")
    if get_json(args.readiness_url).get("status") != "UP":
        raise ValueError("Database-aware readiness is not UP")
    print("Discovery, public RSA JWKS and readiness passed. Login/refresh/logout/browser checks remain separate.")


if __name__ == "__main__":
    try:
        main()
    except Exception:
        raise SystemExit("Smoke failed; inspect TLS/endpoint/configuration privately (response bodies are not printed).")
