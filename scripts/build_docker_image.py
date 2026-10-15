"""Build a verified SSO JAR into a minimal Docker context and retain metadata without secrets."""
import argparse
import hashlib
import json
import re
import runpy
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

sys.dont_write_bytecode = True
from release_layout import ROOT, DOCKER_TEMPLATES as TEMPLATES


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--expected-sha256", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--build-evidence", type=Path, help="Clean package_release.py build-evidence.json for the exact server JAR")
    parser.add_argument("--release-manifest", type=Path, help="Verified extracted package provenance/release-manifest.json (no Git checkout needed)")
    parser.add_argument("--output-dir", type=Path, help="External staging/receipt directory; required for an immutable extracted package")
    parser.add_argument("--allow-dirty-fixture", action="store_true", help="Explicit local-development evidence only")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-fA-F0-9]{64}", args.expected_sha256):
        parser.error("An exact SHA-256 is required")
    jar = args.jar.resolve()
    if not jar.is_file() or hashlib.sha256(jar.read_bytes()).hexdigest() != args.expected_sha256.lower():
        parser.error("Exact JAR/hash mismatch; Docker was not started")
    with zipfile.ZipFile(jar) as archive:
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8").replace("\r\n", "\n").replace("\n ", "")
        fields = dict(line.split(": ", 1) for line in manifest.splitlines() if ": " in line)
        if fields.get("Start-Class") != "org.las.sso.LasSsoApplication":
            parser.error("Expected the SSO server executable")
        if fields.get("Implementation-Version") != args.version:
            parser.error("JAR implementation version differs")
    if args.release_manifest:
        if args.allow_dirty_fixture or args.build_evidence:
            parser.error("Package provenance cannot be mixed with fixture or checkout evidence")
        if args.release_manifest.resolve() != ROOT / "provenance/release-manifest.json":
            parser.error("Use the extracted package's own release manifest")
        if not args.output_dir or args.output_dir.resolve().is_relative_to(ROOT):
            parser.error("Package image staging/receipts must use an external --output-dir")
        # Package verifier checks the entire file inventory, source archive, JAR and test evidence.
        runpy.run_path(str(ROOT / "scripts/verify-package.py"))["verify"]()
        manifest = json.loads(args.release_manifest.read_text(encoding="utf-8"))
        if (manifest.get("version") != args.version or manifest.get("source", {}).get("dirty") is not False
                or manifest["artifacts"]["server"]["sha256"] != args.expected_sha256.lower()
                or (ROOT / manifest["artifacts"]["server"]["path"]).resolve() != jar):
            parser.error("Package source/version/server identity differs")
        revision = manifest["source"]["commit"]
        dirty = False
        args.build_evidence = ROOT / "provenance/verification-summary.json"
    else:
        revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        dirty = bool(subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=all"], cwd=ROOT))
        if dirty and not args.allow_dirty_fixture:
            parser.error("Commit intended sources before a release image build")
    fixture = args.allow_dirty_fixture
    evidence_sha = None
    if not fixture:
        if not args.build_evidence or not args.build_evidence.is_file():
            parser.error("Release images require exact clean-build provenance, not a JAR relabeled with current HEAD")
        evidence = json.loads(args.build_evidence.read_text(encoding="utf-8"))
        tree = manifest["source"]["tree"] if args.release_manifest else subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], cwd=ROOT, text=True).strip()
        if (evidence.get("version") != args.version
                or evidence.get("source") != {"commit": revision, "tree": tree, "dirty": False}
                or evidence.get("sourceCommit") != revision
                or evidence.get("artifacts", {}).get("server") != args.expected_sha256.lower()
                or not evidence.get("tests", {}).get("total", {}).get("tests")
                or any(evidence["tests"]["total"].get(key, 1) for key in ("failures", "errors", "skipped"))):
            parser.error("Clean build source/version/hash/test evidence differs; Docker was not started")
        evidence_sha = hashlib.sha256(args.build_evidence.read_bytes()).hexdigest()
    template_hashes = {name: hashlib.sha256((TEMPLATES / name).read_bytes()).hexdigest()
                       for name in ("Dockerfile", "server-entrypoint.sh", ".dockerignore")}
    work = args.output_dir.resolve() if args.output_dir else ROOT / ".build-docker"
    if not work.parent.is_dir():
        parser.error("The staging parent directory must already exist")
    work.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="image-", dir=work) as temporary:
        context = Path(temporary)
        (context / "application").mkdir()
        shutil.copy2(jar, context / "application/server.jar")
        target = context / "deploy/docker"
        target.mkdir(parents=True)
        for name in ("Dockerfile", "server-entrypoint.sh"):
            # Normalize Unix launcher lines even on Windows clones.
            (target / name).write_text((TEMPLATES / name).read_text(encoding="utf-8"), encoding="utf-8", newline="\n")
        shutil.copy2(TEMPLATES / ".dockerignore", context / ".dockerignore")
        subprocess.run(["docker", "build", "--file", str(target / "Dockerfile"), "--tag", args.tag,
                        "--build-arg", "SERVER_JAR=application/server.jar", "--build-arg", "SERVER_SHA256=" + args.expected_sha256.lower(),
                         "--build-arg", "RELEASE_VERSION=" + args.version, "--build-arg", "SOURCE_REVISION=" + revision + ("-unverified-fixture" if fixture else ""), str(context)], check=True)
    if template_hashes != {name: hashlib.sha256((TEMPLATES / name).read_bytes()).hexdigest() for name in template_hashes}:
        parser.error("Docker templates changed during image build; image is not a verified delivery")
    if not fixture and not args.release_manifest:
        if (subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip() != revision
                or subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=all"], cwd=ROOT)):
            parser.error("Source changed during image build; image is not a verified delivery")
    image = json.loads(subprocess.check_output(["docker", "image", "inspect", args.tag], text=True))[0]
    record = {"version": args.version, "sourceCommit": None if fixture else revision, "checkoutBaseCommit": revision,
              "unverifiedFixture": fixture, "checkoutDirty": dirty, "buildEvidenceSha256": evidence_sha,
              "jarSha256": args.expected_sha256.lower(), "imageId": image["Id"],
              "repoDigests": image.get("RepoDigests", []), "tag": args.tag, "productionApproved": False,
               "dockerInputs": template_hashes}
    (work / "image-build.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    print("Image built and identified; image-build.json retained in the selected staging directory.")


if __name__ == "__main__":
    main()
