"""Portable repository and JUnit report checks; Python standard library only."""

import argparse
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
SERVER = ROOT / "las-sso-server"
MODULES = {
    "server": SERVER,
    "starter": SERVER / "las-sso-spring-boot-starter",
    "reference-bff": SERVER / "las-sso-reference-bff",
}
TEXT_SUFFIXES = {
    ".java", ".kts", ".gradle", ".properties", ".toml", ".lockfile", ".md",
    ".json", ".yml", ".yaml", ".sql", ".html", ".css", ".js", ".py", ".ps1",
}
EXTERNAL_DOC_PREFIXES = ("LASLOG/docs/",)


def source_files():
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=ROOT, check=True, capture_output=True,
    )
    return sorted(set(name for name in result.stdout.decode("utf-8").split("\0") if name))


def repository():
    failures = []
    local_links = 0
    external_links = 0
    checked = 0
    files = source_files()
    forbidden_suffixes = {".p12", ".pfx", ".jks", ".keystore", ".key", ".pem", ".log", ".pyc"}
    generated_parts = {".gradle", ".idea", "build", "bin", "target", "__pycache__", ".local"}
    for name in files:
        relative = Path(name)
        path = ROOT / relative
        if not path.is_file():
            failures.append(f"Missing tracked file: {name}")
            continue
        if generated_parts.intersection(relative.parts) or relative.suffix in forbidden_suffixes:
            failures.append(f"Generated or private file is a source candidate: {name}")
        if relative.name == ".env" or (relative.name.startswith(".env.") and relative.name != ".env.example"):
            failures.append(f"Local environment file is a source candidate: {name}")
        if relative.suffix == ".jar" and name != "las-sso-server/gradle/wrapper/gradle-wrapper.jar":
            failures.append(f"Generated JAR is a source candidate: {name}")
        if relative.suffix == ".zip" or relative.name.endswith(".init.gradle"):
            failures.append(f"Local archive/init script is a source candidate: {name}")
        if relative.suffix not in TEXT_SUFFIXES and relative.name not in {
            "gradlew", ".gitignore", ".gitattributes", ".editorconfig",
        }:
            continue
        checked += 1
        try:
            text = path.read_bytes().decode("utf-8")
        except UnicodeDecodeError:
            failures.append(f"Invalid UTF-8: {name}")
            continue
        if text.startswith("\ufeff") or "\ufffd" in text:
            failures.append(f"Unexpected BOM/replacement character: {name}")
        if text and not text.endswith("\n"):
            failures.append(f"Missing final newline: {name}")
        for number, line in enumerate(text.splitlines(), 1):
            markdown_break = relative.suffix == ".md" and line.endswith("  ") and not line.endswith("   ")
            if line.rstrip() != line and not markdown_break:
                failures.append(f"Trailing whitespace: {name}:{number}")
        if relative.suffix == ".json":
            try:
                json.loads(text)
            except ValueError:
                failures.append(f"Invalid JSON: {name}")
        if relative.suffix == ".md":
            if sum(line.startswith("```") for line in text.splitlines()) % 2:
                failures.append(f"Unbalanced Markdown fences: {name}")
            for target in re.findall(r"\[[^\]]*\]\(([^)]+)\)", text):
                parts = urlsplit(target.strip("<>"))
                if parts.scheme or parts.netloc or not parts.path:
                    continue
                destination = (path.parent / unquote(parts.path)).resolve()
                try:
                    destination.relative_to(ROOT)
                except ValueError:
                    # Sibling business docs are external acceptance inputs, not an SSO build dependency.
                    workspace_relative = destination.relative_to(ROOT.parent).as_posix() if destination.is_relative_to(ROOT.parent) else ""
                    if workspace_relative.startswith(EXTERNAL_DOC_PREFIXES):
                        external_links += 1
                        continue
                    failures.append(f"Unexpected external local link: {name} -> {parts.path}")
                    continue
                local_links += 1
                if not destination.exists():
                    failures.append(f"Missing local link: {name} -> {parts.path}")
        if name.startswith("las-sso-server/") and relative.suffix in {".java", ".kts", ".gradle", ".ps1", ".yml"}:
            if re.search(r"[A-Za-z]:[/\\](?:Projects|Users|Program Files|IntelliJ)", text):
                failures.append(f"Workstation path in active build: {name}")
    for module, directory in MODULES.items():
        if not (directory / "gradle.lockfile").is_file():
            failures.append(f"Missing dependency lock: {module}")
    wrapper = SERVER / "gradle/wrapper/gradle-wrapper.properties"
    if not re.search(r"^distributionSha256Sum=[0-9a-f]{64}$", wrapper.read_text(), re.MULTILINE):
        failures.append("Gradle Wrapper distribution checksum missing")
    migrations = list((SERVER / "src/main/resources/db/migration").glob("V*__*.sql"))
    versions = [int(path.name.split("__", 1)[0][1:]) for path in migrations]
    if len(versions) != len(set(versions)) or sorted(versions) != list(range(1, max(versions, default=0) + 1)):
        failures.append("Flyway versions must be unique and sequential")
    if failures:
        for failure in failures:
            print(failure)
        raise SystemExit(1)
    print(f"Repository checks passed: {checked} UTF-8/text candidates, {local_links} local links; {external_links} declared sibling-doc links.")
    print(f"Three dependency locks, Wrapper checksum and {len(migrations)} sequential migrations verified.")


def tests(require_no_skips, output=None):
    grand_total = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
    invalid = False
    summary = {}
    for module, directory in MODULES.items():
        reports = sorted((directory / "build/test-results/test").glob("TEST-*.xml"))
        if not reports:
            print(f"Missing test reports: {module}; run the full Gradle build first.")
            invalid = True
            continue
        totals = dict.fromkeys(grand_total, 0)
        for path in reports:
            suite = ET.parse(path).getroot()
            for key in totals:
                totals[key] += int(suite.attrib.get(key, 0))
        print(f"{module}: " + json.dumps(totals))
        summary[module] = totals
        for key in totals:
            grand_total[key] += totals[key]
        invalid |= totals["tests"] == 0 or totals["failures"] > 0 or totals["errors"] > 0
        invalid |= require_no_skips and totals["skipped"] > 0
    if output is not None:
        if not output.parent.is_dir():
            raise SystemExit("Summary output parent must already exist")
        output.write_text(json.dumps({"modules": summary, "total": grand_total}, indent=2) + "\n", encoding="utf-8")
    if invalid:
        raise SystemExit(1)
    print("Test reports verified: " + json.dumps(grand_total))
    print("These counts reflect files on disk; use a forced complete run for release/CI evidence.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="check", required=True)
    subparsers.add_parser("repository")
    reports = subparsers.add_parser("tests")
    reports.add_argument("--require-no-skips", action="store_true")
    reports.add_argument("--output", type=Path, help="Write count-only JSON; the parent directory must exist")
    args = parser.parse_args()
    if args.check == "repository":
        repository()
    else:
        tests(args.require_no_skips, args.output)


if __name__ == "__main__":
    main()
