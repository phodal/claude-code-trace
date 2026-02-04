package com.phodal.anthropicproxy.otel.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OTEL Span representation
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Span {
    
    private String spanId;
    private String traceId;
    private String parentSpanId;
    private String name;
    private SpanKind kind;
    private Instant startTime;
    private Instant endTime;
    
    @Builder.Default
    private Map<String, Object> attributes = new ConcurrentHashMap<>();
    
    @Builder.Default
    private Map<String, String> resource = new ConcurrentHashMap<>();
    
    private SpanStatus status;
    
    @Builder.Default
    private String serviceName = "anthropic-proxy";
    
    /**
     * Calculate duration in milliseconds
     */
    public long getDurationMs() {
        if (startTime != null && endTime != null) {
            return endTime.toEpochMilli() - startTime.toEpochMilli();
        }
        return 0;
    }
    
    /**
     * Calculate duration in nanoseconds (OTEL standard)
     */
    public long getDurationNanos() {
        if (startTime != null && endTime != null) {
            return (endTime.toEpochMilli() - startTime.toEpochMilli()) * 1_000_000;
        }
        return 0;
    }
    
    /**
     * Add attribute to span
     */
    public void addAttribute(String key, Object value) {
        if (attributes == null) {
            attributes = new ConcurrentHashMap<>();
        }
        attributes.put(key, value);
    }
    
    /**
     * Add resource attribute
     */
    public void addResource(String key, String value) {
        if (resource == null) {
            resource = new ConcurrentHashMap<>();
        }
        resource.put(key, value);
    }
}
