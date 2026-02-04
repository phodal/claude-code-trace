package com.phodal.anthropicproxy.otel.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OTEL Trace - collection of related spans
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Trace {
    
    private String traceId;
    
    @Builder.Default
    private List<Span> spans = Collections.synchronizedList(new ArrayList<>());
    
    /**
     * Add a span to this trace
     */
    public void addSpan(Span span) {
        if (spans == null) {
            spans = Collections.synchronizedList(new ArrayList<>());
        }
        spans.add(span);
    }
    
    /**
     * Get root span (span without parent)
     */
    public Span getRootSpan() {
        for (Span span : snapshotSpans()) {
            if (span.getParentSpanId() == null) {
                return span;
            }
        }
        return null;
    }
    
    /**
     * Get total trace duration
     */
    public long getTotalDurationMs() {
        Span root = getRootSpan();
        return root != null ? root.getDurationMs() : 0;
    }
    
    /**
     * Convert to OTEL JSON format
     */
    public Map<String, Object> toOtelJson() {
        List<Map<String, Object>> spanList = new ArrayList<>();

        for (Span span : snapshotSpans()) {
            Map<String, Object> spanMap = new HashMap<>();
            spanMap.put("traceId", span.getTraceId() != null ? span.getTraceId() : "");
            spanMap.put("spanId", span.getSpanId() != null ? span.getSpanId() : "");
            spanMap.put("parentSpanId", span.getParentSpanId() != null ? span.getParentSpanId() : "");
            spanMap.put("name", span.getName() != null ? span.getName() : "");
            spanMap.put("kind", span.getKind() != null ? span.getKind().name() : "INTERNAL");
            spanMap.put("startTimeUnixNano", span.getStartTime() != null ? span.getStartTime().toEpochMilli() * 1_000_000 : 0);
            spanMap.put("endTimeUnixNano", span.getEndTime() != null ? span.getEndTime().toEpochMilli() * 1_000_000 : 0);
            spanMap.put("attributes", span.getAttributes() != null ? span.getAttributes() : Map.of());
            
            Map<String, Object> statusMap = new HashMap<>();
            statusMap.put("code", span.getStatus() != null && span.getStatus().getCode() != null ? 
                    span.getStatus().getCode().name() : "UNSET");
            statusMap.put("message", span.getStatus() != null && span.getStatus().getMessage() != null ? 
                    span.getStatus().getMessage() : "");
            spanMap.put("status", statusMap);
            
            spanList.add(spanMap);
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("traceId", traceId != null ? traceId : "");
        result.put("spans", spanList);
        return result;
    }

    private List<Span> snapshotSpans() {
        if (spans == null) {
            return List.of();
        }
        synchronized (spans) {
            return new ArrayList<>(spans);
        }
    }
}
