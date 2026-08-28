package com.seoulfit.backend.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Final structured-log boundary for exception and context data.
 *
 * <p>WARN/ERROR messages are deliberately bounded because an exception message can be
 * interpolated before Logback sees the event. The logger and a bounded exception type
 * retain triage value without exporting driver, OAuth, HTTP, or SQL details. A top-level
 * allowlist also prevents future MDC or fluent key/value additions from silently exporting
 * headers, request bodies, or credentials.
 */
public final class StructuredLogSanitizer
        implements StructuredLoggingJsonMembersCustomizer<ILoggingEvent> {

    private static final String SAFE_MESSAGE_MEMBER = "safe_message";
    private static final String SAFE_TRACE_ID_MEMBER = "safe_trace_id";
    private static final String SAFE_SPAN_ID_MEMBER = "safe_span_id";
    private static final Pattern TRACE_ID = Pattern.compile("(?!0{32})[0-9a-f]{32}");
    private static final Pattern SPAN_ID = Pattern.compile("(?!0{16})[0-9a-f]{16}");
    private static final Set<String> ALLOWED_MEMBER_NAMES = Set.of(
            "@timestamp",
            "@version",
            SAFE_MESSAGE_MEMBER,
            "logger_name",
            "thread_name",
            "level",
            "level_value",
            SAFE_TRACE_ID_MEMBER,
            SAFE_SPAN_ID_MEMBER,
            "log_schema",
            "log_category",
            "service_name",
            "service_namespace",
            "service_version",
            "service_instance_id",
            "deployment_environment_name",
            "error_type");
    private static final Pattern SAFE_ERROR_TYPE = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]{0,127}");

    @Override
    public void customize(JsonWriter.Members<ILoggingEvent> members) {
        members.add(SAFE_MESSAGE_MEMBER, StructuredLogSanitizer::safeMessage);
        members.add(SAFE_TRACE_ID_MEMBER, event -> traceContext(event).traceId());
        members.add(SAFE_SPAN_ID_MEMBER, event -> traceContext(event).spanId());
        members.add("error_type", StructuredLogSanitizer::boundedErrorType).whenHasLength();
        members.applyingPathFilter(path -> !ALLOWED_MEMBER_NAMES.contains(path.toUnescapedString()));
        members.applyingNameProcessor(
                (path, existingName) -> canonicalMemberName(existingName));
    }

    private static String canonicalMemberName(String existingName) {
        return switch (existingName) {
            case SAFE_MESSAGE_MEMBER -> "message";
            case SAFE_TRACE_ID_MEMBER -> "trace_id";
            case SAFE_SPAN_ID_MEMBER -> "span_id";
            default -> existingName;
        };
    }

    private static TraceContext traceContext(ILoggingEvent event) {
        Map<String, String> context = event.getMDCPropertyMap();
        if (context == null) {
            return TraceContext.EMPTY;
        }
        String traceId = context.get("traceId");
        String spanId = context.get("spanId");
        if (traceId == null
                || spanId == null
                || !TRACE_ID.matcher(traceId).matches()
                || !SPAN_ID.matcher(spanId).matches()) {
            return TraceContext.EMPTY;
        }
        return new TraceContext(traceId, spanId);
    }

    private static String safeMessage(ILoggingEvent event) {
        Level level = event.getLevel();
        if (event.getThrowableProxy() != null || (level != null && level.isGreaterOrEqual(Level.WARN))) {
            return level == Level.WARN ? "Application warning" : "Application error";
        }
        return event.getFormattedMessage();
    }

    private static String boundedErrorType(ILoggingEvent event) {
        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable == null || throwable.getClassName() == null) {
            return "";
        }
        String className = throwable.getClassName();
        int packageSeparator = className.lastIndexOf('.');
        String simpleName = className.substring(packageSeparator + 1);
        return SAFE_ERROR_TYPE.matcher(simpleName).matches() ? simpleName : "Throwable";
    }

    private record TraceContext(String traceId, String spanId) {
        private static final TraceContext EMPTY = new TraceContext("", "");
    }
}
