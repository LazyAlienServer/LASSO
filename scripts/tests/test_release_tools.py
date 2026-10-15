"""Independent-package fixtures; never builds or publishes a real delivery."""

import contextlib
import hashlib
import importlib.util
import io
import json
import re
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[2]
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT / "scripts"))
from release_version import delivery_kind, template_values
import package_release
import package_files


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class ReleaseToolsTest(unittest.TestCase):
    def test_valid_versions_have_distinct_kinds_and_template_labels(self):
        for version, kind in (("0.1.0-dev.20261009.2", "development-delivery"),
                              ("0.1.0-rc.1", "release-candidate"),
                              ("0.1.0", "software-release"), ("1.2.3", "software-release")):
            with self.subTest(version=version):
                self.assertEqual(delivery_kind(version), kind)
                self.assertEqual(template_values(version)["{{VERSION}}"], version)
                self.assertNotEqual(template_values(version)["{{DELIVERY_EN}}"], "")

    def test_unsafe_ambiguous_or_invalid_versions_are_rejected(self):
        for version in ("../0.1.0", "0.1.0/../../x", "01.1.0", "0.01.0", "0.1.00",
                        "0.1.0-rc.0", "0.1.0-rc.01", "0.1.0-dev.20260230.1",
                        "0.1.0-dev.20261009.0", "0.1.0-SNAPSHOT", "v0.1.0", "0.1.0+local", "0.1.0\n"):
            with self.subTest(version=version), self.assertRaises(ValueError):
                delivery_kind(version)

    def test_cli_keeps_existing_version_outputs_immutable(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            (root / "release/0.1.0").mkdir(parents=True)
            with patch.object(package_release, "ROOT", root), patch.object(sys, "argv", ["package_release.py", "build", "0.1.0"]), contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                package_release.main()

    def test_cli_routes_stable_and_rc_versions_without_relaxing_source_build(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(package_release, "ROOT", Path(directory).resolve()), patch.object(package_release, "build") as build:
                for version in ("0.1.0", "0.1.0-rc.1"):
                    with patch.object(sys, "argv", ["package_release.py", "build", version]):
                        package_release.main()
                    build.assert_called_with(version, Path(directory).resolve() / ".build-release" / version)

    def fixture(self, root, version):
        def write(name, data):
            target = root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data if isinstance(data, bytes) else data.encode("utf-8"))
            return target
        artifacts = {}
        for name, prefix in (("server", "application/server"), ("reference-bff", "examples/reference"), ("starter", "sdk/starter")):
            path = f"{prefix}-{version}.jar"
            target = root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(target, "w") as archive:
                archive.writestr("META-INF/MANIFEST.MF", "Implementation-Version: " + version + "\n")
            artifacts[name] = {"path": path, "sha256": digest(target)}
        starter = Path(artifacts["starter"]["path"])
        write(str(starter.with_suffix(".pom")), f'<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>org.las</groupId><version>{version}</version></project>')
        write(str(starter.with_suffix(".module")), json.dumps({"component": {"version": version}}))
        write("database/migration-manifest.json", json.dumps({"migrations": []}))
        source = root / "source/source.zip"
        source.parent.mkdir(exist_ok=True)
        commit = "a" * 40
        with zipfile.ZipFile(source, "w") as archive:
            archive.writestr("README.md", "Synthetic tool fixture; not application evidence.")
            archive.comment = commit.encode("ascii")
        write("README.md", "# Synthetic " + version + "\n[API](api/openapi.json)\n")
        api = json.loads((ROOT / "api/openapi.json").read_text(encoding="utf-8"))
        api["info"]["version"] = version
        write("api/openapi.json", json.dumps(api))
        write("provenance/verification-summary.json", json.dumps({"version": version, "sourceCommit": commit, "build": {"exitCode": 0}, "tests": {"modules": {"fixture": {"tests": 1, "failures": 0, "errors": 0, "skipped": 0}}}}))
        manifest = {"version": version, "kind": delivery_kind(version), "productionApproved": False, "source": {"archive": "source/source.zip", "sha256": digest(source), "commit": commit}, "artifacts": artifacts}
        self.seal(root, manifest)
        return manifest

    def seal(self, root, manifest):
        excluded = {"provenance/release-manifest.json", "provenance/SHA256SUMS"}
        manifest["files"] = [{"path": p.relative_to(root).as_posix(), "bytes": p.stat().st_size, "sha256": digest(p)} for p in sorted(root.rglob("*")) if p.is_file() and p.relative_to(root).as_posix() not in excluded]
        (root / "provenance/release-manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        sums = "".join(f"{digest(p)}  {p.relative_to(root).as_posix()}\n" for p in sorted(root.rglob("*")) if p.is_file() and p.relative_to(root).as_posix() != "provenance/SHA256SUMS")
        (root / "provenance/SHA256SUMS").write_text(sums, encoding="utf-8")

    def verifier(self, root):
        spec = importlib.util.spec_from_file_location("package_verifier", ROOT / "scripts/verify_package.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        module.ROOT = root.resolve()
        return module.verify

    def test_independent_verifier_accepts_dev_rc_and_stable_packages(self):
        for version in ("0.1.0-dev.20261009.2", "0.1.0-rc.1", "0.1.0", "1.0.0"):
            with self.subTest(version=version), tempfile.TemporaryDirectory() as directory:
                root = Path(directory).resolve()
                self.fixture(root, version)
                with contextlib.redirect_stdout(io.StringIO()): self.verifier(root)()

    def test_verifier_rejects_resealed_kind_approval_api_drift_and_skips(self):
        for mutation in ("kind", "approval", "api-version", "api-ref", "skipped"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                root = Path(directory).resolve()
                manifest = self.fixture(root, "0.1.0")
                if mutation == "kind": manifest["kind"] = "development-delivery"
                elif mutation == "approval": manifest["productionApproved"] = True
                elif mutation.startswith("api-"):
                    path = root / "api/openapi.json"
                    api = json.loads(path.read_text())
                    if mutation == "api-version": api["info"]["version"] = "0.1.0-rc.1"
                    else: api["components"]["schemas"]["Fixture"] = {"$ref": "https://untrusted.invalid/schema"}
                    path.write_text(json.dumps(api))
                else:
                    path = root / "provenance/verification-summary.json"
                    data = json.loads(path.read_text())
                    data["tests"]["modules"]["fixture"]["skipped"] = 1
                    path.write_text(json.dumps(data))
                self.seal(root, manifest)
                with self.assertRaises(ValueError): self.verifier(root)()

    def test_verifier_still_rejects_tampered_and_extra_files(self):
        for mutation in ("tamper", "extra"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                root = Path(directory).resolve()
                self.fixture(root, "0.1.0-rc.1")
                (root / ("README.md" if mutation == "tamper" else "extra.txt")).write_text("changed")
                with self.assertRaises(ValueError): self.verifier(root)()

    def test_actual_stable_and_rc_templates_are_independent_and_verifier_imports_packaged_policy(self):
        for version in ("0.1.0", "0.1.0-rc.1"):
            with self.subTest(version=version), tempfile.TemporaryDirectory() as directory:
                # Windows hosted runners may return an 8.3 TEMP alias; compare canonical paths.
                root = Path(directory).resolve()
                package_release.copy_templates(version, root)
                values = template_values(version)
                for path in root.rglob("*.md"):
                    text = path.read_text(encoding="utf-8")
                    self.assertFalse(any(placeholder in text for placeholder in values), path)
                    for link in re.findall(r"\[[^\]]*\]\(([^)]+)\)", text):
                        target = urlsplit(link)
                        if target.scheme or not target.path: continue
                        destination = (path.parent / unquote(target.path)).resolve()
                        self.assertTrue(destination.is_relative_to(root) and destination.exists(), path)
                self.assertEqual({p.name for p in (root / "docs/en").glob("*.md")},
                                 {p.name for p in (root / "docs/zh-CN").glob("*.md")})
                self.assertEqual(json.loads((root / "api/openapi.json").read_text())["info"]["version"], version)
                for source, target, render in package_files.entries(ROOT):
                    actual = (root / target).read_bytes()
                    if render:
                        expected = source.read_text(encoding="utf-8")
                        for token, value in values.items():
                            expected = expected.replace(token, value)
                        self.assertEqual(actual, expected.encode("utf-8"), target)
                        self.assertFalse(any(token.encode() in actual for token in values), target)
                    else:
                        self.assertEqual(actual, source.read_bytes(), target)
                service = (root / "deploy/systemd/las-sso.service").read_text()
                self.assertIn(f"WorkingDirectory=/opt/las-sso/{version}", service)
                for command in ("smoke.py", "build_docker_image.py",
                                "build_docker_infrastructure.py", "verify_docker_deployment.py"):
                    help_result = subprocess.run([sys.executable, str(root / "scripts" / command), "--help"],
                                                 cwd=root, capture_output=True, timeout=30)
                    self.assertEqual(help_result.returncode, 0, help_result.stderr.decode("utf-8", errors="replace"))
                self.fixture(root, version)
                result = subprocess.run([sys.executable, str(root / "scripts/verify-package.py")], cwd=root,
                                        capture_output=True, timeout=30)
                self.assertEqual(result.returncode, 0, result.stderr.decode("utf-8", errors="replace"))
                self.assertFalse(list(root.rglob("__pycache__")))

    def test_stable_package_cannot_mislabel_a_release_candidate_executable(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            manifest = self.fixture(root, "0.1.0")
            jar = root / manifest["artifacts"]["server"]["path"]
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("META-INF/MANIFEST.MF", "Implementation-Version: 0.1.0-rc.1\r\n")
            manifest["artifacts"]["server"]["sha256"] = digest(jar)
            self.seal(root, manifest)
            with self.assertRaisesRegex(ValueError, "Executable JAR version mismatch"):
                self.verifier(root)()


class PackageManifestTest(unittest.TestCase):
    def manifest(self, root, files=None, trees=None):
        path = root / "release/package-files.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"schemaVersion": 1, "files": files or [], "trees": trees or []}), encoding="utf-8")

    def test_unsafe_paths_and_globs_are_rejected(self):
        for value in ("", ".", "../escape", "/absolute", "C:/absolute", "a\\b", None):
            with self.subTest(path=value), self.assertRaises(ValueError):
                package_files.relative_path(value)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            for pattern in ("", "../*", "/**", "a\\*", "C:*"):
                self.manifest(root, trees=[{"source": "docs", "target": "docs", "pattern": pattern, "render": False}])
                with self.subTest(pattern=pattern), self.assertRaises(ValueError):
                    package_files.entries(root)

    def test_missing_inputs_empty_trees_and_generated_files_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            self.manifest(root, files=[{"source": "missing.md", "target": "README.md", "render": True}])
            with self.assertRaisesRegex(ValueError, "missing"):
                package_files.entries(root)
            self.manifest(root, trees=[{"source": "empty", "target": "docs", "pattern": "*.md", "render": True}])
            with self.assertRaisesRegex(ValueError, "no files"):
                package_files.entries(root)
            (root / "generated.pyc").write_bytes(b"fixture")
            self.manifest(root, files=[{"source": "generated.pyc", "target": "scripts/generated.pyc", "render": False}])
            with self.assertRaisesRegex(ValueError, "Generated Python"):
                package_files.entries(root)

    def test_duplicate_case_aliases_and_file_directory_conflicts_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            (root / "input.md").write_text("fixture\n", encoding="utf-8")
            for first, second in (("docs/a.md", "docs/a.md"), ("docs/A.md", "docs/a.md"),
                                  ("docs", "docs/a.md"), ("docs/a.md", "docs")):
                rows = [{"source": "input.md", "target": target, "render": False} for target in (first, second)]
                self.manifest(root, files=rows)
                with self.subTest(targets=(first, second)), self.assertRaisesRegex(ValueError, "conflicting"):
                    package_files.entries(root)

    def test_python_render_and_symlink_sources_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            script = root / "tool.py"
            script.write_text("# {{VERSION}}\n", encoding="utf-8")
            self.manifest(root, files=[{"source": "tool.py", "target": "scripts/tool.py", "render": True}])
            with self.assertRaisesRegex(ValueError, "copied unchanged"):
                package_files.entries(root)
            self.manifest(root, files=[{"source": "tool.py", "target": "scripts/tool.py", "render": False}])
            # Exercise the policy on Windows too, where creating a symlink may require elevation.
            original = Path.is_symlink
            with patch.object(Path, "is_symlink", lambda path: path == script or original(path)):
                with self.assertRaisesRegex(ValueError, "leaves source tree"):
                    package_files.entries(root)

    def test_existing_destinations_are_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            (root / "input.md").write_text("{{VERSION}}\n", encoding="utf-8")
            stage = root / "stage"
            stage.mkdir()
            protected = stage / "README.md"
            protected.write_text("existing\n", encoding="utf-8")
            self.manifest(root, files=[{"source": "input.md", "target": "README.md", "render": True}])
            with self.assertRaisesRegex(ValueError, "destination exists"):
                package_files.copy_files(root, stage, "0.1.0")
            self.assertEqual(protected.read_text(), "existing\n")

    def test_tree_globs_cannot_include_ignored_local_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            (root / "config").mkdir()
            (root / "config/server.example").write_text("public template\n", encoding="utf-8")
            (root / "config/private.env").write_text("private fixture\n", encoding="utf-8")
            self.manifest(root, trees=[{"source": "config", "target": "config", "pattern": "*", "render": False}])
            stage = root / "stage"
            stage.mkdir()
            with self.assertRaisesRegex(ValueError, "source candidate"):
                package_files.copy_files(root, stage, "0.1.0", candidates={"config/server.example"})
            self.assertFalse(list(stage.iterdir()))


if __name__ == "__main__":
    unittest.main()
