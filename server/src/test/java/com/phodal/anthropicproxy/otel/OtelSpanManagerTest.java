package com.phodal.anthropicproxy.otel;

import com.phodal.anthropicproxy.otel.config.OtelProperties;
import com.phodal.anthropicproxy.otel.config.OtelSdkConfig;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OtelSpanManager span lifecycle management.
 */
class OtelSpanManagerTest {

    private OtelSpanManager spanManager;
    private Tracer tracer;

    @BeforeEach
    void setUp() {
        OtelProperties properties = new OtelProperties();
        properties.setEnabled(true);
        properties.setServiceName("test-service");
        properties.getOtlp().setEnabled(false);

        OtelSdkConfig config = new OtelSdkConfig();
        OpenTelemetry openTelemetry = config.openTelemetry(properties);
        tracer = config.tracer(openTelemetry, properties);
        spanManager = new OtelSpanManager(tracer);
    }

    @Test
    void shouldCreateClientSpan() {
        try (OtelSpanManager.SpanScope scope = spanManager.startClientSpan("test.operation")) {
            assertNotNull(scope.span(), "Client span should be created");
            assertTrue(scope.span().isRecording(), "Span should be recording");
            
            // Set some attributes
            scope.setAttribute("test.key", "test.value");
            scope.setAttribute("test.number", 42);
            scope.setStatus(StatusCode.OK);
        }
        // Span should be ended after close
    }

    @Test
    void shouldCreateInternalSpan() {
        try (OtelSpanManager.SpanScope scope = spanManager.startInternalSpan("internal.operation")) {
            assertNotNull(scope.span(), "Internal span should be created");
            assertTrue(scope.span().isRecording(), "Span should be recording");
        }
    }

    @Test
    void shouldHandleNestedSpans() {
        try (OtelSpanManager.SpanScope parent = spanManager.startClientSpan("parent.operation")) {
            String parentSpanId = parent.span().getSpanContext().getSpanId();
            
            try (OtelSpanManager.SpanScope child = spanManager.startInternalSpan("child.operation")) {
                String childSpanId = child.span().getSpanContext().getSpanId();
                
                // Child should have different span ID
                assertNotEquals(parentSpanId, childSpanId, "Child should have different span ID");
                
                // Both should share same trace ID
                assertEquals(
                    parent.span().getSpanContext().getTraceId(),
                    child.span().getSpanContext().getTraceId(),
                    "Parent and child should share trace ID"
                );
            }
        }
    }

    @Test
    void shouldRecordExceptionOnSpan() {
        try (OtelSpanManager.SpanScope scope = spanManager.startClientSpan("failing.operation")) {
            RuntimeException error = new RuntimeException("Test error");
            scope.recordException(error);
            scope.setStatus(StatusCode.ERROR, "Test error occurred");
        }
        // Should not throw
    }

    @Test
    void shouldGetCurrentSpanFromRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        
        // Create a span and store in request
        Span testSpan = tracer.spanBuilder("test").startSpan();
        request.setAttribute("otel.span", testSpan);
        
        Span retrieved = spanManager.getCurrentSpan(request);
        assertEquals(testSpan, retrieved, "Should retrieve span from request attribute");
        
        testSpan.end();
    }

    @Test
    void shouldFallbackToCurrentSpanWhenNotInRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        // No span in request attributes
        
        Span retrieved = spanManager.getCurrentSpan(request);
        assertNotNull(retrieved, "Should fall back to Span.current()");
    }

    @Test
    void shouldAddRequestAttributes() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        Span testSpan = tracer.spanBuilder("test").startSpan();
        request.setAttribute("otel.span", testSpan);
        
        spanManager.addRequestAttributes(request, "claude-3-opus", "user-123", "conv-456", true);
        
        // Attributes are added to the span - we can't easily verify them without exporter
        // but at least it shouldn't throw
        testSpan.end();
    }

    @Test
    void shouldAddResponseAttributes() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        Span testSpan = tracer.spanBuilder("test").startSpan();
        request.setAttribute("otel.span", testSpan);
        
        spanManager.addResponseAttributes(request, 100, 200, 3, "tool1,tool2,tool3");
        
        testSpan.end();
    }

    @Test
    void shouldRecordError() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        Span testSpan = tracer.spanBuilder("test").startSpan();
        request.setAttribute("otel.span", testSpan);
        
        RuntimeException error = new RuntimeException("Something went wrong");
        spanManager.recordError(request, error);
        
        testSpan.end();
    }
}
