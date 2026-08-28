package com.seoulfit.backend.config;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.tracing.exporter.SpanFilter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.ClientRequestObservationConvention;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Application-specific observability boundaries.
 *
 * <p>Health and monitoring polls are operational control traffic. Excluding the
 * complete actuator subtree prevents probes and Prometheus scrapes from consuming
 * the application's trace budget while business requests remain observable.
 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfig {

    private static final Set<String> NON_DEPLOYED_ENVIRONMENTS = Set.of("local", "test");
    private static final Set<String> INVALID_IDENTITY_VALUES =
            Set.of("", "unknown", "unset", "none", "null", "local", "test", "development", "placeholder");

    @Bean
    ObservationPredicate excludeActuatorControlTraffic() {
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext serverRequest) {
                String requestUri = serverRequest.getCarrier().getRequestURI();
                return !(requestUri.equals("/actuator") || requestUri.startsWith("/actuator/"));
            }
            return true;
        };
    }

    /**
     * Spring's default HTTP client convention exports the complete URI. Seoul
     * Open Data credentials can appear in the path, so spans retain only origin
     * and the low-cardinality URI template supplied by instrumented builders.
     */
    @Bean
    ClientRequestObservationConvention sanitizedBlockingClientConvention() {
        return new DefaultClientRequestObservationConvention() {
            @Override
            protected KeyValue requestUri(ClientRequestObservationContext context) {
                if (context.getCarrier() == null) {
                    return super.requestUri(context);
                }
                return KeyValue.of("http.url", TelemetrySanitizer.origin(context.getCarrier().getURI()));
            }
        };
    }

    @Bean
    org.springframework.web.reactive.function.client.ClientRequestObservationConvention
            sanitizedReactiveClientConvention() {
        return new org.springframework.web.reactive.function.client.DefaultClientRequestObservationConvention() {
            @Override
            protected KeyValue httpUrl(
                    org.springframework.web.reactive.function.client.ClientRequestObservationContext context) {
                if (context.getRequest() == null) {
                    return super.httpUrl(context);
                }
                return KeyValue.of("http.url", TelemetrySanitizer.origin(context.getRequest().url()));
            }
        };
    }

    @Bean
    SpanFilter sanitizeExportedExceptionDetails() {
        return TelemetrySanitizer::sanitizeExportedSpan;
    }

    @Bean
    ObservationHandler<Observation.Context> sanitizeObservedErrorDetails() {
        return TelemetrySanitizer.sanitizeObservedErrors();
    }

    @Bean
    static BeanPostProcessor sanitizeConcreteSpanExporters() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof SpanExporter exporter) {
                    return TelemetrySanitizer.sanitizeExporter(exporter);
                }
                return bean;
            }
        };
    }

    /**
     * Fail closed when a deployed profile would publish telemetry that cannot be
     * joined to an exact release and pod. Local and test processes intentionally
     * retain useful defaults so contributors do not need Kubernetes metadata.
     */
    @Bean
    InitializingBean validateDeployedObservabilityIdentity(Environment environment) {
        return () -> {
            String deploymentEnvironment = environment.getProperty("DEPLOYMENT_ENVIRONMENT_NAME", "local");
            boolean onlyLocalProfiles = Arrays.stream(environment.getActiveProfiles())
                    .allMatch(profile -> NON_DEPLOYED_ENVIRONMENTS.contains(profile.toLowerCase(Locale.ROOT)));
            if (onlyLocalProfiles
                    && NON_DEPLOYED_ENVIRONMENTS.contains(deploymentEnvironment.toLowerCase(Locale.ROOT))) {
                return;
            }

            requireIdentity("deployment.environment.name", deploymentEnvironment);
            requireIdentity("service.name", environment.getProperty(
                    "OTEL_SERVICE_NAME", environment.getProperty("spring.application.name")));
            requireIdentity("service.namespace", environment.getProperty(
                    "OTEL_SERVICE_NAMESPACE", environment.getProperty("spring.application.group")));
            requireIdentity("service.version", environment.getProperty(
                    "OTEL_SERVICE_VERSION", environment.getProperty("spring.application.version")));
            requireIdentity("service.instance.id", environment.getProperty("OTEL_SERVICE_INSTANCE_ID"));
        };
    }

    private static void requireIdentity(String key, String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (INVALID_IDENTITY_VALUES.contains(normalized) || normalized.startsWith("unknown_service:")) {
            throw new IllegalStateException(key + " must identify the deployed workload");
        }
    }
}
