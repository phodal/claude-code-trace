package com.phodal.anthropicproxy.otel.filter;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Collections;

/**
 * Servlet filter that extracts W3C TraceContext from incoming requests
 * and creates a SERVER span for the request lifecycle.
 * 
 * This filter:
 * 1. Extracts traceparent/tracestate headers using W3C propagator
 * 2. Creates a SERVER span as child of extracted context (if present)
 * 3. Stores the span in request attributes for downstream use
 * 4. Ensures span is properly closed on request completion
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class TraceContextFilter implements Filter {

    private final OpenTelemetry openTelemetry;
    private final Tracer tracer;

    public static final String SPAN_ATTRIBUTE = "otel.span";
    public static final String SCOPE_ATTRIBUTE = "otel.scope";

    private static final TextMapGetter<HttpServletRequest> HTTP_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(HttpServletRequest carrier) {
            return Collections.list(carrier.getHeaderNames());
        }

        @Override
        public String get(HttpServletRequest carrier, String key) {
            return carrier != null ? carrier.getHeader(key) : null;
        }
    };

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        if (!(request instanceof HttpServletRequest httpRequest) ||
            !(response instanceof HttpServletResponse httpResponse)) {
            chain.doFilter(request, response);
            return;
        }

        // Skip non-API paths (static resources, actuator, etc.)
        String path = httpRequest.getRequestURI();
        if (!shouldTrace(path)) {
            chain.doFilter(request, response);
            return;
        }

        // Extract parent context from headers (traceparent/tracestate)
        Context extractedContext = openTelemetry.getPropagators()
                .getTextMapPropagator()
                .extract(Context.current(), httpRequest, HTTP_GETTER);

        // Create SERVER span
        String spanName = httpRequest.getMethod() + " " + getRoutePattern(path);
        Span span = tracer.spanBuilder(spanName)
                .setParent(extractedContext)
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("http.method", httpRequest.getMethod())
                .setAttribute("http.url", httpRequest.getRequestURL().toString())
                .setAttribute("http.route", path)
                .setAttribute("http.scheme", httpRequest.getScheme())
                .setAttribute("http.host", httpRequest.getServerName())
                .setAttribute("user_agent.original", httpRequest.getHeader("User-Agent"))
                .startSpan();

        // Log if we continued an existing trace
        if (Span.fromContext(extractedContext).getSpanContext().isValid()) {
            log.debug("Continued trace from parent: traceId={}, parentSpanId={}",
                    span.getSpanContext().getTraceId(),
                    Span.fromContext(extractedContext).getSpanContext().getSpanId());
        }

        // Make span current and store in request
        try (Scope scope = span.makeCurrent()) {
            // Store span in request attributes for controllers to add attributes
            httpRequest.setAttribute(SPAN_ATTRIBUTE, span);
            httpRequest.setAttribute(SCOPE_ATTRIBUTE, scope);

            chain.doFilter(request, response);

            // Record response status
            int statusCode = httpResponse.getStatus();
            span.setAttribute("http.status_code", statusCode);
            
            if (statusCode >= 400) {
                span.setStatus(StatusCode.ERROR, "HTTP " + statusCode);
            } else {
                span.setStatus(StatusCode.OK);
            }
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    private boolean shouldTrace(String path) {
        // Trace API endpoints, skip health/actuator/static
        return path.startsWith("/anthropic/") && !path.equals("/anthropic/health");
    }

    private String getRoutePattern(String path) {
        // Normalize paths with IDs to route patterns
        if (path.startsWith("/anthropic/v1/messages")) {
            return "/anthropic/v1/messages";
        }
        return path;
    }
}
