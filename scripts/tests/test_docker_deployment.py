"""Topology/security boundary tests; real Compose parsing is a separate fixture check."""
import contextlib
import copy
import io
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT / "scripts"))
import verify_docker_deployment as deployment
import build_docker_infrastructure as infrastructure


class DockerDeploymentTest(unittest.TestCase):
    def fixture(self, work, host_edge):
        values = {
            "LAS_SSO_PROJECT": "isolated-topology-fixture",
            "LAS_SSO_HOST": "auth.las.test", "LAS_SSO_ISSUER": "https://auth.las.test",
            "LAS_SSO_SIGNING_KEY_ID": "sso-prod-fixture-1", "LAS_SSO_SIGNING_KEY_ALIAS": "signing",
            "LAS_SSO_HTTP_PORT": "80", "LAS_SSO_HTTPS_PORT": "443", "LAS_SSO_APP_PORT": "19000",
            "LAS_SSO_BIND_ADDRESS": "127.0.0.1",
        }
        for prefix in ("LAS_SSO", "LAS_SSO_DB", "LAS_SSO_PROXY"):
            values[prefix + "_IMAGE"] = "sha256:" + "a" * 64
            values[prefix + "_CPUS"] = "1"
            values[prefix + "_MEMORY_LIMIT"] = "128m"
        def service(prefix):
            return {"image": values[prefix + "_IMAGE"], "cpus": "1", "mem_limit": 128 * 1024 ** 2,
                    "read_only": True, "user": "10001:10001", "cap_drop": ["ALL"],
                    "security_opt": ["no-new-privileges:true"], "networks": {"database": {}},
                    "environment": {}}
        sso = service("LAS_SSO")
        sso["networks"] = {name: {} for name in ("database", "application", "monitoring")}
        sso["environment"] = {key: values[key] for key in ("LAS_SSO_ISSUER", "LAS_SSO_SIGNING_KEY_ID", "LAS_SSO_SIGNING_KEY_ALIAS")}
        maintenance = service("LAS_SSO")
        maintenance["environment"] = dict(sso["environment"])
        maintenance["logging"] = {"driver": "none"}
        postgres = service("LAS_SSO_DB")
        services = {"sso": sso, "postgres": postgres, "maintenance": maintenance}
        if host_edge:
            sso["ports"] = [{"target": 9000, "published": "19000", "host_ip": "127.0.0.1", "protocol": "tcp"}]
            for key in list(values):
                if key.startswith("LAS_SSO_PROXY") or key == "LAS_SSO_BIND_ADDRESS":
                    del values[key]
        else:
            proxy = service("LAS_SSO_PROXY")
            proxy["user"] = "101:101"
            proxy["networks"] = {"application": {}}
            proxy["environment"] = {"LAS_SSO_HOST": values["LAS_SSO_HOST"], "LAS_SSO_HTTPS_PORT": "443"}
            proxy["ports"] = [{"target": 8080, "published": "80", "host_ip": "127.0.0.1"},
                              {"target": 8443, "published": "443", "host_ip": "127.0.0.1"}]
            services["proxy"] = proxy
        path = work / "deployment.env"
        path.write_text("".join(key + "=" + value + "\n" for key, value in values.items()), encoding="utf-8")
        return path, {"name": values["LAS_SSO_PROJECT"], "services": services, "networks": {"database": {"internal": True}, "monitoring": {"internal": True}}}

    def check(self, path, config, topology):
        result = subprocess.CompletedProcess([], 0, stdout=json.dumps(config).encode(), stderr=b"")
        with patch.object(deployment.subprocess, "run", return_value=result), contextlib.redirect_stdout(io.StringIO()):
            deployment.validate(path, Path("compose.yml"), topology)

    def test_host_and_bundled_edges_preserve_their_distinct_boundaries(self):
        for host_edge in (True, False):
            with self.subTest(host_edge=host_edge), tempfile.TemporaryDirectory() as directory:
                path, config = self.fixture(Path(directory), host_edge)
                self.check(path, config, "host-nginx" if host_edge else "bundled-proxy")

    def test_host_edge_rejects_public_management_database_and_maintenance_ports(self):
        for mutation in ("wildcard", "ipv6-wildcard", "management", "extra-management", "database", "maintenance", "host-network"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                path, config = self.fixture(Path(directory), True)
                services = config["services"]
                if mutation == "wildcard": services["sso"]["ports"][0]["host_ip"] = "0.0.0.0"
                elif mutation == "ipv6-wildcard": services["sso"]["ports"][0]["host_ip"] = "::"
                elif mutation == "management": services["sso"]["ports"][0]["target"] = 9091
                elif mutation == "extra-management": services["sso"]["ports"].append({"target": 9091, "published": "19091", "host_ip": "127.0.0.1"})
                elif mutation == "host-network": services["sso"]["network_mode"] = "host"
                else: services[mutation if mutation == "maintenance" else "postgres"]["ports"] = [{"target": 5432, "published": "5432"}]
                with self.assertRaises(ValueError): self.check(path, config, "host-nginx")

    def test_validator_rejects_mismatched_topology_inherited_settings_and_privileges(self):
        for mutation in ("topology", "project-override", "kid-override", "alias-override", "issuer-override", "cpu-override", "root", "secret-env", "network"):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                path, config = self.fixture(Path(directory), True)
                sso = config["services"]["sso"]
                if mutation == "topology": config["services"]["proxy"] = copy.deepcopy(sso)
                elif mutation == "project-override": config["name"] = "other-database-project"
                elif mutation.endswith("override"):
                    key = {"kid-override": "LAS_SSO_SIGNING_KEY_ID", "alias-override": "LAS_SSO_SIGNING_KEY_ALIAS", "issuer-override": "LAS_SSO_ISSUER"}.get(mutation)
                    if key: sso["environment"][key] = "unexpected"
                    else: sso["cpus"] = "2"
                elif mutation == "root": sso["user"] = "0:0"
                elif mutation == "secret-env": sso["environment"]["RAW_PASSWORD"] = "fixture-only"
                else: config["networks"]["database"]["internal"] = False
                with self.assertRaises(ValueError): self.check(path, config, "host-nginx")

    def test_signing_id_rejects_the_observed_production_failure_patterns(self):
        for value in ("las-local-ephemeral", "las-test-1", "change-me-key", "signing/key", '"valid-key"', "k" * 129):
            with self.subTest(value=value), tempfile.TemporaryDirectory() as directory:
                path, config = self.fixture(Path(directory), True)
                path.write_text(path.read_text().replace("sso-prod-fixture-1", value), encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "SIGNING_KEY_ID_INVALID"):
                    self.check(path, config, "host-nginx")

    def test_host_infrastructure_builds_only_requested_postgres_and_records_fixture_scope(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory) / "receipts"
            calls = []
            def output(command, **kwargs):
                calls.append(command)
                if command[:3] == ["git", "rev-parse", "HEAD"]: return "a" * 40
                if command[:2] == ["git", "status"]: return b"fixture-dirty"
                if command[:3] == ["docker", "image", "inspect"]:
                    return json.dumps([{"Id": "sha256:" + "b" * 64, "RepoDigests": []}]).encode()
                return b"fixture-package-list\n"
            with patch.object(sys, "argv", ["build_docker_infrastructure.py", "--postgres-tag", "fixture-db", "--output-dir", str(work), "--allow-dirty-fixture"]), patch.object(infrastructure.subprocess, "check_output", side_effect=output), patch.object(infrastructure.subprocess, "run") as build, contextlib.redirect_stdout(io.StringIO()):
                infrastructure.main()
            self.assertEqual(build.call_count, 1)
            self.assertIn("fixture-db", build.call_args.args[0])
            receipt = json.loads((work / "infrastructure-images.json").read_text())
            self.assertEqual(set(receipt["images"]), {"postgres"})
            self.assertTrue(receipt["unverifiedFixture"])
            self.assertIsNone(receipt["sourceCommit"])
            self.assertFalse(receipt["productionApproved"])


if __name__ == "__main__":
    unittest.main()
