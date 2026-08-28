package com.seoulfit.backend.config;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.tracing.exporter.FinishedSpan;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.Ordered;

/** Shared redaction at log/client conventions and the final span export boundary. */
public final class TelemetrySanitizer {

    private static final Throwable REDACTED_ERROR = new RedactedTelemetryException();

    private TelemetrySanitizer() {}

    /**
     * Keep only the remote origin. Query, fragment, user-info, and path are all
     * omitted because Seoul Open Data credentials can occur outside the query.
     */
    public static String origin(URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
            return "relative-or-unknown";
        }
        try {
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), null, null, null)
                    .toASCIIString();
        } catch (URISyntaxException ignored) {
            return "invalid-uri";
        }
    }

    /**
     * Exception messages and stack traces can embed request credentials, SQL,
     * and JDBC bound values. Replace exception events immediately before export
     * while retaining bounded error status and correlation identity.
     */
    static FinishedSpan sanitizeExportedSpan(FinishedSpan span) {
        Map<String, Object> safeTags = new LinkedHashMap<>();
        span.getTypedTags().forEach((key, value) -> {
            if (!isSensitiveExceptionAttribute(key)) {
                safeTags.put(key, value);
            }
        });
        span.setTypedTags(safeTags);

        if (span.getError() != null) {
            span.setEvents(span.getEvents().stream()
                    .filter(event -> !"exception".equalsIgnoreCase(event.getValue()))
                    .toList());
            span.setError(REDACTED_ERROR);
        }
        return span;
    }

    /**
     * OTel derives span status descriptions during Observation.onError, before
     * export filters run. Replace the throwable first so that both the status
     * and the later exception event are created from bounded text.
     */
    static ObservationHandler<Observation.Context> sanitizeObservedErrors() {
        return new SanitizingObservationHandler();
    }

    /**
     * Micrometer's generic FinishedSpan filter cannot alter OTel status
     * descriptions. Decorate each concrete exporter so no raw throwable text
     * crosses the final process boundary.
     */
    static SpanExporter sanitizeExporter(SpanExporter exporter) {
        if (exporter instanceof SanitizingSpanExporter) {
            return exporter;
        }
        return new SanitizingSpanExporter(exporter);
    }

    private static boolean isSensitiveExceptionAttribute(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.equals("exception.message")
                || normalized.equals("exception.stacktrace")
                || normalized.equals("error.message")
                || normalized.equals("error.stacktrace");
    }

    private static final class RedactedTelemetryException extends RuntimeException {

        private RedactedTelemetryException() {
            super("redacted", null, false, false);
        }
    }

    private static final class SanitizingObservationHandler
            implements ObservationHandler<Observation.Context>, Ordered {

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        @Override
        public void onError(Observation.Context context) {
            if (context.getError() != null) {
                context.setError(REDACTED_ERROR);
            }
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }

    private static final class SanitizingSpanExporter implements SpanExporter {

        private final SpanExporter delegate;

        private SanitizingSpanExporter(SpanExporter delegate) {
            this.delegate = delegate;
        }

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            return delegate.export(spans.stream()
                    .map(span -> (SpanData) new SanitizedSpanData(span))
                    .toList());
        }

        @Override
        public CompletableResultCode flush() {
            return delegate.flush();
        }

        @Override
        public CompletableResultCode shutdown() {
            return delegate.shutdown();
        }
    }

    private static final class SanitizedSpanData extends DelegatingSpanData {

        private final Attributes attributes;
        private final List<EventData> events;
        private final StatusData status;

        private SanitizedSpanData(SpanData delegate) {
            super(delegate);
            this.attributes = sanitizeAttributes(delegate.getAttributes());
            this.events = delegate.getEvents().stream()
                    .filter(event -> !"exception".equalsIgnoreCase(event.getName()))
                    .map(event -> {
                        Attributes safeAttributes = sanitizeAttributes(event.getAttributes());
                        return EventData.create(
                                event.getEpochNanos(), event.getName(), safeAttributes, safeAttributes.size());
                    })
                    .toList();
            StatusData originalStatus = delegate.getStatus();
            this.status = originalStatus.getDescription().isBlank()
                    ? originalStatus
                    : StatusData.create(originalStatus.getStatusCode(), "redacted");
        }

        @Override
        public Attributes getAttributes() {
            return attributes;
        }

        @Override
        public List<EventData> getEvents() {
            return events;
        }

        @Override
        public StatusData getStatus() {
            return status;
        }

        @Override
        public int getTotalRecordedEvents() {
            return events.size();
        }

        @Override
        public int getTotalAttributeCount() {
            return attributes.size();
        }
    }

    private static Attributes sanitizeAttributes(Attributes attributes) {
        return attributes.toBuilder()
                .removeIf(key -> isSensitiveExceptionAttribute(key.getKey()))
                .build();
    }
}
