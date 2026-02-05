package com.phodal.anthropicproxy.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.phodal.anthropicproxy.otel.filter.TraceContextFilter;

/**
 * Helper class for managing OpenTelemetry spans in business logic.
 * Provides methods to:
 * - Get current span from request
 * - Create child spans for specific operations
 * - Add attributes to spans
 * - Record errors and status
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OtelSpanManager {

    private final Tracer tracer;

    // Custom attribute keys for AI proxy domain
    public static final AttributeKey<String> ATTR_MODEL = AttributeKey.stringKey("ai.model");
    public static final AttributeKey<String> ATTR_USER_ID = AttributeKey.stringKey("user.id");
    public static final AttributeKey<String> ATTR_CONVERSATION_ID = AttributeKey.stringKey("conversation.id");
    public static final AttributeKey<Boolean> ATTR_STREAMING = AttributeKey.booleanKey("ai.streaming");
    public static final AttributeKey<Long> ATTR_INPUT_TOKENS = AttributeKey.longKey("ai.tokens.input");
    public static final AttributeKey<Long> ATTR_OUTPUT_TOKENS = AttributeKey.longKey("ai.tokens.output");
    public static final AttributeKey<String> ATTR_TOOL_USE_IDS = AttributeKey.stringKey("ai.tool_use_ids");
    public static final AttributeKey<Long> ATTR_TOOL_CALL_COUNT = AttributeKey.longKey("ai.tool_call_count");

    /**
     * Get the current span from request attributes (set by TraceContextFilter).
     */
    public Span getCurrentSpan(HttpServletRequest request) {
        Object span = request.getAttribute(TraceContextFilter.SPAN_ATTRIBUTE);
        if (span instanceof Span s) {
            return s;
        }
        return Span.current();
    }

    /**
     * Add common AI request attributes to the current span.
     */
    public void addRequestAttributes(HttpServletRequest request, String model, String userId, 
                                      String conversationId, boolean streaming) {
        Span span = getCurrentSpan(request);
        span.setAttribute(ATTR_MODEL, model);
        span.setAttribute(ATTR_USER_ID, userId);
        span.setAttribute(ATTR_CONVERSATION_ID, conversationId);
        span.setAttribute(ATTR_STREAMING, streaming);
    }

    /**
     * Add response attributes (tokens, tool calls) to current span.
     */
    public void addResponseAttributes(HttpServletRequest request, long inputTokens, long outputTokens,
                                       int toolCallCount, String toolUseIds) {
        Span span = getCurrentSpan(request);
        span.setAttribute(ATTR_INPUT_TOKENS, inputTokens);
        span.setAttribute(ATTR_OUTPUT_TOKENS, outputTokens);
        span.setAttribute(ATTR_TOOL_CALL_COUNT, (long) toolCallCount);
        if (toolUseIds != null && !toolUseIds.isEmpty()) {
            span.setAttribute(ATTR_TOOL_USE_IDS, toolUseIds);
        }
    }

    /**
     * Create a child span for upstream API calls (CLIENT span).
     * Returns a SpanScope that must be closed when the operation completes.
     */
    public SpanScope startClientSpan(String operationName) {
        Span parentSpan = Span.current();
        Span clientSpan = tracer.spanBuilder(operationName)
                .setParent(Context.current().with(parentSpan))
                .setSpanKind(SpanKind.CLIENT)
                .startSpan();
        return new SpanScope(clientSpan);
    }

    /**
     * Create a child span for internal operations (INTERNAL span).
     */
    public SpanScope startInternalSpan(String operationName) {
        Span parentSpan = Span.current();
        Span internalSpan = tracer.spanBuilder(operationName)
                .setParent(Context.current().with(parentSpan))
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        return new SpanScope(internalSpan);
    }

    /**
     * Record an error on the current span.
     */
    public void recordError(HttpServletRequest request, Throwable error) {
        Span span = getCurrentSpan(request);
        span.setStatus(StatusCode.ERROR, error.getMessage());
        span.recordException(error);
    }

    /**
     * Wrapper class that holds a span and its scope for proper lifecycle management.
     */
    public static class SpanScope implements AutoCloseable {
        private final Span span;
        private final io.opentelemetry.context.Scope scope;

        SpanScope(Span span) {
            this.span = span;
            this.scope = span.makeCurrent();
        }

        public Span span() {
            return span;
        }

        public void setStatus(StatusCode status) {
            span.setStatus(status);
        }

        public void setStatus(StatusCode status, String description) {
            span.setStatus(status, description);
        }

        public void recordException(Throwable t) {
            span.recordException(t);
        }

        public void setAttribute(String key, String value) {
            span.setAttribute(key, value);
        }

        public void setAttribute(String key, long value) {
            span.setAttribute(key, value);
        }

        public void setAttribute(String key, boolean value) {
            span.setAttribute(key, value);
        }

        @Override
        public void close() {
            try {
                scope.close();
            } finally {
                span.end();
            }
        }
    }
}
