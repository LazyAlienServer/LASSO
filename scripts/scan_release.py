"""Scan the exact committed release inputs; keep raw reports private and publish metadata only."""

import argparse
import collections
import hashlib
import json
import shutil
import subprocess
import urllib.request
import zipfile
from pathlib import Path

from package_release import ROOT, artifacts, execute, load_evidence, now, sha, write_json


def metadata(report):
    return {"reportSha256": sha(report), "reportBytes": report.stat().st_size}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version")
    parser.add_argument("--gitleaks", type=Path, required=True, help="Installed Gitleaks8.30.1 executable")
    parser.add_argument("--rules", type=Path, help="Existing reviewed java/javascript/security-audit Semgrep rule directory")
    parser.add_argument("--cache", type=Path, help="Existing Trivy cache to seed; database is updated before scans")
    args = parser.parse_args()
    work = ROOT / ".build-release" / args.version
    evidence = load_evidence(args.version, work)
    scan = work / "scan"
    if scan.exists():
        raise ValueError("Scan work already exists; review failed/private outputs before retrying")
    scan.mkdir()
    source_zip = scan / "source.zip"
    subprocess.run(["git", "archive", "--format=zip", f"--output={source_zip}", evidence["sourceCommit"]], cwd=ROOT, check=True)
    with zipfile.ZipFile(source_zip) as archive:
        archive.extractall(scan / "source")
    (scan / "extracted").mkdir()
    (scan / "artifacts").mkdir()
    for name, path in artifacts(args.version).items():
        shutil.copy2(path, scan / "artifacts" / f"{name}.jar")
        with zipfile.ZipFile(path) as archive:
            for entry in archive.namelist():
                if entry.startswith("BOOT-INF/lib/") and entry.endswith(".jar"):
                    directory = scan / "extracted" / name
                    directory.mkdir(exist_ok=True)
                    (directory / Path(entry).name).write_bytes(archive.read(entry))
        if name == "starter":
            shutil.copy2(path, scan / "extracted/starter.jar")
    summary = {"sourceCommit": evidence["sourceCommit"], "artifacts": evidence["artifacts"], "startedAt": now(), "historicalEvidenceIsCurrent": False}
    images = {"trivy": "aquasec/trivy:0.75.0", "semgrep": "semgrep/semgrep:1.179.0"}
    summary["images"] = {name: subprocess.check_output(["docker", "image", "inspect", image, "--format", "{{.Id}}"], text=True).strip() for name, image in images.items()}

    def docker(label, image, command):
        args = ["docker", "run", "--rm", "--mount", f"type=bind,source={scan},target=/out", image, *command]
        return execute(args, scan, scan / f"{label}.log", timeout=900)

    if args.cache:
        shutil.copytree(args.cache, scan / "cache")
    db = docker("trivy-db", images["trivy"], ["--cache-dir", "/out/cache", "image", "--download-db-only", "--no-progress"])
    db_info = json.loads((scan / "cache/db/metadata.json").read_text(encoding="utf-8"))
    summary["trivyDatabase"] = {**db_info, "databaseSha256": sha(scan / "cache/db/trivy.db"), "download": db}
    for label, mode, target in (("locks", "fs", "source"), ("nested-jars", "rootfs", "extracted")):
        command = ["--cache-dir", "/out/cache", mode, "--scanners", "vuln", "--list-all-pkgs", "--format", "json", "--output", f"/out/{label}.json", "--skip-db-update", "--no-progress", "--timeout", "10m", f"/out/{target}"]
        result = docker(label, images["trivy"], command)
        report = scan / f"{label}.json"
        data = json.loads(report.read_text(encoding="utf-8"))
        rows = data.get("Results", [])
        packages = sum(len(row.get("Packages", [])) for row in rows)
        if packages < 1:
            raise ValueError("Scanner identified zero packages; not valid dependency coverage")
        counts = collections.Counter(finding.get("Severity", "UNKNOWN") for row in rows for finding in row.get("Vulnerabilities", []))
        summary[label] = {**result, **metadata(report), "targetCount": len(rows), "packageRows": packages, "vulnerabilitiesBySeverity": dict(counts)}
        print(f"Trivy {label}: packageRows={packages} severityCounts={dict(counts)}")
    expected_rules = {
        "java": "5e652fa9ac09c9fac36bbadc3562a0c8eed1a47438a9d1f545e021ae3c5648b1",
        "javascript": "e65e8449157ef5d587f2c2a0c17ed388910ca90336e514d27941ccd25543cf4e",
        "security-audit": "b109a039df712f30c6d3e25e1e8358053fd0f1c91b92d0e8d2871cd141fe602f",
    }
    rules = []
    hashes = {}
    for pack in expected_rules:
        filename = f"semgrep-{pack}.yml"
        if args.rules:
            data = (args.rules / filename).read_bytes()
            if hashlib.sha256(data).hexdigest() != expected_rules[pack]:
                raise ValueError("Reviewed rule cache hash differs")
        else:
            request = urllib.request.Request("https://semgrep.dev/c/p/" + pack, headers={"User-Agent": "semgrep/1.179.0"})
            with urllib.request.urlopen(request, timeout=60) as response:
                data = response.read()
        (scan / filename).write_bytes(data)
        hashes[pack] = hashlib.sha256(data).hexdigest()
        rules += ["--config", "/out/" + filename]
    result = docker("semgrep", images["semgrep"], ["semgrep", "scan", *rules, "--metrics=off", "--disable-version-check", "--json", "--output", "/out/semgrep.json", "--quiet", "--no-git-ignore", "--max-target-bytes", "2000000", "/out/source"])
    report = scan / "semgrep.json"
    data = json.loads(report.read_text(encoding="utf-8"))
    # No source excerpts or matching values are copied into public evidence.
    findings = [{"ruleId": row["check_id"], "path": row["path"].removeprefix("/out/source/"), "line": row["start"]["line"]} for row in data.get("results", [])]
    summary["semgrep"] = {**result, **metadata(report), "ruleHashes": hashes, "scannedPaths": len(data.get("paths", {}).get("scanned", [])), "errors": len(data.get("errors", [])), "findings": findings}
    print(f"Semgrep: paths={summary['semgrep']['scannedPaths']} findings={len(findings)} errors={summary['semgrep']['errors']}")
    version = subprocess.check_output([str(args.gitleaks), "version"], text=True).strip()
    if version != "8.30.1":
        raise ValueError("Use identified Gitleaks8.30.1")
    leaks = {}
    for label, mode, target, extra in (("source", "dir", scan / "source", []), ("artifacts", "dir", scan / "artifacts", ["--max-archive-depth", "2"]), ("history", "git", ROOT, ["--log-opts=--all"])):
        report = scan / f"gitleaks-{label}.json"
        command = [str(args.gitleaks), mode, str(target), "--redact=100", "--no-banner", "--report-format", "json", "--report-path", str(report), *extra]
        result = execute(command, scan, scan / f"gitleaks-{label}.log")
        rows = json.loads(report.read_text(encoding="utf-8"))
        leaks[label] = {**result, **metadata(report), "findings": len(rows)}
        print(f"Gitleaks {label}: redactedFindings={len(rows)}")
    summary["gitleaks"] = {"version": version, "coverage": leaks}
    summary["endedAt"] = now()
    summary["review"] = "Review finding metadata against the committed call graph; zero findings is not a guarantee of no flaws. No production image/JDK/proxy/DAST coverage."
    write_json(work / "scan-summary.json", summary)
    print("Private scan reports retained; count-only/metadata summary ready for packaging.")


if __name__ == "__main__":
    main()
