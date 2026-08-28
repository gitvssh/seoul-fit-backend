#!/usr/bin/env python3
"""Print a sanitized SonarQube new-code diagnostic report."""

from __future__ import annotations

import base64
import json
import os
import sys
import urllib.parse
import urllib.request


def get_json(server_url: str, path: str, query: dict[str, str], token: str) -> dict:
    url = f"{server_url.rstrip('/')}{path}?" + urllib.parse.urlencode(query)
    authorization = base64.b64encode(f"{token}:".encode()).decode()
    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/json",
            "Authorization": f"Basic {authorization}",
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def format_issue(project_key: str, issue: dict) -> str:
    component = issue.get("component", "")
    prefix = f"{project_key}:"
    if component.startswith(prefix):
        component = component[len(prefix) :]
    location = component
    if issue.get("line") is not None:
        location += f":{issue['line']}"
    return (
        f"{issue.get('severity', 'UNKNOWN'):8} "
        f"{issue.get('rule', 'unknown-rule'):35} "
        f"{location} — {issue.get('message', '')}"
    )


def report(server_url: str, project_key: str, token: str) -> None:
    new_code = get_json(
        server_url,
        "/api/new_code_periods/show",
        {"project": project_key},
        token,
    )
    print(
        "New-code definition: "
        f"type={new_code.get('type', 'unknown')}, "
        f"value={new_code.get('value', 'n/a')}, "
        f"effectiveValue={new_code.get('effectiveValue', 'n/a')}"
    )

    gate = get_json(
        server_url,
        "/api/qualitygates/project_status",
        {"projectKey": project_key},
        token,
    )["projectStatus"]
    print(f"Quality gate: {gate.get('status', 'UNKNOWN')}")
    for condition in gate.get("conditions", []):
        if condition.get("status") != "OK":
            print(
                " - {metricKey}: status={status}, actual={actualValue}, "
                "threshold={comparator} {errorThreshold}".format(**condition)
            )

    page = 1
    issues: list[dict] = []
    while True:
        payload = get_json(
            server_url,
            "/api/issues/search",
            {
                "componentKeys": project_key,
                "resolved": "false",
                "inNewCodePeriod": "true",
                "ps": "100",
                "p": str(page),
            },
            token,
        )
        issues.extend(payload.get("issues", []))
        paging = payload.get("paging", {})
        total = int(paging.get("total", len(issues)))
        if len(issues) >= total:
            break
        page += 1

    print(f"Open issues in the new-code period: {len(issues)}")
    for issue in issues:
        print(format_issue(project_key, issue))


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} PROJECT_KEY", file=sys.stderr)
        return 2
    server_url = os.environ.get("SONAR_HOST_URL", "")
    token = os.environ.get("SONAR_TOKEN", "")
    if not server_url or not token:
        print("SONAR_HOST_URL and SONAR_TOKEN are required", file=sys.stderr)
        return 2
    try:
        report(server_url, sys.argv[1], token)
    except (KeyError, OSError, ValueError, json.JSONDecodeError) as error:
        print(f"Unable to query SonarQube: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
