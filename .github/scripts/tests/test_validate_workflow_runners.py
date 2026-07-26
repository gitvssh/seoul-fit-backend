from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase

from importlib.util import module_from_spec, spec_from_file_location


SCRIPT = Path(__file__).parents[1] / "validate_workflow_runners.py"
SPEC = spec_from_file_location("validate_workflow_runners", SCRIPT)
assert SPEC and SPEC.loader
MODULE = module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ValidateWorkflowRunnersTest(TestCase):
    def validate(self, contents: str) -> list[str]:
        with TemporaryDirectory() as directory:
            workflows = Path(directory)
            (workflows / "ci.yml").write_text(contents, encoding="utf-8")
            return MODULE.violations(workflows, "homelab-seoul-fit-backend")

    def test_accepts_repository_arc_label(self) -> None:
        self.assertEqual(
            [],
            self.validate(
                "jobs:\n"
                "  test:\n"
                "    runs-on: homelab-seoul-fit-backend\n"
            ),
        )

    def test_rejects_github_hosted_label(self) -> None:
        errors = self.validate("jobs:\n  test:\n    runs-on: ubuntu-latest\n")
        self.assertTrue(any("GitHub-hosted" in error for error in errors))

    def test_rejects_dynamic_or_multiline_labels(self) -> None:
        dynamic = self.validate("jobs:\n  test:\n    runs-on: ${{ matrix.runner }}\n")
        multiline = self.validate(
            "jobs:\n  test:\n    runs-on:\n      - self-hosted\n"
        )
        self.assertTrue(dynamic)
        self.assertTrue(multiline)
