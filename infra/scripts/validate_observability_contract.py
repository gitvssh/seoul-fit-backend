#!/usr/bin/env python3
"""Validate Seoul Fit's deployment-time observability identity without YAML dependencies."""

from __future__ import annotations

import re
import shutil
import subprocess
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
APPLICATION_CONFIG = REPO_ROOT / "src/main/resources/application.yml"
BASE_DEPLOYMENT = REPO_ROOT / "infra/k8s/seoul-fit-backend/base/deployment.yaml"
DOCKERFILE = REPO_ROOT / "Dockerfile"
ENTRYPOINT = REPO_ROOT / "infra/runtime/seoul-fit-backend-entrypoint.sh"
OVERLAY_ROOT = REPO_ROOT / "infra/k8s/seoul-fit-backend/overlays"
LOG_SCHEMA = "spring_boot_otel_json_v1"
INVALID_IDENTITY = {"", "unknown", "unset", "none", "null", "local", "development", "placeholder"}


class ContractError(RuntimeError):
    """Raised when desired state could publish ambiguous telemetry."""


def require(pattern: str, text: str, description: str) -> str:
    match = re.search(pattern, text, flags=re.MULTILINE)
    if not match:
        raise ContractError(f"missing {description}")
    return match.group(1)


def validate_base() -> None:
    application_source = APPLICATION_CONFIG.read_text(encoding="utf-8")
    if f"log_schema: {LOG_SCHEMA}" not in application_source:
        raise ContractError(f"application log_schema must be exactly {LOG_SCHEMA!r}")
    if "customizer: com.seoulfit.backend.config.StructuredLogSanitizer" not in application_source:
        raise ContractError("application must sanitize the final structured-log output boundary")
    if "banner-mode: off" not in application_source:
        raise ContractError("Spring's plaintext startup banner must be disabled")

    entrypoint = ENTRYPOINT.read_text(encoding="utf-8")
    marker = "printf '%s\\n' 'homelab-runtime-start-v1'"
    if entrypoint.count("homelab-runtime-start-v1") != 1:
        raise ContractError("runtime marker must occur exactly once in the entrypoint")
    for required in (
        "OTEL_SERVICE_NAME",
        "OTEL_SERVICE_NAMESPACE",
        "OTEL_SERVICE_VERSION",
        "OTEL_SERVICE_INSTANCE_ID",
        "K8S_POD_UID",
        "DEPLOYMENT_ENVIRONMENT_NAME",
    ):
        if entrypoint.find(required) < 0 or entrypoint.find(required) > entrypoint.find(marker):
            raise ContractError(f"entrypoint must validate {required} before the marker")
    if entrypoint.find("exec java") < entrypoint.find(marker):
        raise ContractError("entrypoint must emit the marker before execing Java")

    dockerfile = DOCKERFILE.read_text(encoding="utf-8")
    for fragment in (
        "FROM runtime AS runtime-contract",
        '/usr/local/bin/seoul-fit-backend-entrypoint >"${contract_log}" 2>&1',
        'test "$(wc -l < "${contract_log}")" -eq 2',
        "FROM runtime AS release",
        "COPY --from=runtime-contract /tmp/seoul-fit-backend-runtime-contract.ok",
    ):
        if fragment not in dockerfile:
            raise ContractError(f"Docker runtime contract is missing {fragment!r}")

    source = BASE_DEPLOYMENT.read_text(encoding="utf-8")
    required_fragments = (
        f"observability.damecasol.com/log-schema: {LOG_SCHEMA}",
        "name: OTEL_SERVICE_NAME",
        "name: OTEL_SERVICE_NAMESPACE",
        "name: OTEL_SERVICE_VERSION",
        "metadata.annotations['observability.damecasol.com/service-version']",
        "name: OTEL_SERVICE_INSTANCE_ID",
        "fieldPath: metadata.uid",
        "name: K8S_POD_UID",
        "name: DEPLOYMENT_ENVIRONMENT_NAME",
        "metadata.labels['app.kubernetes.io/environment']",
        "name: OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
    )
    missing = [fragment for fragment in required_fragments if fragment not in source]
    if missing:
        raise ContractError(f"base deployment is missing identity wiring: {', '.join(missing)}")
    require(
        r"(name:\s*K8S_POD_UID\s*\n\s*valueFrom:\s*\n\s*fieldRef:\s*\n"
        r"\s*fieldPath:\s*metadata\.uid)",
        source,
        "K8S_POD_UID downward API wiring",
    )


def validate_overlay(environment: str) -> None:
    overlay_dir = OVERLAY_ROOT / environment
    source = (overlay_dir / "kustomization.yaml").read_text(encoding="utf-8")

    image_digest = require(r"^\s*digest:\s*(sha256:[0-9a-f]{64})\s*$", source, "image digest")
    service_version = require(
        r"path:\s*/spec/template/metadata/annotations/observability\.damecasol\.com~1service-version"
        r"\s*\n\s*value:\s*([^\s]+)",
        source,
        "service.version annotation patch",
    )
    deployment_environment = require(
        r"path:\s*/spec/template/metadata/labels/app\.kubernetes\.io~1environment"
        r"\s*\n\s*value:\s*([^\s]+)",
        source,
        "deployment environment label patch",
    )

    if service_version.lower() in INVALID_IDENTITY:
        raise ContractError(f"{environment}: invalid service.version {service_version!r}")
    if service_version != image_digest:
        raise ContractError(
            f"{environment}: service.version must equal the pinned image digest "
            f"({service_version!r} != {image_digest!r})"
        )
    if deployment_environment != environment:
        raise ContractError(
            f"{environment}: deployment environment label is {deployment_environment!r}"
        )

    kubectl = shutil.which("kubectl")
    if kubectl is None:
        raise ContractError("kubectl is required to validate rendered overlays")
    rendered = subprocess.run(
        [kubectl, "kustomize", str(overlay_dir)],
        check=True,
        capture_output=True,
        text=True,
    ).stdout
    if "PLACEHOLDER" in rendered:
        raise ContractError(f"{environment}: rendered desired state still contains PLACEHOLDER")
    for expected in (
        f"image: registry.damecasol.com/seoul-fit/backend@{image_digest}",
        f"observability.damecasol.com/log-schema: {LOG_SCHEMA}",
        f"observability.damecasol.com/service-version: {image_digest}",
        f"app.kubernetes.io/environment: {environment}",
        "name: K8S_POD_UID",
    ):
        if expected not in rendered:
            raise ContractError(f"{environment}: rendered desired state is missing {expected!r}")


def main() -> int:
    try:
        validate_base()
        for environment in ("dev", "prod"):
            validate_overlay(environment)
    except (ContractError, OSError, subprocess.CalledProcessError) as error:
        print(f"observability contract invalid: {error}", file=sys.stderr)
        return 1
    print("observability contract valid: dev/prod release and pod identity are exact")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
