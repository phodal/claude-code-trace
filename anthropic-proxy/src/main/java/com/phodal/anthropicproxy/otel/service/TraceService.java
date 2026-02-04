package com.phodal.anthropicproxy.otel.service;

import com.phodal.anthropicproxy.otel.model.Span;
import com.phodal.anthropicproxy.otel.model.SpanKind;
import com.phodal.anthropicproxy.otel.model.SpanStatus;
import com.phodal.anthropicproxy.otel.model.Trace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing OTEL traces and spans
 */
@Slf4j
@Service
public class TraceService {
    
    private final Map<String, Trace> activeTraces = new ConcurrentHashMap<>();
    
    private final List<Trace> completedTraces = Collections.synchronizedList(new ArrayList<>());
    
    private static final int MAX_COMPLETED_TRACES = 1000;
    
    /**
     * Generate a unique trace ID
     */
    public String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
    
    /**
     * Generate a unique span ID
     */
    public String generateSpanId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
    
    /**
     * Start a new trace
     */
    public Trace startTrace(String traceId) {
        if (traceId == null) {
            traceId = generateTraceId();
        }
        
        Trace trace = Trace.builder()
                .traceId(traceId)
                .build();
        
        activeTraces.put(traceId, trace);
        log.debug("Started new trace: {}", traceId);
        
        return trace;
    }
    
    /**
     * Start a new span within a trace
     */
    public Span startSpan(String traceId, String name, SpanKind kind, String parentSpanId) {
        Span span = Span.builder()
                .spanId(generateSpanId())
                .traceId(traceId)
                .parentSpanId(parentSpanId)
                .name(name)
                .kind(kind)
                .startTime(Instant.now())
                .status(SpanStatus.unset())
                .build();
        
        // Add to active trace
        Trace trace = activeTraces.get(traceId);
        if (trace != null) {
            trace.addSpan(span);
        }
        
        log.debug("Started span: {} in trace: {}", span.getSpanId(), traceId);
        
        return span;
    }
    
    /**
     * End a span
     */
    public void endSpan(Span span, SpanStatus status) {
        span.setEndTime(Instant.now());
        span.setStatus(status);
        
        log.debug("Ended span: {} with status: {}", span.getSpanId(), 
                status != null ? status.getCode() : "UNSET");
    }
    
    /**
     * Complete a trace and move it to completed traces
     */
    public void completeTrace(String traceId) {
        Trace trace = activeTraces.remove(traceId);
        if (trace != null) {
            synchronized (completedTraces) {
                completedTraces.add(trace);

                // Limit size of completed traces
                while (completedTraces.size() > MAX_COMPLETED_TRACES) {
                    completedTraces.remove(0);
                }
            }
            
            log.debug("Completed trace: {} with {} spans", traceId, trace.getSpans().size());
        }
    }
    
    /**
     * Get trace by ID (from active or completed)
     */
    public Trace getTrace(String traceId) {
        Trace trace = activeTraces.get(traceId);
        if (trace == null) {
            synchronized (completedTraces) {
                for (Trace t : completedTraces) {
                    if (t.getTraceId().equals(traceId)) {
                        trace = t;
                        break;
                    }
                }
            }
        }
        return trace;
    }
    
    /**
     * Get recent completed traces
     */
    public List<Trace> getRecentTraces(int limit) {
        synchronized (completedTraces) {
            int size = completedTraces.size();
            int fromIndex = Math.max(0, size - limit);
            List<Trace> recent = new ArrayList<>(completedTraces.subList(fromIndex, size));
            Collections.reverse(recent);
            return recent;
        }
    }

    /**
     * Expose an unmodifiable view of active traces.
     */
    public Map<String, Trace> getActiveTraces() {
        return Collections.unmodifiableMap(activeTraces);
    }

    /**
     * Expose an immutable snapshot of completed traces.
     */
    public List<Trace> getCompletedTraces() {
        synchronized (completedTraces) {
            return Collections.unmodifiableList(new ArrayList<>(completedTraces));
        }
    }
    
    /**
     * Clear all traces (for testing/reset)
     */
    public void clearAllTraces() {
        activeTraces.clear();
        synchronized (completedTraces) {
            completedTraces.clear();
        }
        log.info("Cleared all traces");
    }
}
