"""Build and package a clean committed LAS SSO development release; standard library only."""

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SERVER = ROOT / "las-sso-server"
MODULES = {
    "server": SERVER,
    "starter": SERVER / "las-sso-spring-boot-starter",
    "reference-bff": SERVER / "las-sso-reference-bff",
}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT).decode("utf-8").strip()


def source():
    if git("status", "--porcelain", "--untracked-files=all"):
        raise ValueError("Commit intended sources first; packaging requires a clean checkout")
    return {"commit": git("rev-parse", "HEAD"), "tree": git("rev-parse", "HEAD^{tree}"), "dirty": False}


def artifacts(version):
    return {
        "server": MODULES["server"] / f"build/libs/las-sso-server-{version}.jar",
        "starter": MODULES["starter"] / f"build/libs/las-sso-spring-boot-starter-{version}.jar",
        "reference-bff": MODULES["reference-bff"] / f"build/libs/las-sso-reference-bff-{version}.jar",
    }


def execute(command, cwd, log, timeout=1200):
    started = now()
    with log.open("wb") as output:
        result = subprocess.run(command, cwd=cwd, stdout=output, stderr=subprocess.STDOUT, timeout=timeout)
    if result.returncode:
        raise ValueError(f"Command failed ({result.returncode}); inspect private build log: {log.name}")
    return {"startedAt": started, "endedAt": now(), "exitCode": result.returncode, "logSha256": sha(log)}


def test_reports():
    result = {"modules": {}, "reports": [], "total": dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)}
    for name, directory in MODULES.items():
        reports = sorted((directory / "build/test-results/test").glob("TEST-*.xml"))
        counts = dict.fromkeys(result["total"], 0)
        for path in reports:
            suite = ET.parse(path).getroot()
            for key in counts:
                counts[key] += int(suite.attrib.get(key, 0))
            result["reports"].append({"path": path.relative_to(ROOT).as_posix(), "sha256": sha(path)})
        if not counts["tests"] or any(counts[key] for key in ("failures", "errors", "skipped")):
            raise ValueError(f"Missing/failed/skipped release tests: {name}")
        result["modules"][name] = counts
        for key in counts:
            result["total"][key] += counts[key]
    return result


def build(version, work):
    revision = source()
    subprocess.run([sys.executable, str(ROOT / "scripts/verify.py"), "repository"], cwd=ROOT, check=True)
    work.mkdir(parents=True, exist_ok=True)
    publication = work / "maven"
    if publication.exists():
        raise ValueError("Build evidence already exists; use a new version or explicitly clear only failed build output")
    wrapper = str(SERVER / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    options = [f"-PlasSsoServerVersion={version}", f"-PlasSsoStarterVersion={version}", "--no-daemon", "--console=plain"]
    command = [wrapper, ":clean", ":las-sso-spring-boot-starter:clean", ":las-sso-reference-bff:clean", "build", "-PrequireDatabaseTests=true", "--rerun-tasks", *options]
    evidence = execute(command, SERVER, work / "build.log")
    publish = [wrapper, ":las-sso-spring-boot-starter:publishStarterPublicationToLasSsoRepository", f"-PlasSsoPublishRepository={publication.as_uri()}", *options]
    publication_evidence = execute(publish, SERVER, work / "publish.log")
    if source() != revision:
        raise ValueError("Source changed during release build")
    tools = {}
    for name, args in {"java": ["java", "-version"], "docker": ["docker", "version", "--format", "{{.Server.Version}}"], "git": ["git", "--version"]}.items():
        result = subprocess.run(args, capture_output=True, text=True, check=True)
        tools[name] = (result.stdout + result.stderr).strip()
    tools["python"] = sys.version.split()[0]
    tools["gradleWrapper"] = (SERVER / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
    evidence["command"] = [Path(wrapper).name, *command[1:]]
    publication_evidence["command"] = [Path(wrapper).name, publish[1], "-PlasSsoPublishRepository=<private-build-file-repository>", *options]
    state = {
        "version": version, "sourceCommit": revision["commit"], "source": revision,
        "build": evidence, "publication": publication_evidence, "tools": tools,
        "tests": test_reports(), "artifacts": {name: sha(path) for name, path in artifacts(version).items()},
        "publicationFiles": {path.relative_to(publication).as_posix(): sha(path) for path in sorted(publication.rglob("*")) if path.is_file()},
        "scans": {"status": "not-executed-for-this-version", "historicalEvidenceIsCurrent": False},
    }
    write_json(work / "build-evidence.json", state)
    print("Strict release build/publish passed: " + json.dumps(state["tests"]["total"]))


def load_evidence(version, work):
    evidence = json.loads((work / "build-evidence.json").read_text(encoding="utf-8"))
    if evidence["version"] != version or evidence["source"] != source():
        raise ValueError("Build source/version differs from clean checkout")
    if evidence["tests"] != test_reports():
        raise ValueError("Test evidence changed after release build")
    if evidence["artifacts"] != {name: sha(path) for name, path in artifacts(version).items()}:
        raise ValueError("Artifact bytes changed after release build")
    publication = work / "maven"
    if evidence["publicationFiles"] != {path.relative_to(publication).as_posix(): sha(path) for path in sorted(publication.rglob("*")) if path.is_file()}:
        raise ValueError("Publication changed after build")
    return evidence


def zip_tree(root, destination, prefix=""):
    with zipfile.ZipFile(destination, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(root.rglob("*")):
            if not path.is_file():
                continue
            info = zipfile.ZipInfo(prefix + path.relative_to(root).as_posix(), (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, path.read_bytes())


def package(version, work):
    evidence = load_evidence(version, work)
    if (work / "scan-summary.json").exists():
        scans = json.loads((work / "scan-summary.json").read_text(encoding="utf-8"))
        if scans["sourceCommit"] != evidence["sourceCommit"] or scans["artifacts"] != evidence["artifacts"]:
            raise ValueError("Scan source/artifact mismatch")
        evidence["scans"] = scans
    stage = work / "package"
    if stage.exists():
        raise ValueError("Staging package already exists; inspect it before retrying")
    stage.mkdir()
    templates = ROOT / "release/_source"
    for path in sorted(templates.rglob("*")):
        if path.is_file():
            target = stage / path.relative_to(templates)
            target.parent.mkdir(parents=True, exist_ok=True)
            text = path.read_text(encoding="utf-8")
            # Executable tools contain template-detection literals, not release substitutions.
            if path.suffix != ".py":
                text = text.replace("{{VERSION}}", version)
            target.write_text(text, encoding="utf-8", newline="\n")
    shutil.copytree(SERVER / "deploy/monitoring", stage / "deploy/monitoring")
    shutil.copytree(SERVER / "deploy/docker", stage / "deploy/docker")
    for language, name in (("en", "README.md"), ("zh-CN", "README.zh-CN.md")):
        target = stage / "deploy/docker" / name
        text = target.read_text(encoding="utf-8").replace("../../../release/_source/docs/", "../../docs/")
        # The package has task handbooks; source-only environment matrices remain in its source ZIP.
        source_matrix = "../../../docs/" + ("zh-CN/" if language == "zh-CN" else "") + "operations/docker-compose-deployment.md"
        text = text.replace(source_matrix, "../../docs/" + language + "/docker-deployment.md")
        source_heading = "## 源码构建入口" if language == "zh-CN" else "## Source-build entry"
        text = text.split(source_heading, 1)[0]
        text = text.replace("源码中的手册是发行模板，`{{VERSION}}` 在打包时替换。实际解压包已有精确版本，无需源码或 Git。", "本包镜像构建按上方教程执行，使用包内精确 JAR/hash 和 manifest，无需源码或 Git。")
        text = text.replace("Source handbooks are templates; packaging substitutes `{{VERSION}}`. Extracted packages have exact versions and need no source/Git.", "Build images using the tutorial above and this package's exact JAR/hash/manifest. No source checkout or Git is needed.")
        target.write_text(text.replace("{{VERSION}}", version), encoding="utf-8", newline="\n")
    for name in ("build_docker_image.py", "build_docker_infrastructure.py", "verify_docker_deployment.py"):
        shutil.copy2(ROOT / "scripts" / name, stage / "scripts" / name)
    shutil.copy2(SERVER / "deploy/nginx/las-forwarding.conf", stage / "deploy/nginx/las-forwarding.conf")
    shutil.copy2(SERVER / "scripts/history-status.sql", stage / "scripts/history-status.sql")
    for path in sorted((SERVER / "scripts").glob("*.ps1")):
        text = path.read_text(encoding="utf-8")
        text = text.replace("[string]$Profile = 'local'", "[string]$Profile = 'production'")
        text = text.replace("[string]$JarPath = (Join-Path $PSScriptRoot '..\\build\\libs\\las-sso-server-0.1.0-SNAPSHOT.jar')", "[string]$JarPath")
        if path.name not in {"maintenance-artifact.ps1", "reference-revocation-maintenance.ps1"}:
            text = text.replace("[ValidateNotNullOrEmpty()]\n    [string]$JarPath", "[Parameter(Mandatory = $true)]\n    [ValidateNotNullOrEmpty()]\n    [string]$JarPath") if "[Parameter(Mandatory = $true)]\n    [ValidateNotNullOrEmpty()]\n    [string]$JarPath" not in text else text
            if "[Parameter(Mandatory = $true)]\n    [ValidatePattern('^[0-9a-fA-F]{64}$')]" not in text:
                text = text.replace("[ValidatePattern('^[0-9a-fA-F]{64}$')]", "[Parameter(Mandatory = $true)]\n    [ValidatePattern('^[0-9a-fA-F]{64}$')]")
        (stage / "scripts" / path.name).write_text(text, encoding="utf-8", newline="\n")
    coordinates = stage / f"sdk/maven/org/las/las-sso-spring-boot-starter/{version}"
    coordinates.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(work / f"maven/org/las/las-sso-spring-boot-starter/{version}", coordinates)
    paths = {
        "server": f"application/las-sso-server-{version}.jar",
        "starter": f"sdk/maven/org/las/las-sso-spring-boot-starter/{version}/las-sso-spring-boot-starter-{version}.jar",
        "reference-bff": f"examples/reference-bff/las-sso-reference-bff-{version}.jar",
    }
    artifact_rows = {}
    libraries = []
    migrations = []
    for name, origin in artifacts(version).items():
        target = stage / paths[name]
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists() and sha(target) != sha(origin):
            raise ValueError("Published SDK differs from built SDK")
        shutil.copy2(origin, target)
        artifact_rows[name] = {"path": paths[name], "sha256": sha(target)}
        with zipfile.ZipFile(target) as archive:
            for entry in sorted(archive.namelist()):
                if entry.startswith("BOOT-INF/lib/") and entry.endswith(".jar"):
                    data = archive.read(entry)
                    libraries.append({"artifact": name, "jarPath": entry, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
                if name == "server" and entry.startswith("BOOT-INF/classes/db/migration/") and entry.endswith(".sql"):
                    migrations.append({"jarPath": entry, "sha256": hashlib.sha256(archive.read(entry)).hexdigest()})
    for name, module in MODULES.items():
        target = stage / f"provenance/dependencies/{name}.gradle.lockfile"
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(module / "gradle.lockfile", target)
    for path in ("gradle/libs.versions.toml", "gradle/wrapper/gradle-wrapper.properties"):
        shutil.copy2(SERVER / path, stage / "provenance/dependencies" / Path(path).name)
    write_json(stage / "provenance/dependencies/nested-libraries.json", libraries)
    write_json(stage / "database/migration-manifest.json", {"source": "executable-server-embedded-Flyway", "migrations": migrations})
    source_zip = stage / f"source/las-sso-source-{version}.zip"
    source_zip.parent.mkdir(parents=True)
    subprocess.run(["git", "archive", "--format=zip", f"--output={source_zip}", evidence["sourceCommit"]], cwd=ROOT, check=True)
    write_json(stage / "provenance/verification-summary.json", evidence)
    source_row = {**evidence["source"], "archive": source_zip.relative_to(stage).as_posix(), "sha256": sha(source_zip)}
    files = [{"path": path.relative_to(stage).as_posix(), "bytes": path.stat().st_size, "sha256": sha(path)} for path in sorted(stage.rglob("*")) if path.is_file()]
    manifest = {"schemaVersion": 1, "version": version, "kind": "development-delivery", "createdAt": now(), "source": source_row, "artifacts": artifact_rows, "files": files, "productionApproved": False}
    write_json(stage / "provenance/release-manifest.json", manifest)
    sums = "".join(f"{sha(path)}  {path.relative_to(stage).as_posix()}\n" for path in sorted(stage.rglob("*")) if path.is_file())
    (stage / "provenance/SHA256SUMS").write_text(sums, encoding="utf-8", newline="\n")
    subprocess.run([sys.executable, str(stage / "scripts/verify-package.py")], check=True)
    output = ROOT / "release" / version
    distribution = ROOT / "release" / f"las-sso-{version}.zip"
    zip_tree(stage, work / "distribution.zip", prefix=version + "/")
    stage.rename(output)
    (work / "distribution.zip").rename(distribution)
    print(f"Delivery created: release/{version}/ and release/{distribution.name}; ZIP SHA-256={sha(distribution)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("build", "package"))
    parser.add_argument("version")
    args = parser.parse_args()
    if not re.fullmatch(r"0\.\d+\.\d+-dev\.\d{8}\.\d+", args.version):
        parser.error("Use a distinct development version, e.g. 0.1.0-dev.20261007.1")
    if (ROOT / "release" / args.version).exists() or (ROOT / "release" / f"las-sso-{args.version}.zip").exists():
        parser.error("Version output already exists; never overwrite a delivery")
    # Build evidence must live outside Gradle's cleanable build directories.
    work = ROOT / ".build-release" / args.version
    {"build": build, "package": package}[args.action](args.version, work)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print(f"Release operation stopped: {error}", file=sys.stderr)
        raise SystemExit(1)
