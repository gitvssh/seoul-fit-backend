# Immutable backend image release

`infra/scripts/release_immutable_image.py` is the canonical, local-only release
entrypoint. It binds the full source commit, OCI revision label, Harbor manifest
digest, Kubernetes image pin, and `service.version` to one release. It does not
commit or push Git, change Kubernetes or Argo CD, call `sudo`, or store a
credential outside its temporary Docker configuration.

## Runtime prerequisites

- canonical `main` is clean and exactly equals `origin/main` after an explicit
  `git fetch`; the publish SHA must be `HEAD`
- Docker with BuildKit is installed and the daemon is reachable
- `XDG_RUNTIME_DIR=/run/user/$(id -u)` is owner-only
- `/run/vault-proxy/seoul-fit-release-agent.sock` is healthy and returns HTTP
  200 only for `kv/data/projects/seoul-fit/harbor-ci`; that document contains
  exactly `username` and `password`
- the Harbor robot can pull and push only `seoul-fit/backend`

The tool creates `DOCKER_CONFIG` below `XDG_RUNTIME_DIR` with mode `0700`, sends
the password only to `docker login --password-stdin`, suppresses command output,
and removes the directory on exit. It never reads `~/.docker/config.json`.

## Review, publish, and pin

The plan command is read-only and does not contact Vault, Docker, or Harbor:

```bash
python3 infra/scripts/release_immutable_image.py plan \
  --source-sha <40-lowercase-hex>
```

After the runtime prerequisites and exact SHA are reviewed, publish locally:

```bash
python3 infra/scripts/release_immutable_image.py publish \
  --source-sha <40-lowercase-hex> --execute
```

The publisher checks the remote immutable tag before building/pushing, refuses
an overwrite, runs the image's embedded runtime-contract smoke check, and reads
the registry manifest digest twice after push. An interrupted retry reuses an
existing tag only after its OCI revision label is proven equal to the source.

Pin development first, review and commit only the generated dev overlay and
`infra/releases/dev.json`, then let the normal CI and manual Argo workflow
validate development:

```bash
python3 infra/scripts/release_immutable_image.py pin \
  --environment dev --source-sha <40-lowercase-hex> \
  --digest sha256:<64-lowercase-hex> --execute
```

Production is a separate command and change. It is refused unless the committed
dev receipt contains the exact same source and digest:

```bash
python3 infra/scripts/release_immutable_image.py pin \
  --environment prod --source-sha <40-lowercase-hex> \
  --digest sha256:<64-lowercase-hex> --execute
```

Neither pin command commits, pushes, force-updates, syncs, prunes, or deletes.
Run the repository validators, inspect the exact diff, and use the repository's
normal publication and manual-sync process afterward.

## Source-only validation

```bash
python3 -m unittest discover -s infra/scripts/tests -p 'test_*.py'
python3 infra/scripts/release_immutable_image.py plan \
  --source-sha "$(git rev-parse HEAD)"
python3 infra/scripts/validate_observability_contract.py
python3 .github/scripts/validate_workflow_runners.py homelab-seoul-fit-backend
```
