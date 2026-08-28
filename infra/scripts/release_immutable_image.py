#!/usr/bin/env python3
"""Build, publish, verify, and pin one immutable Seoul Fit backend image.

The command is intentionally local-only.  It never commits, pushes Git, talks to
Kubernetes, or updates Argo CD.  Harbor credentials are read through the
project-scoped Vault Proxy socket and are passed only to ``docker login`` stdin.
"""

from __future__ import annotations

import argparse
import base64
import http.client
import json
import os
import re
import shutil
import socket
import stat
import subprocess
import sys
import tempfile
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator, Mapping, Sequence
from urllib.parse import urlencode


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_BRANCH = "main"
REGISTRY = "registry.damecasol.com"
IMAGE_REPOSITORY = f"{REGISTRY}/seoul-fit/backend"
REGISTRY_REPOSITORY = "seoul-fit/backend"
PLATFORM = "linux/amd64"
HARBOR_SOCKET = Path("/run/vault-proxy/seoul-fit-release-agent.sock")
HARBOR_DOCUMENT = "/v1/kv/data/projects/seoul-fit/harbor-ci"
OVERLAY_ROOT = REPO_ROOT / "infra/k8s/seoul-fit-backend/overlays"
RECEIPT_ROOT = REPO_ROOT / "infra/releases"
SOURCE_PATTERN = re.compile(r"[0-9a-f]{40}")
DIGEST_PATTERN = re.compile(r"sha256:[0-9a-f]{64}")


class ReleaseError(RuntimeError):
    """A fail-closed release contract violation."""


class UnixSocketHTTPConnection(http.client.HTTPConnection):
    def __init__(self, socket_path: Path, timeout: float = 10.0) -> None:
        super().__init__("localhost", timeout=timeout)
        self.socket_path = socket_path

    def connect(self) -> None:
        connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        connection.settimeout(self.timeout)
        connection.connect(str(self.socket_path))
        self.sock = connection


def exact_source_sha(value: str) -> str:
    if SOURCE_PATTERN.fullmatch(value) is None:
        raise ReleaseError(
            "source SHA must be exactly 40 lowercase hexadecimal characters"
        )
    return value


def exact_digest(value: str) -> str:
    if DIGEST_PATTERN.fullmatch(value) is None:
        raise ReleaseError(
            "digest must be exactly sha256 followed by 64 lowercase hexadecimal characters"
        )
    return value


def run_checked(
    argv: Sequence[str],
    *,
    env: Mapping[str, str] | None = None,
    input_text: str | None = None,
    pass_fds: Sequence[int] = (),
) -> str:
    try:
        result = subprocess.run(
            list(argv),
            cwd=REPO_ROOT,
            env=dict(env) if env is not None else None,
            input=input_text,
            text=True,
            capture_output=True,
            check=False,
            pass_fds=tuple(pass_fds),
        )
    except OSError as error:
        raise ReleaseError(f"required command could not start: {argv[0]}") from error
    if result.returncode != 0:
        # Command output can contain credentials, headers, or environment data.
        raise ReleaseError(f"command failed without output disclosure: {argv[0]}")
    return result.stdout.strip()


def git_output(*arguments: str) -> str:
    return run_checked(("git", *arguments))


def verify_git_checkout(source_sha: str, *, publish: bool) -> None:
    if git_output("status", "--porcelain=v1"):
        raise ReleaseError("the repository must be clean")
    branch = git_output("symbolic-ref", "--quiet", "--short", "HEAD")
    if branch != DEFAULT_BRANCH:
        raise ReleaseError(
            f"execution is allowed only on the canonical {DEFAULT_BRANCH} branch"
        )
    head = git_output("rev-parse", "HEAD")
    remote_head = git_output("rev-parse", f"origin/{DEFAULT_BRANCH}")
    if head != remote_head:
        raise ReleaseError(
            "the canonical branch must exactly match its origin tracking ref"
        )
    if publish:
        if head != source_sha:
            raise ReleaseError("publish source SHA must exactly equal HEAD")
    elif (
        subprocess.run(
            ["git", "merge-base", "--is-ancestor", source_sha, "HEAD"],
            cwd=REPO_ROOT,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        ).returncode
        != 0
    ):
        raise ReleaseError("pin source SHA must be an ancestor of canonical HEAD")


def read_harbor_credentials() -> tuple[str, str]:
    try:
        metadata = HARBOR_SOCKET.lstat()
    except FileNotFoundError as error:
        raise ReleaseError(
            "the Seoul Fit release-agent Vault Proxy socket is absent"
        ) from error
    if not stat.S_ISSOCK(metadata.st_mode):
        raise ReleaseError("the configured release-agent endpoint is not a Unix socket")

    connection = UnixSocketHTTPConnection(HARBOR_SOCKET)
    try:
        connection.request(
            "GET", HARBOR_DOCUMENT, headers={"Accept": "application/json"}
        )
        response = connection.getresponse()
        body = response.read(65537)
    except (OSError, http.client.HTTPException) as error:
        raise ReleaseError("the release-agent credential request failed") from error
    finally:
        connection.close()
    if response.status != 200 or len(body) > 65536:
        raise ReleaseError("the release-agent credential document is unavailable")
    try:
        document = json.loads(body)
        values = document["data"]["data"]
    except (KeyError, TypeError, ValueError, RecursionError) as error:
        raise ReleaseError(
            "the release-agent credential document is malformed"
        ) from error
    if not isinstance(values, dict) or set(values) != {"username", "password"}:
        raise ReleaseError(
            "the Harbor credential document must contain exactly username and password"
        )
    username, password = values["username"], values["password"]
    for name, value in (("username", username), ("password", password)):
        if (
            not isinstance(value, str)
            or not 1 <= len(value) <= 4096
            or "\x00" in value
            or "\n" in value
            or "\r" in value
        ):
            raise ReleaseError(f"Harbor {name} has an invalid shape")
    return username, password


def registry_token(username: str, password: str) -> str:
    token = base64.b64encode(f"{username}:{password}".encode()).decode("ascii")
    connection = http.client.HTTPSConnection(REGISTRY, timeout=15)
    try:
        connection.request(
            "GET",
            "/service/token?"
            + urlencode(
                {
                    "service": "harbor-registry",
                    "scope": f"repository:{REGISTRY_REPOSITORY}:pull,push",
                }
            ),
            headers={"Authorization": f"Basic {token}", "Accept": "application/json"},
        )
        response = connection.getresponse()
        body = response.read(65537)
    except (OSError, http.client.HTTPException) as error:
        raise ReleaseError("the Harbor repository token request failed") from error
    finally:
        connection.close()
    if response.status != 200 or len(body) > 65536:
        raise ReleaseError("the Harbor repository token is unavailable")
    try:
        document = json.loads(body)
        bearer = document.get("token") or document.get("access_token")
        if not isinstance(bearer, str) or not bearer:
            raise ValueError
        parts = bearer.split(".")
        if len(parts) != 3:
            raise ValueError
        claims = json.loads(
            base64.urlsafe_b64decode(parts[1] + "=" * (-len(parts[1]) % 4))
        )
        access = claims["access"]
    except (KeyError, IndexError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise ReleaseError("the Harbor repository token is malformed") from error
    expected = {"type": "repository", "name": REGISTRY_REPOSITORY}
    actions = (
        access[0].get("actions", []) if isinstance(access, list) and access else []
    )
    if (
        not isinstance(access, list)
        or len(access) != 1
        or not isinstance(access[0], dict)
        or access[0].get("type") != expected["type"]
        or access[0].get("name") != expected["name"]
        or not isinstance(actions, list)
        or not all(isinstance(action, str) for action in actions)
        or sorted(actions) != ["pull", "push"]
    ):
        raise ReleaseError(
            "the Harbor token is not exact pull/push for the backend repository"
        )
    return bearer


def registry_digest(tag: str, bearer: str) -> str | None:
    connection = http.client.HTTPSConnection(REGISTRY, timeout=15)
    try:
        connection.request(
            "HEAD",
            f"/v2/{REGISTRY_REPOSITORY}/manifests/{tag}",
            headers={
                "Accept": (
                    "application/vnd.oci.image.manifest.v1+json, "
                    "application/vnd.docker.distribution.manifest.v2+json"
                ),
                "Authorization": f"Bearer {bearer}",
            },
        )
        response = connection.getresponse()
        response.read()
    except (OSError, http.client.HTTPException) as error:
        raise ReleaseError("the Harbor manifest pre/post check failed") from error
    finally:
        connection.close()
    if response.status == 404:
        return None
    if response.status != 200:
        raise ReleaseError(
            "the Harbor manifest pre/post check was not authorized or available"
        )
    header = response.getheader("Docker-Content-Digest", "")
    return exact_digest(header)


@contextmanager
def isolated_docker_environment() -> Iterator[dict[str, str]]:
    expected_runtime = Path(f"/run/user/{os.geteuid()}")
    configured_runtime = Path(os.environ.get("XDG_RUNTIME_DIR", ""))
    try:
        metadata = configured_runtime.lstat()
    except (FileNotFoundError, OSError) as error:
        raise ReleaseError("XDG_RUNTIME_DIR is unavailable") from error
    if (
        configured_runtime.resolve() != expected_runtime
        or not stat.S_ISDIR(metadata.st_mode)
        or metadata.st_uid != os.geteuid()
        or stat.S_IMODE(metadata.st_mode) & 0o077
    ):
        raise ReleaseError(
            "XDG_RUNTIME_DIR is not the private canonical user runtime directory"
        )
    with tempfile.TemporaryDirectory(
        prefix="seoul-fit-backend-release-", dir=configured_runtime
    ) as name:
        directory = Path(name)
        directory.chmod(0o700)
        environment = {
            "DOCKER_CONFIG": str(directory),
            "HOME": str(configured_runtime),
            "LANG": "C.UTF-8",
            "LC_ALL": "C.UTF-8",
            "PATH": os.environ.get(
                "PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            ),
        }
        yield environment


def inspect_revision(image: str, environment: Mapping[str, str]) -> str:
    revision = run_checked(
        (
            "docker",
            "image",
            "inspect",
            "--format",
            '{{ index .Config.Labels "org.opencontainers.image.revision" }}',
            image,
        ),
        env=environment,
    )
    return exact_source_sha(revision)


def docker_login(environment: Mapping[str, str], username: str, password: str) -> None:
    run_checked(
        ("docker", "login", REGISTRY, "--username", username, "--password-stdin"),
        env=environment,
        input_text=password,
    )


def publish(source_sha: str) -> dict[str, str | bool]:
    verify_git_checkout(source_sha, publish=True)
    if shutil.which("docker") is None:
        raise ReleaseError("docker is required")
    tag = source_sha
    image = f"{IMAGE_REPOSITORY}:{tag}"
    username, password = read_harbor_credentials()
    with isolated_docker_environment() as environment:
        docker_login(environment, username, password)
        bearer = registry_token(username, password)
        before = registry_digest(tag, bearer)
        resumed = before is not None
        if before is None:
            run_checked(
                (
                    "docker",
                    "build",
                    "--pull",
                    "--platform",
                    PLATFORM,
                    "--target",
                    "release",
                    "--label",
                    f"org.opencontainers.image.revision={source_sha}",
                    "--label",
                    f"org.opencontainers.image.version={source_sha}",
                    "--tag",
                    image,
                    ".",
                ),
                env=environment,
            )
            if inspect_revision(image, environment) != source_sha:
                raise ReleaseError(
                    "the local image revision label differs from source SHA"
                )
            run_checked(
                (
                    "docker",
                    "run",
                    "--rm",
                    "--platform",
                    PLATFORM,
                    "--network",
                    "none",
                    "--entrypoint",
                    "/bin/sh",
                    image,
                    "-eu",
                    "-c",
                    "test -f /etc/seoul-fit-backend-runtime-contract.ok",
                ),
                env=environment,
            )
            # Repeat the remote check immediately before the one allowed push.
            if registry_digest(tag, bearer) is not None:
                raise ReleaseError(
                    "the immutable tag appeared during build; refusing overwrite"
                )
            run_checked(("docker", "push", image), env=environment)
        after = registry_digest(tag, bearer)
        if after is None:
            raise ReleaseError("the pushed immutable manifest is absent")
        if registry_digest(tag, bearer) != after:
            raise ReleaseError(
                "the registry digest changed during post-push verification"
            )
        immutable_image = f"{IMAGE_REPOSITORY}@{after}"
        run_checked(
            ("docker", "pull", "--platform", PLATFORM, immutable_image), env=environment
        )
        if inspect_revision(immutable_image, environment) != source_sha:
            raise ReleaseError(
                "the registry image revision label differs from source SHA"
            )
        run_checked(
            (
                "docker",
                "run",
                "--rm",
                "--platform",
                PLATFORM,
                "--network",
                "none",
                "--entrypoint",
                "/bin/sh",
                immutable_image,
                "-eu",
                "-c",
                "test -f /etc/seoul-fit-backend-runtime-contract.ok",
            ),
            env=environment,
        )
    return {
        "digest": after,
        "image": image,
        "resumed_existing_immutable_tag": resumed,
        "service_version": after,
        "source_sha": source_sha,
    }


def replace_overlay_digest(environment: str, digest: str) -> None:
    path = OVERLAY_ROOT / environment / "kustomization.yaml"
    source = path.read_text(encoding="utf-8")
    digest_expression = re.compile(r"(?m)^(\s*digest:\s*)(sha256:[0-9a-f]{64})(\s*)$")
    version_expression = re.compile(
        r"(?m)(^\s*path:\s*/spec/template/metadata/annotations/"
        r"observability\.damecasol\.com~1service-version\s*\n\s*value:\s*)"
        r"(sha256:[0-9a-f]{64})(\s*$)"
    )
    digest_matches = digest_expression.findall(source)
    version_matches = version_expression.findall(source)
    if len(digest_matches) != 1 or len(version_matches) != 1:
        raise ReleaseError(
            f"{environment} overlay must contain one image and one service version digest"
        )
    old_digest = digest_matches[0][1]
    old_version = version_matches[0][1]
    if old_digest != old_version:
        raise ReleaseError(f"{environment} overlay preimage is internally inconsistent")
    updated = digest_expression.sub(rf"\g<1>{digest}\g<3>", source, count=1)
    updated = version_expression.sub(rf"\g<1>{digest}\g<3>", updated, count=1)
    path.write_text(updated, encoding="utf-8")


def write_receipt(environment: str, source_sha: str, digest: str) -> None:
    RECEIPT_ROOT.mkdir(parents=True, exist_ok=True)
    receipt = {
        "digest": digest,
        "environment": environment,
        "image": f"{IMAGE_REPOSITORY}:{source_sha}",
        "schema_version": 1,
        "service_version": digest,
        "source_sha": source_sha,
    }
    (RECEIPT_ROOT / f"{environment}.json").write_text(
        json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )


def require_dev_promotion(source_sha: str, digest: str) -> None:
    path = RECEIPT_ROOT / "dev.json"
    try:
        receipt = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError, RecursionError) as error:
        raise ReleaseError(
            "prod pin requires the committed dev release receipt"
        ) from error
    if receipt.get("source_sha") != source_sha or receipt.get("digest") != digest:
        raise ReleaseError(
            "prod pin must promote the exact source and digest already pinned in dev"
        )


def pin(environment_name: str, source_sha: str, digest: str) -> dict[str, str]:
    verify_git_checkout(source_sha, publish=False)
    if environment_name == "prod":
        require_dev_promotion(source_sha, digest)
    username, password = read_harbor_credentials()
    tag = source_sha
    with isolated_docker_environment() as environment:
        docker_login(environment, username, password)
        bearer = registry_token(username, password)
        remote_digest = registry_digest(tag, bearer)
        if remote_digest != digest:
            raise ReleaseError(
                "the requested digest differs from the immutable registry tag"
            )
        immutable_image = f"{IMAGE_REPOSITORY}@{digest}"
        run_checked(
            ("docker", "pull", "--platform", PLATFORM, immutable_image), env=environment
        )
        if inspect_revision(immutable_image, environment) != source_sha:
            raise ReleaseError(
                "the pinned image revision label differs from source SHA"
            )
    replace_overlay_digest(environment_name, digest)
    write_receipt(environment_name, source_sha, digest)
    return {
        "digest": digest,
        "environment": environment_name,
        "service_version": digest,
        "source_sha": source_sha,
    }


def safe_plan(environment_name: str | None, source_sha: str) -> dict[str, object]:
    return {
        "default_branch": DEFAULT_BRANCH,
        "docker_config": "$XDG_RUNTIME_DIR/seoul-fit-backend-release-* (temporary, mode 0700)",
        "environment": environment_name,
        "git_publish": False,
        "image": f"{IMAGE_REPOSITORY}:{source_sha}",
        "kubernetes_or_argocd_write": False,
        "source_sha": source_sha,
        "vault_document": HARBOR_DOCUMENT.removeprefix("/v1/"),
        "vault_socket": str(HARBOR_SOCKET),
    }


def parse_arguments(argv: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    plan_parser = subparsers.add_parser(
        "plan", help="print a secret-free execution contract"
    )
    plan_parser.add_argument("--source-sha", required=True)
    plan_parser.add_argument("--environment", choices=("dev", "prod"))
    publish_parser = subparsers.add_parser(
        "publish", help="build/push one immutable image"
    )
    publish_parser.add_argument("--source-sha", required=True)
    publish_parser.add_argument("--execute", action="store_true", required=True)
    pin_parser = subparsers.add_parser("pin", help="pin exactly one environment")
    pin_parser.add_argument("--environment", choices=("dev", "prod"), required=True)
    pin_parser.add_argument("--source-sha", required=True)
    pin_parser.add_argument("--digest", required=True)
    pin_parser.add_argument("--execute", action="store_true", required=True)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    try:
        arguments = parse_arguments(argv if argv is not None else sys.argv[1:])
        source_sha = exact_source_sha(arguments.source_sha)
        if arguments.command == "plan":
            result = safe_plan(arguments.environment, source_sha)
        elif arguments.command == "publish":
            result = publish(source_sha)
        else:
            result = pin(
                arguments.environment, source_sha, exact_digest(arguments.digest)
            )
    except (ReleaseError, OSError) as error:
        print(f"immutable release refused: {error}", file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
