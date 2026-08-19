#!/usr/bin/env python3
"""Enforce repository-scoped ARC runners and zero Actions storage."""

from __future__ import annotations

import re
import sys
from pathlib import Path


RUNS_ON = re.compile(r"^\s*runs-on:\s*([^#]*?)(?:\s+#.*)?$")
FORBIDDEN = re.compile(
    r"\b(?:ubuntu|windows|macos)-(?:latest|\d[\w.-]*)\b", re.IGNORECASE
)
FORBIDDEN_STORAGE = (
    (re.compile(r"\buses:\s*actions/cache@", re.IGNORECASE), "actions/cache"),
    (
        re.compile(r"\buses:\s*actions/(?:upload|download)-artifact@", re.IGNORECASE),
        "Actions artifact storage",
    ),
    (re.compile(r"^\s*cache:\s*", re.IGNORECASE), "setup action cache"),
    (re.compile(r"\btype=gha\b", re.IGNORECASE), "BuildKit gha cache"),
)


def violations(workflow_dir: Path, expected_label: str) -> list[str]:
    errors: list[str] = []
    workflow_paths = sorted(
        path
        for pattern in ("*.yml", "*.yaml")
        for path in workflow_dir.glob(pattern)
    )
    if not workflow_paths:
        return [f"{workflow_dir}: no workflow files found"]

    for path in workflow_paths:
        text = path.read_text(encoding="utf-8")
        if FORBIDDEN.search(text):
            errors.append(f"{path}: GitHub-hosted runner label is forbidden")

        for line_number, line in enumerate(text.splitlines(), start=1):
            for pattern, description in FORBIDDEN_STORAGE:
                if pattern.search(line):
                    errors.append(
                        f"{path}:{line_number}: {description} is forbidden"
                    )

        labels = []
        for line_number, line in enumerate(text.splitlines(), start=1):
            match = RUNS_ON.match(line)
            if not match:
                continue
            label = match.group(1).strip().strip("\"'")
            labels.append(label)
            if label != expected_label:
                errors.append(
                    f"{path}:{line_number}: runs-on must be exactly "
                    f"{expected_label!r}, got {label!r}"
                )
        if not labels:
            errors.append(f"{path}: every workflow must declare a runs-on job")

    return errors


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {Path(sys.argv[0]).name} EXPECTED_LABEL", file=sys.stderr)
        return 2

    errors = violations(Path(".github/workflows"), sys.argv[1])
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1

    print(
        f"All workflows use the repository ARC label {sys.argv[1]!r} "
        "and avoid Actions cache/artifact storage."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
