#!/bin/sh
set -eu

runtime_contract_error() {
  printf '%s\n' 'seoul-fit-backend runtime identity contract rejected' >&2
  exit 64
}

[ "${OTEL_SERVICE_NAME:-}" = 'seoul-fit-backend' ] || runtime_contract_error
[ "${OTEL_SERVICE_NAMESPACE:-}" = 'seoul-fit' ] || runtime_contract_error

case "${DEPLOYMENT_ENVIRONMENT_NAME:-}" in
  dev|prod) ;;
  *) runtime_contract_error ;;
esac

printf '%s\n' "${OTEL_SERVICE_VERSION:-}" \
  | grep -Eq '^sha256:[0-9a-f]{64}$' \
  || runtime_contract_error
printf '%s\n' "${OTEL_SERVICE_INSTANCE_ID:-}" \
  | grep -Eq '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' \
  || runtime_contract_error
[ "${OTEL_SERVICE_INSTANCE_ID}" = "${K8S_POD_UID:-}" ] || runtime_contract_error

printf '%s\n' 'homelab-runtime-start-v1'

# JVM_OPTS is the existing operator-owned whitespace-delimited option surface.
# shellcheck disable=SC2086
exec java ${JVM_OPTS:-} -jar /app/app.jar "$@"
