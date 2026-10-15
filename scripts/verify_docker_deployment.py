"""Validate non-secret Docker inputs and the rendered Compose boundary; no resource provisioning."""
import argparse
import ipaddress
import json
import math
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlsplit

sys.dont_write_bytecode = True
from release_layout import DOCKER_TEMPLATES

DEFAULT = DOCKER_TEMPLATES / "compose.yml"


def require(condition, code):
    if not condition:
        raise ValueError(code)


def validate(path, compose, topology="bundled-proxy"):
    require(topology in {"bundled-proxy", "host-nginx"}, "TOPOLOGY_INVALID")
    host_edge = topology == "host-nginx"
    values = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        name, separator, value = raw.partition("=")
        require(separator and name not in values and name.startswith("LAS_SSO_"), "INPUT_FILE_INVALID")
        require(not any(word in name for word in ("PASSWORD", "PEPPER", "CAPTCHA", "TOKEN", "HASH")), "RAW_SECRET_INPUT_FORBIDDEN")
        require(bool(value.strip()), "INPUT_VALUE_MISSING")
        values[name] = value.strip()
    host = values.get("LAS_SSO_HOST", "")
    try:
        ipaddress.ip_address(host)
    except ValueError:
        pass
    else:
        raise ValueError("DNS_HOSTNAME_REQUIRED")
    require(bool(re.fullmatch(r"(?=.{1,253}$)[a-z0-9]+(?:[a-z0-9.-]*[a-z0-9])?", host))
            and all(re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?", label) for label in host.split("."))
            and "." in host and not any(host == suffix or host.endswith("." + suffix)
                                        for suffix in ("example", "example.com", "example.org", "example.net", "localhost")), "CANONICAL_HOST_INVALID")
    https = int(values.get("LAS_SSO_HTTPS_PORT", "443"))
    http = int(values.get("LAS_SSO_HTTP_PORT", "80"))
    require(1 <= https <= 65535 and 1 <= http <= 65535 and https != http, "EXTERNAL_PORT_INVALID")
    issuer = urlsplit(values.get("LAS_SSO_ISSUER", ""))
    require(issuer.scheme == "https" and issuer.hostname == host and issuer.port in (None, https)
            and (issuer.port or 443) == https and not any((issuer.path, issuer.query, issuer.fragment, issuer.username, issuer.password)), "ISSUER_PROXY_MISMATCH")
    kid = values.get("LAS_SSO_SIGNING_KEY_ID", "")
    lower = kid.lower()
    require(bool(re.fullmatch(r"[A-Za-z0-9._-]{1,128}", kid))
            and not lower.startswith(("las-local", "las-test"))
            and not any(marker in lower for marker in ("local-only", "test-only", "change-me", "changeme")), "SIGNING_KEY_ID_INVALID")
    require(bool(values.get("LAS_SSO_SIGNING_KEY_ALIAS")), "SIGNING_KEY_ALIAS_REQUIRED")
    if host_edge:
        app_port = int(values.get("LAS_SSO_APP_PORT", "0"))
        require(1 <= app_port <= 65535 and app_port not in {http, https}, "LOOPBACK_APP_PORT_INVALID")
    else:
        ipaddress.ip_address(values.get("LAS_SSO_BIND_ADDRESS", ""))
    prefixes = ("LAS_SSO", "LAS_SSO_DB") if host_edge else ("LAS_SSO", "LAS_SSO_DB", "LAS_SSO_PROXY")
    for prefix in prefixes:
        cpus = float(values.get(prefix + "_CPUS", "0"))
        require(math.isfinite(cpus) and cpus > 0, "FINITE_CPU_LIMIT_REQUIRED")
        require(bool(re.fullmatch(r"[1-9][0-9]*(?:[bkmgBKMG])?", values.get(prefix + "_MEMORY_LIMIT", ""))), "FINITE_MEMORY_LIMIT_REQUIRED")
    for name in tuple(prefix + "_IMAGE" for prefix in prefixes):
        require(bool(re.fullmatch(r"(?:sha256:|[^\s]+@sha256:)[a-f0-9]{64}", values.get(name, ""))), "IMMUTABLE_IMAGE_REQUIRED")
    # Capture config privately in memory: only fixed failure codes are emitted.
    result = subprocess.run(["docker", "compose", "--env-file", str(path), "-f", str(compose), "--profile", "maintenance", "config", "--format", "json"], capture_output=True, timeout=60)
    require(result.returncode == 0, "COMPOSE_CONFIGURATION_INVALID")
    config = json.loads(result.stdout)
    require(config.get("name") == values.get("LAS_SSO_PROJECT"), "INHERITED_PROJECT_MISMATCH")
    services = config["services"]
    require(set(services) == ({"sso", "postgres", "maintenance"} if host_edge else {"sso", "postgres", "proxy", "maintenance"}), "SERVICE_SCOPE_INVALID")
    require(services["sso"]["image"] == services["maintenance"]["image"] == values["LAS_SSO_IMAGE"], "MAINTENANCE_IMAGE_MISMATCH")
    require(services["postgres"]["image"] == values["LAS_SSO_DB_IMAGE"], "INFRASTRUCTURE_IMAGE_MISMATCH")
    if not host_edge:
        require(services["proxy"]["image"] == values["LAS_SSO_PROXY_IMAGE"], "INFRASTRUCTURE_IMAGE_MISMATCH")
    resource_services = [("sso", "LAS_SSO"), ("maintenance", "LAS_SSO"), ("postgres", "LAS_SSO_DB")]
    if not host_edge:
        resource_services.append(("proxy", "LAS_SSO_PROXY"))
    for name, prefix in resource_services:
        memory = values[prefix + "_MEMORY_LIMIT"]
        unit = memory[-1].lower() if memory[-1].isalpha() else "b"
        amount = int(memory[:-1] if memory[-1].isalpha() else memory)
        expected_memory = amount * {"b": 1, "k": 1024, "m": 1024 ** 2, "g": 1024 ** 3}[unit]
        require(float(services[name].get("cpus", "0")) == float(values[prefix + "_CPUS"])
                and int(services[name].get("mem_limit", 0)) == expected_memory, "INHERITED_RESOURCE_LIMIT_MISMATCH")
    require(all(services[name]["environment"].get("LAS_SSO_ISSUER") == values["LAS_SSO_ISSUER"]
                and services[name]["environment"].get("LAS_SSO_SIGNING_KEY_ID") == kid
                and services[name]["environment"].get("LAS_SSO_SIGNING_KEY_ALIAS") == values["LAS_SSO_SIGNING_KEY_ALIAS"]
                for name in ("sso", "maintenance")), "INHERITED_ENVIRONMENT_MISMATCH")
    if not host_edge:
        require(services["proxy"]["environment"].get("LAS_SSO_HOST") == host
                and str(services["proxy"]["environment"].get("LAS_SSO_HTTPS_PORT")) == str(https), "INHERITED_ENVIRONMENT_MISMATCH")
    for name in (("maintenance", "postgres") if host_edge else ("sso", "maintenance", "postgres")):
        require(not services[name].get("ports") and not services[name].get("network_mode"), "PRIVATE_PORT_BOUNDARY_INVALID")
    privilege_services = [("sso", "10001:10001"), ("maintenance", "10001:10001")]
    if not host_edge:
        privilege_services.append(("proxy", "101:101"))
    for name, uid in privilege_services:
        service = services[name]
        require(service.get("read_only") is True and service.get("user") == uid
                and service.get("cap_drop") == ["ALL"] and not service.get("privileged")
                and any(option.split(":")[0] == "no-new-privileges" for option in service.get("security_opt", [])), "CONTAINER_PRIVILEGE_INVALID")
        require(not any(key.startswith("JAVA_") or any(word in key for word in ("PASSWORD", "PEPPER", "TOKEN")) for key in service.get("environment", {})), "SECRET_OR_JVM_OVERRIDE_INVALID")
    require(config["networks"]["database"].get("internal") is True
            and config["networks"]["monitoring"].get("internal") is True
            and set(services["sso"]["networks"]) == {"database", "application", "monitoring"}
            and set(services["postgres"]["networks"]) == {"database"}
            and set(services["maintenance"]["networks"]) == {"database"}, "NETWORK_BOUNDARY_INVALID")
    if host_edge:
        service = services["sso"]
        ports = service.get("ports", [])
        require(not service.get("network_mode") and len(ports) == 1, "LOOPBACK_APP_BOUNDARY_INVALID")
        port = ports[0]
        require(port.get("host_ip") == "127.0.0.1" and int(port["target"]) == 9000
                and int(port["published"]) == app_port and port.get("protocol", "tcp") == "tcp", "LOOPBACK_APP_BOUNDARY_INVALID")
    else:
        require(set(services["proxy"]["networks"]) == {"application"}, "NETWORK_BOUNDARY_INVALID")
        require(len(services["proxy"].get("ports", [])) == 2, "PROXY_PORT_BOUNDARY_INVALID")
        published = {int(port["target"]): (port.get("host_ip"), int(port["published"])) for port in services["proxy"]["ports"]}
        require(published == {8080: (values["LAS_SSO_BIND_ADDRESS"], http), 8443: (values["LAS_SSO_BIND_ADDRESS"], https)}, "INHERITED_PORT_MISMATCH")
    require(services["maintenance"]["logging"]["driver"] == "none", "MAINTENANCE_LOGGING_INVALID")
    print(f"Docker input/Compose boundaries verified ({topology}); DNS, target firewall, CA/custody, capacity, backups and notifications need target evidence.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--compose", type=Path, default=DEFAULT)
    parser.add_argument("--topology", choices=("bundled-proxy", "host-nginx"), default="bundled-proxy")
    args = parser.parse_args()
    try:
        validate(args.env_file.resolve(), args.compose.resolve(), args.topology)
    except (ValueError, KeyError, OSError, subprocess.SubprocessError):
        # Never expose actual external input values or Docker/config output on a rejected input.
        parser.exit(1, "Docker input/Compose validation failed; review non-secret inputs and the approved template privately.\n")


if __name__ == "__main__":
    main()
