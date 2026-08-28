from __future__ import annotations

import importlib.util
import inspect
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / "release_immutable_image.py"
SPEC = importlib.util.spec_from_file_location("backend_immutable_release", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)

SOURCE_SHA = "1" * 40
DIGEST = "sha256:" + "2" * 64
OLD_DIGEST = "sha256:" + "3" * 64


class ImmutableReleaseContractTest(unittest.TestCase):
    def test_exact_identifiers_reject_ambiguous_values(self) -> None:
        self.assertEqual(release.exact_source_sha(SOURCE_SHA), SOURCE_SHA)
        self.assertEqual(release.exact_digest(DIGEST), DIGEST)
        for invalid in ("1" * 39, "A" * 40, "main", SOURCE_SHA + "x"):
            with self.assertRaises(release.ReleaseError):
                release.exact_source_sha(invalid)
        for invalid in ("2" * 64, "sha256:" + "A" * 64, DIGEST + "0"):
            with self.assertRaises(release.ReleaseError):
                release.exact_digest(invalid)

    def test_plan_is_secret_free_and_non_mutating(self) -> None:
        plan = release.safe_plan("dev", SOURCE_SHA)
        self.assertEqual(plan["source_sha"], SOURCE_SHA)
        self.assertFalse(plan["git_publish"])
        self.assertFalse(plan["kubernetes_or_argocd_write"])
        serialized = json.dumps(plan)
        self.assertNotIn("username", serialized)
        self.assertNotIn("password", serialized)

    def test_overlay_pin_updates_digest_and_service_version_together(self) -> None:
        source = f"""images:
  - name: example
    digest: {OLD_DIGEST}
patches:
  - patch: |-
      - op: replace
        path: /spec/template/metadata/annotations/observability.damecasol.com~1service-version
        value: {OLD_DIGEST}
"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            overlay = root / "dev"
            overlay.mkdir()
            path = overlay / "kustomization.yaml"
            path.write_text(source, encoding="utf-8")
            with mock.patch.object(release, "OVERLAY_ROOT", root):
                release.replace_overlay_digest("dev", DIGEST)
            updated = path.read_text(encoding="utf-8")
        self.assertEqual(updated.count(DIGEST), 2)
        self.assertNotIn(OLD_DIGEST, updated)

    def test_overlay_pin_refuses_inconsistent_preimage(self) -> None:
        source = f"""images:
  - name: example
    digest: {OLD_DIGEST}
patches:
  - patch: |-
      - op: replace
        path: /spec/template/metadata/annotations/observability.damecasol.com~1service-version
        value: {DIGEST}
"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            overlay = root / "dev"
            overlay.mkdir()
            (overlay / "kustomization.yaml").write_text(source, encoding="utf-8")
            with mock.patch.object(release, "OVERLAY_ROOT", root):
                with self.assertRaises(release.ReleaseError):
                    release.replace_overlay_digest("dev", DIGEST)

    def test_prod_requires_exact_dev_source_and_digest(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "dev.json").write_text(
                json.dumps({"source_sha": SOURCE_SHA, "digest": DIGEST}),
                encoding="utf-8",
            )
            with mock.patch.object(release, "RECEIPT_ROOT", root):
                release.require_dev_promotion(SOURCE_SHA, DIGEST)
                with self.assertRaises(release.ReleaseError):
                    release.require_dev_promotion("4" * 40, DIGEST)
                with self.assertRaises(release.ReleaseError):
                    release.require_dev_promotion(SOURCE_SHA, OLD_DIGEST)

    def test_harbor_password_is_stdin_only(self) -> None:
        source = inspect.getsource(release.docker_login)
        self.assertIn("input_text=password", source)
        self.assertNotIn("password,", source.split("input_text=password", 1)[0])

    def test_docker_subprocess_environment_does_not_inherit_session_secrets(
        self,
    ) -> None:
        with mock.patch.dict(os.environ, {"SHOULD_NOT_REACH_DOCKER": "sensitive"}):
            with release.isolated_docker_environment() as environment:
                self.assertNotIn("SHOULD_NOT_REACH_DOCKER", environment)

    def test_failed_command_does_not_disclose_captured_output(self) -> None:
        completed = mock.Mock(
            returncode=1, stdout="sensitive stdout", stderr="sensitive stderr"
        )
        with mock.patch.object(release.subprocess, "run", return_value=completed):
            with self.assertRaises(release.ReleaseError) as raised:
                release.run_checked(("docker", "example"))
        self.assertNotIn("sensitive", str(raised.exception))

    def test_release_tool_has_no_git_publish_force_or_actions_storage(self) -> None:
        source = SCRIPT.read_text(encoding="utf-8")
        self.assertNotIn('("git", "push"', source)
        self.assertNotIn("--force", source)
        forbidden = (
            "actions/cache",
            "actions/upload-artifact",
            "actions/download-artifact",
            "actions/upload-pages-artifact",
            "type=gha",
            "cache-dependency-path",
        )
        workflow_root = SCRIPT.parents[2] / ".github/workflows"
        for path in workflow_root.glob("*.y*ml"):
            workflow = path.read_text(encoding="utf-8")
            for marker in forbidden:
                self.assertNotIn(
                    marker, workflow, f"{path}: forbidden Actions storage marker"
                )

    def test_harbor_contract_is_exactly_project_scoped(self) -> None:
        self.assertEqual(
            release.HARBOR_DOCUMENT, "/v1/kv/data/projects/seoul-fit/harbor-ci"
        )
        self.assertEqual(
            release.HARBOR_SOCKET,
            Path("/run/vault-proxy/seoul-fit-release-agent.sock"),
        )


if __name__ == "__main__":
    unittest.main()
