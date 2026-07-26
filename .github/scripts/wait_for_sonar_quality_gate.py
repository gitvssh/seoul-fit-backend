#!/usr/bin/env python3
"""Wait for the submitted SonarQube analysis and report failed conditions."""

from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path


TERMINAL_TASK_STATES = {"SUCCESS", "FAILED", "CANCELED"}


def parse_report(path: Path) -> dict[str, str]:
    report: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator:
            report[key] = value
    return report


def same_origin(left: str, right: str) -> bool:
    left_url = urllib.parse.urlsplit(left)
    right_url = urllib.parse.urlsplit(right)
    return (
        left_url.scheme,
        left_url.hostname,
        left_url.port,
    ) == (
        right_url.scheme,
        right_url.hostname,
        right_url.port,
    )


def get_json(url: str, token: str) -> dict:
    authorization = base64.b64encode(f"{token}:".encode()).decode()
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/json",
            "Authorization": f"Basic {authorization}",
        },
    )
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.load(response)


def wait_for_gate(report: dict[str, str], token: str, timeout: int) -> int:
    server_url = report.get("serverUrl", "").rstrip("/")
    task_url = report.get("ceTaskUrl", "")
    if not server_url or not task_url:
        raise ValueError("scanner report is missing serverUrl or ceTaskUrl")
    if not same_origin(server_url, task_url):
        raise ValueError("refusing to send the token to a different origin")

    deadline = time.monotonic() + timeout
    while True:
        task = get_json(task_url, token)["task"]
        status = task["status"]
        if status in TERMINAL_TASK_STATES:
            break
        if time.monotonic() >= deadline:
            raise TimeoutError(f"analysis did not finish within {timeout}s")
        time.sleep(2)

    if status != "SUCCESS":
        print(f"SonarQube compute task: {status}", file=sys.stderr)
        return 1

    analysis_id = task.get("analysisId")
    if not analysis_id:
        raise ValueError("successful compute task is missing analysisId")
    status_url = (
        f"{server_url}/api/qualitygates/project_status?"
        + urllib.parse.urlencode({"analysisId": analysis_id})
    )
    project_status = get_json(status_url, token)["projectStatus"]
    gate_status = project_status["status"]
    print(f"SonarQube quality gate: {gate_status}")

    failed_conditions = [
        condition
        for condition in project_status.get("conditions", [])
        if condition.get("status") != "OK"
    ]
    for condition in failed_conditions:
        print(
            " - {metricKey}: status={status}, actual={actualValue}, "
            "threshold={comparator} {errorThreshold}".format(**condition)
        )

    return 0 if gate_status == "OK" else 1


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--report",
        type=Path,
        default=Path(".scannerwork/report-task.txt"),
    )
    parser.add_argument("--timeout", type=int, default=300)
    args = parser.parse_args()

    token = os.environ.get("SONAR_TOKEN", "")
    if not token:
        print("SONAR_TOKEN is required", file=sys.stderr)
        return 2
    try:
        return wait_for_gate(parse_report(args.report), token, args.timeout)
    except (KeyError, OSError, TimeoutError, ValueError, json.JSONDecodeError) as error:
        print(f"Unable to verify the SonarQube quality gate: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
