#!/usr/bin/env python3
"""Validate Seoul Fit backend's bounded Argo CD descriptors."""

from __future__ import annotations

import json
from pathlib import Path
import sys


REPO_ROOT = Path(__file__).resolve().parents[2]
DESCRIPTOR_DIR = REPO_ROOT / "infra/argocd/applications"
LEGACY_APP_DIR = REPO_ROOT / "infra/argocd-apps"
EXPECTED = {
    "dev.json": {
        "schemaVersion": 1,
        "name": "seoul-fit-backend-dev",
        "environment": "dev",
        "targetRevision": "main",
        "sourcePath": "infra/k8s/seoul-fit-backend/overlays/dev",
        "namespace": "seoul-fit-dev",
        "syncClass": "manual",
    },
    "prod.json": {
        "schemaVersion": 1,
        "name": "seoul-fit-backend-prod",
        "environment": "prod",
        "targetRevision": "main",
        "sourcePath": "infra/k8s/seoul-fit-backend/overlays/prod",
        "namespace": "seoul-fit-prod",
        "syncClass": "manual",
    },
}


def fail(message: str) -> None:
    raise AssertionError(message)


def validate_descriptor(filename: str, expected: dict[str, object]) -> None:
    path = DESCRIPTOR_DIR / filename
    if not path.is_file():
        fail(f"missing approved descriptor: {path.relative_to(REPO_ROOT)}")

    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        fail(f"invalid descriptor {filename}: {exc}")

    if document != expected:
        fail(
            f"{filename} must match the approved registration contract; "
            f"expected {expected!r}, got {document!r}"
        )

    source_path = document["sourcePath"]
    if not isinstance(source_path, str):
        fail(f"{filename}: sourcePath must be a string")
    if not (REPO_ROOT / source_path / "kustomization.yaml").is_file():
        fail(f"{filename}: sourcePath has no kustomization.yaml: {source_path}")


def main() -> int:
    actual = {path.name for path in DESCRIPTOR_DIR.glob("*.json")}
    approved = set(EXPECTED)
    if actual != approved:
        fail(
            "descriptor set must be explicitly approved; "
            f"expected {sorted(approved)}, got {sorted(actual)}"
        )

    legacy = sorted(
        path.relative_to(REPO_ROOT).as_posix()
        for pattern in ("*.yaml", "*.yml")
        for path in LEGACY_APP_DIR.glob(pattern)
    )
    if legacy:
        fail(f"app repositories must not own Argo CD resources: {legacy}")

    for filename, expected in EXPECTED.items():
        validate_descriptor(filename, expected)

    print("Seoul Fit backend Argo CD registration contract: OK")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except AssertionError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(1)
