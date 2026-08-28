from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
from unittest import TestCase


SCRIPT = Path(__file__).parents[1] / "report_sonar_issues.py"
SPEC = spec_from_file_location("report_sonar_issues", SCRIPT)
assert SPEC and SPEC.loader
MODULE = module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ReportSonarIssuesTest(TestCase):
    def test_formats_project_relative_location(self) -> None:
        issue = {
            "severity": "MAJOR",
            "rule": "java:S1234",
            "component": "seoul-fit-backend:src/main/java/Example.java",
            "line": 42,
            "message": "Example message",
        }

        result = MODULE.format_issue("seoul-fit-backend", issue)

        self.assertIn("src/main/java/Example.java:42", result)
        self.assertIn("java:S1234", result)
        self.assertNotIn("seoul-fit-backend:", result)
