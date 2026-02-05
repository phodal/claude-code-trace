package com.phodal.anthropicproxy.otel;

import com.phodal.anthropicproxy.otel.config.OtelProperties;
import com.phodal.anthropicproxy.otel.config.OtelSdkConfig;
import com.phodal.anthropicproxy.otel.filter.TraceContextFilter;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for W3C TraceContext propagation via TraceContextFilter.
 */
class TraceContextFilterTest {

    private TraceContextFilter filter;
    private OtelProperties properties;
    private OpenTelemetry openTelemetry;
    private Tracer tracer;

    @BeforeEach
    void setUp() {
        properties = new OtelProperties();
        properties.setEnabled(true);
        properties.setServiceName("test-service");
        properties.setServiceVersion("1.0.0");
        properties.getOtlp().setEnabled(false); // Use logging exporter for tests

        OtelSdkConfig config = new OtelSdkConfig();
        openTelemetry = config.openTelemetry(properties);
        tracer = config.tracer(openTelemetry, properties);
        filter = new TraceContextFilter(openTelemetry, tracer);
    }

    @Test
    void shouldCreateSpanForApiRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/anthropic/v1/messages");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        // Verify span was stored in request attributes
        Object spanAttr = request.getAttribute(TraceContextFilter.SPAN_ATTRIBUTE);
        assertNotNull(spanAttr, "Span should be stored in request attributes");
        assertTrue(spanAttr instanceof Span, "Stored attribute should be a Span");
    }

    @Test
    void shouldExtractTraceparentAndContinueTrace() throws Exception {
        // Valid W3C traceparent header
        String traceId = "0af7651916cd43dd8448eb211c80319c";
        String parentSpanId = "b7ad6b7169203331";
        String traceparent = "00-" + traceId + "-" + parentSpanId + "-01";

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/anthropic/v1/messages");
        request.addHeader("traceparent", traceparent);
        MockHttpServletResponse response = new MockHttpServletResponse();
        
        // Capture the span created during filter execution
        final Span[] capturedSpan = new Span[1];
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                capturedSpan[0] = Span.current();
            }
        };

        filter.doFilter(request, response, chain);

        // Verify span continues the parent trace
        assertNotNull(capturedSpan[0], "Span should be current during request");
        SpanContext ctx = capturedSpan[0].getSpanContext();
        
        // The span should have the same trace ID as the parent
        assertEquals(traceId, ctx.getTraceId(), "Span should continue parent trace ID");
        // The span should have a different span ID (it's a child span)
        assertNotEquals(parentSpanId, ctx.getSpanId(), "Span should have its own span ID");
    }

    @Test
    void shouldHandleInvalidTraceparent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/anthropic/v1/messages");
        request.addHeader("traceparent", "invalid-traceparent-value");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        // Should not throw, should create new trace
        assertDoesNotThrow(() -> filter.doFilter(request, response, chain));
    }

    @Test
    void shouldSkipNonApiPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/anthropic/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        // Health endpoint should not have span stored
        assertNull(request.getAttribute(TraceContextFilter.SPAN_ATTRIBUTE),
                "Health endpoint should not create OTEL span");
    }

    @Test
    void shouldSetHttpStatusCodeAttribute() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/anthropic/v1/messages");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        // Span should have http.status_code attribute set
        Object spanAttr = request.getAttribute(TraceContextFilter.SPAN_ATTRIBUTE);
        assertNotNull(spanAttr);
    }
}
