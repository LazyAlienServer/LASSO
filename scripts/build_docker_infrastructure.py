"""Build pinned-base PostgreSQL/Nginx security layers and retain exact image/package receipts."""
import argparse
import hashlib
import json
import runpy
import subprocess
import sys
import tempfile
from pathlib import Path

sys.dont_write_bytecode = True
from release_layout import ROOT, DOCKER_TEMPLATES as TEMPLATES


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--postgres-tag", required=True)
    parser.add_argument("--proxy-tag", help="Optional bundled Nginx image; omit for the host-Nginx topology")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--allow-dirty-fixture", action="store_true")
    args = parser.parse_args()
    work = args.output_dir.resolve()
    if not work.parent.is_dir():
        parser.error("Staging parent directory must already exist")
    if (ROOT / "provenance/release-manifest.json").is_file():
        if args.allow_dirty_fixture or work.is_relative_to(ROOT):
            parser.error("Verified package builds require external staging, without a fixture override")
        runpy.run_path(str(ROOT / "scripts/verify-package.py"))["verify"]()
        revision = json.loads((ROOT / "provenance/release-manifest.json").read_text(encoding="utf-8"))["source"]["commit"]
    else:
        revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        if subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=all"], cwd=ROOT) and not args.allow_dirty_fixture:
            parser.error("Commit intended sources before building delivery images")
    work.mkdir(exist_ok=True)
    receipt = {"sourceCommit": None if args.allow_dirty_fixture else revision, "checkoutBaseCommit": revision,
               "unverifiedFixture": args.allow_dirty_fixture, "productionApproved": False, "images": {}}
    requested = [("postgres", args.postgres_tag)]
    if args.proxy_tag:
        requested.append(("proxy", args.proxy_tag))
    for name, tag in requested:
        source = TEMPLATES / (name + ".Dockerfile")
        source_sha = hashlib.sha256(source.read_bytes()).hexdigest()
        with tempfile.TemporaryDirectory(prefix=name + "-", dir=work) as directory:
            context = Path(directory)
            (context / "Dockerfile").write_text(source.read_text(encoding="utf-8"), encoding="utf-8", newline="\n")
            subprocess.run(["docker", "build", "--pull", "--no-cache", "--tag", tag,
                            "--label", "org.opencontainers.image.revision=" + revision + ("-unverified-fixture" if args.allow_dirty_fixture else ""), str(context)], check=True)
        image = json.loads(subprocess.check_output(["docker", "image", "inspect", tag]))[0]
        if source_sha != hashlib.sha256(source.read_bytes()).hexdigest():
            parser.error("Infrastructure template changed during build; image is not a verified delivery")
        packages = subprocess.check_output(["docker", "run", "--rm", "--network", "none", "--entrypoint", "apk", image["Id"], "--no-network", "info", "-v"])
        package_path = work / (name + "-packages.txt")
        package_path.write_bytes(packages)
        receipt["images"][name] = {"tag": tag, "imageId": image["Id"], "repoDigests": image.get("RepoDigests", []),
                                   "dockerfileSha256": source_sha,
                                   "packageListSha256": hashlib.sha256(packages).hexdigest()}
    if not args.allow_dirty_fixture and not (ROOT / "provenance/release-manifest.json").is_file():
        if (subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip() != revision
                or subprocess.check_output(["git", "status", "--porcelain", "--untracked-files=all"], cwd=ROOT)):
            parser.error("Source changed during build; images are not a verified delivery")
    (work / "infrastructure-images.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print("Infrastructure images identified; package/receipt evidence retained. Scan exact images before deployment.")


if __name__ == "__main__":
    main()
