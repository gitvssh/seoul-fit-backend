package com.seoulfit.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seoulfit.backend.shared.exception.GlobalExceptionHandler;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.reactive.function.client.WebClient;

@SpringBootTest(
        classes = ObservabilityRuntimeTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "DEPLOYMENT_ENVIRONMENT_NAME=test",
            "OTEL_SERVICE_NAME=seoul-fit-backend",
            "OTEL_SERVICE_NAMESPACE=seoul-fit",
            "OTEL_SERVICE_VERSION=test-build-617f203",
            "OTEL_SERVICE_INSTANCE_ID=test-pod-uid",
            "OTEL_TRACES_EXPORT_ENABLED=false",
            "OBSERVABILITY_TRACING_SAMPLING_PROBABILITY=1.0",
            "management.tracing.opentelemetry.export.schedule-delay=10ms"
        })
@ExtendWith(OutputCaptureExtension.class)
@AutoConfigureObservability
class ObservabilityRuntimeTest {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityRuntimeTest.class);
    private static final Logger securityOutputLog =
            LoggerFactory.getLogger("com.seoulfit.backend.observability.security-output-probe");
    private static final String SQL_EXCEPTION_SENTINEL = "sql_exception_secret_probe_7d410d";
    private static final String SQL_BOUND_VALUE_SENTINEL = "sql_bound_value_secret_731bac";
    private static final String RUNTIME_EXCEPTION_SENTINEL = "token=must-not-appear-runtime-614c7f";
    private static final String CLIENT_EXCEPTION_SENTINEL = "token=must-not-appear-client-3c22ea";
    private static final String LOG_ARGUMENT_SENTINEL = "token=must-not-appear-log-argument-91c6b5";
    private static final String LOG_THROWABLE_SENTINEL = "token=must-not-appear-log-throwable-b3102a";
    private static final String LOG_HEADER_SENTINEL = "Bearer must-not-appear-log-header-577d9e";
    private static final String LOG_BODY_SENTINEL = "must-not-appear-log-body-f8106c";

    @Autowired
    private Tracer tracer;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ObservationRegistry observationRegistry;

    @Autowired
    private Resource resource;

    @Autowired
    private SdkTracerProvider sdkTracerProvider;

    @Autowired
    private CollectingSpanSink spanSink;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    @Qualifier("taskExecutor")
    private Executor taskExecutor;

    @Autowired
    @LocalServerPort
    private int serverPort;

    @Autowired
    private RestClient.Builder restClientBuilder;

    @Autowired
    private WebClient.Builder webClientBuilder;

    @Test
    void usesOpenTelemetryBridgeAndOneIdentityAcrossTracesAndMetrics() {
        assertThat(tracer.getClass().getName()).contains("OtelTracer");
        assertThat(ClassUtils.isPresent("brave.Tracing", getClass().getClassLoader())).isFalse();

        assertThat(resource.getAttribute(AttributeKey.stringKey("service.name")))
                .isEqualTo("seoul-fit-backend");
        assertThat(resource.getAttribute(AttributeKey.stringKey("service.namespace")))
                .isEqualTo("seoul-fit");
        assertThat(resource.getAttribute(AttributeKey.stringKey("service.version")))
                .isEqualTo("test-build-617f203");
        assertThat(resource.getAttribute(AttributeKey.stringKey("service.instance.id")))
                .isEqualTo("test-pod-uid");
        assertThat(resource.getAttribute(AttributeKey.stringKey("deployment.environment.name")))
                .isEqualTo("test");

        Counter counter = meterRegistry.counter("observability.contract.test");
        assertThat(counter.getId().getTag("service_name")).isEqualTo("seoul-fit-backend");
        assertThat(counter.getId().getTag("service_namespace")).isEqualTo("seoul-fit");
        assertThat(counter.getId().getTag("service_version")).isEqualTo("test-build-617f203");
        assertThat(counter.getId().getTag("service_instance_id")).isEqualTo("test-pod-uid");
        assertThat(counter.getId().getTag("deployment_environment_name")).isEqualTo("test");
    }

    @Test
    void emitsContractJsonWithTraceCorrelation(CapturedOutput output) throws Exception {
        Span span = tracer.nextSpan().name("observability-contract-test").start();
        String expectedTraceId = span.context().traceId();
        String expectedSpanId = span.context().spanId();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            log.info("observability-contract-probe");
        } finally {
            span.end();
        }

        String jsonLine = output.getOut().lines()
                .filter(line -> line.contains("\"message\":\"observability-contract-probe\""))
                .findFirst()
                .orElseThrow();
        JsonNode json = objectMapper.readTree(jsonLine);

        assertThat(json.path("@timestamp").asText()).isNotBlank();
        assertThat(json.path("level").asText()).isEqualTo("INFO");
        assertThat(json.path("log_schema").asText()).isEqualTo("spring_boot_otel_json_v1");
        assertThat(json.path("log_category").asText()).isEqualTo("application");
        assertThat(json.path("service_name").asText()).isEqualTo("seoul-fit-backend");
        assertThat(json.path("service_namespace").asText()).isEqualTo("seoul-fit");
        assertThat(json.path("service_version").asText()).isEqualTo("test-build-617f203");
        assertThat(json.path("service_instance_id").asText()).isEqualTo("test-pod-uid");
        assertThat(json.path("deployment_environment_name").asText()).isEqualTo("test");
        assertThat(json.path("trace_id").asText()).isEqualTo(expectedTraceId);
        assertThat(json.path("span_id").asText()).isEqualTo(expectedSpanId);
        assertThat(json.has("traceId")).isFalse();
        assertThat(json.has("spanId")).isFalse();
    }

    @Test
    void finalJsonBoundaryDropsRawErrorArgumentsMessagesAndStacks(CapturedOutput output)
            throws Exception {
        int outputStart = output.getAll().length();

        securityOutputLog
                .atError()
                .addKeyValue("authorization", LOG_HEADER_SENTINEL)
                .addKeyValue("request_body", LOG_BODY_SENTINEL)
                .setCause(new RuntimeException(LOG_THROWABLE_SENTINEL))
                .log("exception-probe detail={}", LOG_ARGUMENT_SENTINEL);
        securityOutputLog.error("oauth-direct-message={}", LOG_ARGUMENT_SENTINEL);

        String emitted = output.getAll().substring(outputStart);
        assertThat(emitted)
                .doesNotContain(
                        LOG_ARGUMENT_SENTINEL,
                        LOG_THROWABLE_SENTINEL,
                        LOG_HEADER_SENTINEL,
                        LOG_BODY_SENTINEL,
                        "authorization",
                        "request_body");

        List<JsonNode> records = emitted.lines()
                .filter(line -> line.contains(
                        "\"logger_name\":\"com.seoulfit.backend.observability.security-output-probe\""))
                .map(this::parseJson)
                .toList();
        assertThat(records).hasSize(2);
        assertThat(records)
                .allSatisfy(logRecord -> {
                    assertThat(logRecord.path("message").asText()).isEqualTo("Application error");
                    assertThat(logRecord.path("level").asText()).isEqualTo("ERROR");
                    assertThat(logRecord.path("log_schema").asText())
                            .isEqualTo("spring_boot_otel_json_v1");
                    assertThat(logRecord.has("stack_trace")).isFalse();
                    assertThat(logRecord.has("exception")).isFalse();
                    assertThat(logRecord.has("error")).isFalse();
                });
        assertThat(records).anySatisfy(logRecord ->
                assertThat(logRecord.path("error_type").asText()).isEqualTo("RuntimeException"));
    }

    @Test
    void propagatesTraceContextThroughTheApplicationExecutor() throws Exception {
        Observation observation = Observation.start("async-context-test", observationRegistry);
        try (Observation.Scope ignored = observation.openScope()) {
            String expectedTraceId = tracer.currentSpan().context().traceId();
            String asyncTraceId = CompletableFuture.supplyAsync(
                            () -> tracer.currentSpan().context().traceId(), taskExecutor)
                    .get(5, TimeUnit.SECONDS);
            assertThat(asyncTraceId).isEqualTo(expectedTraceId);
        } finally {
            observation.stop();
        }
    }

    @Test
    void doesNotExportServerQueryHeaderOrBodySecrets() throws Exception {
        flushAndClearSpans();

        restClientBuilder.clone().build().post()
                .uri("http://127.0.0.1:" + serverPort
                        + "/observability/security-probe?token=server-query-secret")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer server-header-secret")
                        .header("X-Api-Key", "server-api-key-secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"password\":\"server-body-secret\"}")
                        .retrieve()
                        .toBodilessEntity();

        List<SpanData> serverSpans = exportedSpans(SpanKind.SERVER);
        assertThat(serverSpans).isNotEmpty();
        assertNoSensitiveAttributes(serverSpans,
                "server-query-secret", "server-header-secret", "server-api-key-secret",
                "server-body-secret", "token=");
    }

    @Test
    void doesNotExportBlockingOrReactiveClientCredentials() throws Exception {
        flushAndClearSpans();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(jsonResponse());
            server.enqueue(jsonResponse());
            server.start();

            restClientBuilder.clone().build().post()
                    .uri(server.url("/credential/in/path?token=blocking-query-secret").uri())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer blocking-header-secret")
                    .header("X-Api-Key", "blocking-api-key-secret")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"password\":\"blocking-body-secret\"}")
                    .retrieve()
                    .toBodilessEntity();

            webClientBuilder.clone().build().post()
                    .uri(server.url("/credential/in/path?token=reactive-query-secret").uri())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer reactive-header-secret")
                    .header("X-Api-Key", "reactive-api-key-secret")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"password\":\"reactive-body-secret\"}")
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        }

        List<SpanData> clientSpans = exportedSpans(SpanKind.CLIENT);
        assertThat(clientSpans).hasSizeGreaterThanOrEqualTo(2);
        assertNoSensitiveAttributes(clientSpans,
                "credential/in/path", "blocking-query-secret", "blocking-header-secret",
                "blocking-api-key-secret", "blocking-body-secret", "reactive-query-secret",
                "reactive-header-secret", "reactive-api-key-secret", "reactive-body-secret", "token=");
    }

    @Test
    void doesNotExportSqlExceptionOrBoundValueToLogsOrSpanEvents(CapturedOutput output) {
        flushAndClearSpans();

        SqlProbeResponse response = restClientBuilder.clone().build().post()
                .uri("http://127.0.0.1:" + serverPort + "/observability/sql-error-probe")
                .exchange((request, clientResponse) -> new SqlProbeResponse(
                        clientResponse.getStatusCode().value(),
                        new String(clientResponse.getBody().readAllBytes())));

        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(response.body()).contains("서버 내부 오류가 발생했습니다.");
        assertThat(response.body()).doesNotContain(SQL_EXCEPTION_SENTINEL, SQL_BOUND_VALUE_SENTINEL);
        assertThat(output.getAll()).doesNotContain(SQL_EXCEPTION_SENTINEL, SQL_BOUND_VALUE_SENTINEL);

        List<SpanData> spans = exportedSpans(SpanKind.SERVER);
        assertThat(spans).isNotEmpty();
        assertNoSensitiveTelemetry(spans, SQL_EXCEPTION_SENTINEL, SQL_BOUND_VALUE_SENTINEL);
    }

    @Test
    void redactsUnhandledRuntimeExceptionFromLogsEventsAndStatus(CapturedOutput output) {
        flushAndClearSpans();

        SqlProbeResponse response = restClientBuilder.clone().build().post()
                .uri("http://127.0.0.1:" + serverPort + "/observability/runtime-error-probe")
                .exchange((request, clientResponse) -> new SqlProbeResponse(
                        clientResponse.getStatusCode().value(),
                        new String(clientResponse.getBody().readAllBytes())));

        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        assertThat(response.body()).doesNotContain(RUNTIME_EXCEPTION_SENTINEL);
        assertThat(output.getAll()).doesNotContain(RUNTIME_EXCEPTION_SENTINEL);

        List<SpanData> spans = exportedSpans(SpanKind.SERVER);
        assertThat(spans).isNotEmpty();
        assertNoSensitiveTelemetry(spans, RUNTIME_EXCEPTION_SENTINEL);
    }

    @Test
    void redactsClientExceptionFromEventsAndStatus() throws Exception {
        flushAndClearSpans();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
            server.start();

            assertThatThrownBy(() -> restClientBuilder.clone().build().post()
                            .uri(server.url("/failure?" + CLIENT_EXCEPTION_SENTINEL).uri())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("client-exception-body-secret")
                            .retrieve()
                            .toBodilessEntity())
                    .isInstanceOf(RestClientException.class);
        }

        List<SpanData> spans = exportedSpans(SpanKind.CLIENT);
        assertThat(spans).isNotEmpty();
        assertNoSensitiveTelemetry(
                spans, CLIENT_EXCEPTION_SENTINEL, "client-exception-body-secret", "/failure");
    }

    private MockResponse jsonResponse() {
        return new MockResponse()
                .setResponseCode(200)
                .addHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .setBody("{\"ok\":true}");
    }

    private JsonNode parseJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new AssertionError("expected one-line JSON log", exception);
        }
    }

    private List<SpanData> exportedSpans(SpanKind kind) {
        sdkTracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
        return spanSink.spans().stream().filter(span -> span.getKind() == kind).toList();
    }

    private void flushAndClearSpans() {
        sdkTracerProvider.forceFlush().join(5, TimeUnit.SECONDS);
        spanSink.clear();
    }

    private static void assertNoSensitiveAttributes(List<SpanData> spans, String... forbidden) {
        String exported = spans.stream()
                .flatMap(span -> span.getAttributes().asMap().entrySet().stream())
                .map(entry -> entry.getKey().getKey() + "=" + entry.getValue())
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(exported).doesNotContain(forbidden);
    }

    private static void assertNoSensitiveTelemetry(List<SpanData> spans, String... forbidden) {
        String exported = spans.stream()
                .map(SpanData::toString)
                .reduce("", (left, right) -> left + "\n" + right);
        assertThat(exported).doesNotContain(forbidden);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
        DataSourceAutoConfiguration.class,
        JpaRepositoriesAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        FlywayAutoConfiguration.class
    })
    @Import({
        ObservabilityConfig.class,
        AsyncConfig.class,
        GlobalExceptionHandler.class,
        SecurityProbeController.class,
        SqlErrorProbeController.class,
        RuntimeErrorProbeController.class
    })
    static class TestApplication {

        @Bean
        CollectingSpanSink collectingSpanSink() {
            return new CollectingSpanSink();
        }

        @Bean
        SpanExporter collectingSpanExporter(CollectingSpanSink spanSink) {
            return new CollectingSpanExporter(spanSink);
        }

        @Bean
        DataSource sqlProbeDataSource() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName("org.h2.Driver");
            dataSource.setUrl("jdbc:h2:mem:observability_contract;DB_CLOSE_DELAY=-1");
            dataSource.setUsername("sa");
            dataSource.setPassword("");
            return dataSource;
        }

        @Bean
        JdbcTemplate sqlProbeJdbcTemplate(DataSource sqlProbeDataSource) {
            return new JdbcTemplate(sqlProbeDataSource);
        }

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
            return http.csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                    .build();
        }
    }

    @RestController
    static class SecurityProbeController {

        @PostMapping("/observability/security-probe")
        Map<String, Boolean> probe(@RequestBody String ignored) {
            return Map.of("ok", true);
        }
    }

    @RestController
    static class SqlErrorProbeController {

        private final JdbcTemplate jdbcTemplate;

        SqlErrorProbeController(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @PostMapping("/observability/sql-error-probe")
        Map<String, Boolean> failWithRealBoundSql() {
            jdbcTemplate.execute("drop table if exists " + SQL_EXCEPTION_SENTINEL);
            jdbcTemplate.execute("create table " + SQL_EXCEPTION_SENTINEL
                    + " (id integer primary key, secret_value varchar(255))");
            jdbcTemplate.update(
                    "insert into " + SQL_EXCEPTION_SENTINEL + " (id, secret_value) values (?, ?)",
                    1,
                    SQL_BOUND_VALUE_SENTINEL);
            jdbcTemplate.update(
                    "insert into " + SQL_EXCEPTION_SENTINEL + " (id, secret_value) values (?, ?)",
                    1,
                    SQL_BOUND_VALUE_SENTINEL);
            return Map.of("ok", true);
        }
    }

    @RestController
    static class RuntimeErrorProbeController {

        @PostMapping("/observability/runtime-error-probe")
        Map<String, Boolean> failWithUnhandledRuntimeException() {
            throw new RuntimeException(RUNTIME_EXCEPTION_SENTINEL);
        }
    }

    private record SqlProbeResponse(int status, String body) {}

    static final class CollectingSpanExporter implements SpanExporter {

        private final CollectingSpanSink sink;

        CollectingSpanExporter(CollectingSpanSink sink) {
            this.sink = sink;
        }

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            sink.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

    static final class CollectingSpanSink {

        private final List<SpanData> spans = new CopyOnWriteArrayList<>();

        void addAll(Collection<SpanData> spans) {
            this.spans.addAll(spans);
        }

        List<SpanData> spans() {
            return List.copyOf(spans);
        }

        void clear() {
            spans.clear();
        }
    }
}
