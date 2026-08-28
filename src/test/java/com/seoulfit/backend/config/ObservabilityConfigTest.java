package com.seoulfit.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.server.observation.ServerRequestObservationContext;

class ObservabilityConfigTest {

    private final ObservabilityConfig configuration = new ObservabilityConfig();

    @Test
    void excludesActuatorTrafficWithoutSuppressingBusinessObservations() {
        ObservationPredicate predicate = configuration.excludeActuatorControlTraffic();

        assertThat(predicate.test("http.server.requests", serverRequest("/actuator/health"))).isFalse();
        assertThat(predicate.test("http.server.requests", serverRequest("/actuator/prometheus"))).isFalse();
        assertThat(predicate.test("http.server.requests", serverRequest("/api/v1/places"))).isTrue();
        assertThat(predicate.test("scheduler.execution", new Observation.Context())).isTrue();
    }

    @Test
    void rejectsEveryAmbiguousIdentityForNonLocalProfiles() {
        Map<String, String> validIdentity = new LinkedHashMap<>();
        validIdentity.put("DEPLOYMENT_ENVIRONMENT_NAME", "dev");
        validIdentity.put("OTEL_SERVICE_NAME", "seoul-fit-backend");
        validIdentity.put("OTEL_SERVICE_NAMESPACE", "seoul-fit");
        validIdentity.put("OTEL_SERVICE_VERSION", "sha256:0123456789abcdef");
        validIdentity.put("OTEL_SERVICE_INSTANCE_ID", "pod-uid-0123456789");

        Map<String, String> contractNames = Map.of(
                "DEPLOYMENT_ENVIRONMENT_NAME", "deployment.environment.name",
                "OTEL_SERVICE_NAME", "service.name",
                "OTEL_SERVICE_NAMESPACE", "service.namespace",
                "OTEL_SERVICE_VERSION", "service.version",
                "OTEL_SERVICE_INSTANCE_ID", "service.instance.id");
        String[] invalidValues = {
            "", "  ", "development", "local", "none", "null", "placeholder", "test", "unknown", "unset",
            "unknown_service:java"
        };

        for (String profile : new String[] {"dev", "prod"}) {
            for (Map.Entry<String, String> target : contractNames.entrySet()) {
                for (String invalidValue : invalidValues) {
                    Map<String, String> candidate = new LinkedHashMap<>(validIdentity);
                    candidate.put("DEPLOYMENT_ENVIRONMENT_NAME", profile);
                    candidate.put(target.getKey(), invalidValue);

                    String[] properties = candidate.entrySet().stream()
                            .map(entry -> entry.getKey() + "=" + entry.getValue())
                            .toArray(String[]::new);
                    new ApplicationContextRunner()
                            .withUserConfiguration(ObservabilityConfig.class)
                            .withPropertyValues("spring.profiles.active=" + profile)
                            .withPropertyValues(properties)
                            .run(context -> {
                                assertThat(context).hasFailed();
                                assertThat(context.getStartupFailure())
                                        .hasRootCauseMessage(
                                                target.getValue() + " must identify the deployed workload");
                            });
                }
            }
        }
    }

    @Test
    void keepsLocalProcessUsableWithoutKubernetesMetadata() {
        new ApplicationContextRunner()
                .withUserConfiguration(ObservabilityConfig.class)
                .run(context -> assertThat(context).hasNotFailed());

        for (String profile : new String[] {"local", "test"}) {
            new ApplicationContextRunner()
                    .withUserConfiguration(ObservabilityConfig.class)
                    .withPropertyValues(
                            "spring.profiles.active=" + profile,
                            "DEPLOYMENT_ENVIRONMENT_NAME=" + profile)
                    .run(context -> assertThat(context).hasNotFailed());
        }
    }

    @Test
    void ignoresAmbientKubernetesServiceHostWithoutExplicitWorkloadMarker() {
        new ApplicationContextRunner()
                .withUserConfiguration(ObservabilityConfig.class)
                .withPropertyValues("KUBERNETES_SERVICE_HOST=10.43.0.1")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void kubernetesPodMarkerPreventsLocalIdentityBypass() {
        new ApplicationContextRunner()
                .withUserConfiguration(ObservabilityConfig.class)
                .withPropertyValues(
                        "spring.profiles.active=local",
                        "DEPLOYMENT_ENVIRONMENT_NAME=local",
                        "OTEL_SERVICE_NAME=seoul-fit-backend",
                        "OTEL_SERVICE_NAMESPACE=seoul-fit",
                        "OTEL_SERVICE_VERSION=sha256:0123456789abcdef",
                        "OTEL_SERVICE_INSTANCE_ID=pod-uid-0123456789",
                        "K8S_POD_UID=pod-uid-0123456789")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("deployment.environment.name must identify the deployed workload");
                });
    }

    @Test
    void kubernetesPodMarkerRequiresExactPodUidIdentity() {
        new ApplicationContextRunner()
                .withUserConfiguration(ObservabilityConfig.class)
                .withPropertyValues(
                        "spring.profiles.active=dev",
                        "DEPLOYMENT_ENVIRONMENT_NAME=dev",
                        "OTEL_SERVICE_NAME=seoul-fit-backend",
                        "OTEL_SERVICE_NAMESPACE=seoul-fit",
                        "OTEL_SERVICE_VERSION=sha256:0123456789abcdef",
                        "OTEL_SERVICE_INSTANCE_ID=reported-pod-uid",
                        "K8S_POD_UID=actual-pod-uid")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("service.instance.id must equal k8s.pod.uid");
                });
    }

    @Test
    void stripsEveryCredentialBearingUriComponent() {
        URI uri = URI.create(
                "https://user:password@api.example.test:8443/api-key-in-path?token=query-secret#fragment");

        assertThat(TelemetrySanitizer.origin(uri)).isEqualTo("https://api.example.test:8443");
    }

    @Test
    void treatsMissingOrRelativeUrisAsUnknown() {
        assertThat(TelemetrySanitizer.origin(null)).isEqualTo("relative-or-unknown");
        assertThat(TelemetrySanitizer.origin(URI.create("/relative/path?token=secret")))
                .isEqualTo("relative-or-unknown");
    }

    @Test
    void reusesSanitizedExportersAndDelegatesTheirLifecycle() {
        SpanExporter delegate = mock(SpanExporter.class);
        CompletableResultCode flushResult = CompletableResultCode.ofSuccess();
        CompletableResultCode shutdownResult = CompletableResultCode.ofSuccess();
        when(delegate.flush()).thenReturn(flushResult);
        when(delegate.shutdown()).thenReturn(shutdownResult);

        SpanExporter sanitized = TelemetrySanitizer.sanitizeExporter(delegate);

        assertThat(TelemetrySanitizer.sanitizeExporter(sanitized)).isSameAs(sanitized);
        assertThat(sanitized.flush()).isSameAs(flushResult);
        assertThat(sanitized.shutdown()).isSameAs(shutdownResult);
    }

    private static ServerRequestObservationContext serverRequest(String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        return new ServerRequestObservationContext(request, mock(HttpServletResponse.class));
    }
}
