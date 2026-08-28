# Observability contract

Seoul Fit uses the homelab cross-language observability contract. Logs, metrics,
and traces identify the same exact service release and pod, so Grafana can move
from an error log to its trace without guessing which deployment produced it.

## Signal paths

| Signal | Producer and transport | Cluster destination |
|---|---|---|
| Application logs | Spring Boot Logstash JSON on container stdout | Alloy → Loki |
| Application metrics | Actuator `/actuator/prometheus` | Prometheus scrape |
| Application traces | Micrometer tracing bridge OTel, OTLP/HTTP | OTel Collector → Tempo |

OTLP log export is deliberately disabled: stdout is the one log source. OTLP
metrics export is also disabled while Prometheus scraping is the canonical
metrics path, preventing duplicate time series during the migration.

## Shared identity

Every signal uses these values:

| OTel resource | Log field | Prometheus common tag | Deployment source |
|---|---|---|---|
| `service.name` | `service_name` | `service_name` | `seoul-fit-backend` |
| `service.namespace` | `service_namespace` | `service_namespace` | `seoul-fit` |
| `service.version` | `service_version` | `service_version` | pinned image digest |
| `service.instance.id` | `service_instance_id` | `service_instance_id` | Pod UID |
| `deployment.environment.name` | `deployment_environment_name` | `deployment_environment_name` | dev/prod pod label |

The pod template annotation `observability.damecasol.com/service-version` must
equal the overlay's image digest, and its log-schema annotation is fixed to the
registered `spring_boot_otel_json_v1` contract. The manifest validator rejects
drift. The application refuses to start under a non-local profile if any of the
five identity values is empty, local, or another placeholder. Local and test
processes keep non-Kubernetes defaults and remain usable.

All stdout records carry `log_schema=spring_boot_otel_json_v1`, `log_category`, and
the five identity fields. Records written inside an observed request or job
also carry normalized `trace_id` and `span_id`. These high-cardinality values
remain JSON fields and must not become Loki labels.

## Runtime boundaries

- W3C trace context is produced; W3C and legacy B3 context are accepted while
  callers migrate.
- The default trace sampling probability is 10%. Override only with an explicit
  per-environment budget.
- `/actuator/**` observations are excluded so health probes and Prometheus
  scrapes do not consume the trace budget.
- Reactor automatic context propagation and the application task executor keep
  observation context across reactive and `@Async` boundaries.
- Boot-managed HTTP client builders keep RestTemplate, RestClient, and WebClient
  trace propagation active. Client span URLs and request logs retain only the
  remote origin; query, path, user-info, headers, and bodies are never exported.
- Deployed profiles disable Hibernate/JDBC statement and bind logging. Unused
  datasource-proxy and P6Spy dependencies are absent, and database exception
  handling logs only a safe exception category rather than driver messages or
  bound values.
- Observation errors are bounded before tracing and every concrete OTel exporter
  removes exception events and raw status descriptions. Runtime, JDBC, and HTTP
  client exception messages and stack traces therefore cannot cross the export
  boundary; span error status and trace correlation remain available.
- The Collector endpoint is cluster-internal and contains no credentials.

## Validation and release

Run before publishing a release:

```bash
./gradlew test --no-daemon
python3 infra/scripts/validate_argocd_registration.py
python3 infra/scripts/validate_observability_contract.py
kubectl kustomize infra/k8s/seoul-fit-backend/overlays/dev >/dev/null
kubectl kustomize infra/k8s/seoul-fit-backend/overlays/prod >/dev/null
```

When promoting an image, update both the overlay `digest` and its
`service-version` annotation to that same digest. After Argo CD sync, verify a
fresh JSON record, a Prometheus series with the release tags, and an OTLP trace
with the same resource identity. Collector reachability and the platform Alloy
JSON parser are deployment prerequisites; no NetworkPolicy or collector
permission is owned by this repository.
