from contextlib import redirect_stdout
from importlib.util import module_from_spec, spec_from_file_location
from io import StringIO
from pathlib import Path
from unittest import TestCase
from unittest.mock import patch


SCRIPT = Path(__file__).parents[1] / "wait_for_sonar_quality_gate.py"
SPEC = spec_from_file_location("wait_for_sonar_quality_gate", SCRIPT)
assert SPEC and SPEC.loader
MODULE = module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class WaitForSonarQualityGateTest(TestCase):
    report = {
        "serverUrl": "https://sonar.example",
        "ceTaskUrl": "https://sonar.example/api/ce/task?id=task-1",
    }

    def test_reports_failed_conditions_without_exposing_the_token(self) -> None:
        responses = [
            {"task": {"status": "SUCCESS", "analysisId": "analysis-1"}},
            {
                "projectStatus": {
                    "status": "ERROR",
                    "conditions": [
                        {
                            "metricKey": "new_coverage",
                            "status": "ERROR",
                            "actualValue": "42.0",
                            "comparator": "LT",
                            "errorThreshold": "80",
                        }
                    ],
                }
            },
        ]
        output = StringIO()
        with patch.object(MODULE, "get_json", side_effect=responses):
            with redirect_stdout(output):
                result = MODULE.wait_for_gate(self.report, "secret-token", 1)

        self.assertEqual(1, result)
        self.assertIn("new_coverage", output.getvalue())
        self.assertNotIn("secret-token", output.getvalue())

    def test_accepts_a_successful_gate(self) -> None:
        responses = [
            {"task": {"status": "SUCCESS", "analysisId": "analysis-1"}},
            {"projectStatus": {"status": "OK", "conditions": []}},
        ]
        with patch.object(MODULE, "get_json", side_effect=responses):
            self.assertEqual(0, MODULE.wait_for_gate(self.report, "token", 1))

    def test_rejects_a_cross_origin_task_url(self) -> None:
        report = dict(self.report, ceTaskUrl="https://attacker.example/task")
        with self.assertRaisesRegex(ValueError, "different origin"):
            MODULE.wait_for_gate(report, "token", 1)
