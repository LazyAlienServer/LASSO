"""Shared source/package locations and module identities; no workstation-specific paths."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERVER = ROOT / "las-sso-server"
MODULES = {
    "server": SERVER,
    "starter": SERVER / "las-sso-spring-boot-starter",
    "reference-bff": SERVER / "las-sso-reference-bff",
}
DOCKER_TEMPLATES = SERVER / "deploy/docker" if SERVER.is_dir() else ROOT / "deploy/docker"


def artifacts(version):
    identifiers = {"server": "las-sso-server", "starter": "las-sso-spring-boot-starter", "reference-bff": "las-sso-reference-bff"}
    return {
        name: directory / f"build/libs/{identifiers[name]}-{version}.jar"
        for name, directory in MODULES.items()
    }
