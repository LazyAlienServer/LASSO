"""Verify an extracted LAS SSO delivery using only Python's standard library."""

import hashlib
import json
import re
import sys
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path, PurePosixPath
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def safe_path(name):
    path = PurePosixPath(name)
    if not name or "\\" in name or path.is_absolute() or ".." in path.parts or ":" in name:
        raise ValueError("Unsafe package path")
    direct = ROOT / path
    resolved = direct.resolve()
    if not resolved.is_relative_to(ROOT) or any(parent.is_symlink() for parent in (direct, *direct.parents)):
        raise ValueError("Path leaves package")
    return resolved


def verify():
    manifest_name = "provenance/release-manifest.json"
    sums_name = "provenance/SHA256SUMS"
    manifest = json.loads((ROOT / manifest_name).read_text(encoding="utf-8"))
    version = manifest["version"]
    if not re.fullmatch(r"0\.\d+\.\d+-dev\.\d{8}\.\d+", version):
        raise ValueError("Unexpected delivery version")
    expected = {}
    for line in (ROOT / sums_name).read_text(encoding="utf-8").splitlines():
        value, name = line.split("  ", 1)
        if name in expected or not re.fullmatch(r"[a-f0-9]{64}", value):
            raise ValueError("Duplicate/invalid checksum entry")
        expected[name] = value
        if digest(safe_path(name).read_bytes()) != value:
            raise ValueError(f"Checksum mismatch: {name}")
    files = {path.relative_to(ROOT).as_posix() for path in ROOT.rglob("*") if path.is_file()}
    if files != set(expected) | {sums_name}:
        raise ValueError("Missing or extra package files")
    inventory = {row["path"]: row["sha256"] for row in manifest["files"]}
    if len(inventory) != len(manifest["files"]):
        raise ValueError("Duplicate manifest file entry")
    if inventory != {name: value for name, value in expected.items() if name != manifest_name}:
        raise ValueError("Manifest file inventory differs from checksum list")
    for row in manifest["files"]:
        if safe_path(row["path"]).stat().st_size != row["bytes"]:
            raise ValueError("File length differs from manifest")
    if expected[manifest["source"]["archive"]] != manifest["source"]["sha256"]:
        raise ValueError("Source archive checksum mismatch")
    with zipfile.ZipFile(safe_path(manifest["source"]["archive"])) as archive:
        if archive.comment.decode("ascii") != manifest["source"]["commit"]:
            raise ValueError("Source git-archive revision mismatch")
    for name, artifact in manifest["artifacts"].items():
        if artifact["sha256"] != expected[artifact["path"]] or version not in artifact["path"]:
            raise ValueError(f"Artifact provenance mismatch: {name}")
        if name in {"server", "reference-bff"}:
            with zipfile.ZipFile(safe_path(artifact["path"])) as archive:
                header = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
                if f"Implementation-Version: {version}" not in header:
                    raise ValueError("Executable JAR version mismatch")
    starter = safe_path(manifest["artifacts"]["starter"]["path"])
    pom = ET.fromstring(starter.with_suffix(".pom").read_bytes())
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    if pom.findtext("m:groupId", namespaces=ns) != "org.las" or pom.findtext("m:version", namespaces=ns) != version:
        raise ValueError("SDK POM coordinate mismatch")
    module = json.loads(starter.with_suffix(".module").read_text(encoding="utf-8"))
    if module["component"]["version"] != version:
        raise ValueError("SDK native metadata coordinate mismatch")
    migrations = json.loads((ROOT / "database/migration-manifest.json").read_text(encoding="utf-8"))
    with zipfile.ZipFile(safe_path(manifest["artifacts"]["server"]["path"])) as archive:
        names = sorted(name for name in archive.namelist() if name.startswith("BOOT-INF/classes/db/migration/") and name.endswith(".sql"))
        if set(names) != {row["jarPath"] for row in migrations["migrations"]}:
            raise ValueError("Embedded migration inventory mismatch")
        for row in migrations["migrations"]:
            if digest(archive.read(row["jarPath"])) != row["sha256"]:
                raise ValueError("Embedded migration checksum mismatch")
    links = 0
    for path in sorted(ROOT.rglob("*.md")):
        text = path.read_text(encoding="utf-8")
        if "{{VERSION}}" in text or sum(line.startswith("```") for line in text.splitlines()) % 2:
            raise ValueError("Unexpanded template or Markdown fence")
        for target in re.findall(r"\[[^\]]*\]\(([^)]+)\)", text):
            parsed = urlsplit(target.strip("<>"))
            if parsed.scheme or parsed.netloc or not parsed.path:
                continue
            destination = (path.parent / unquote(parsed.path)).resolve()
            if not destination.is_relative_to(ROOT) or not destination.exists():
                raise ValueError(f"Broken/nonlocal handbook link: {path.relative_to(ROOT)}")
            links += 1
    summary = json.loads((ROOT / "provenance/verification-summary.json").read_text(encoding="utf-8"))
    if summary["sourceCommit"] != manifest["source"]["commit"] or summary["version"] != version:
        raise ValueError("Verification source/version mismatch")
    if summary["build"]["exitCode"] != 0:
        raise ValueError("Unsuccessful release build")
    for counts in summary["tests"]["modules"].values():
        if counts["tests"] < 1 or any(counts[key] for key in ("failures", "errors", "skipped")):
            raise ValueError("Missing/failed/skipped release tests")
    print(f"Verified {version}: {len(files)} files, {links} local links, artifact/migration/source evidence aligned.")


if __name__ == "__main__":
    try:
        verify()
    except (ValueError, KeyError, OSError, zipfile.BadZipFile) as error:
        print(f"Package verification failed: {error}", file=sys.stderr)
        raise SystemExit(1)
