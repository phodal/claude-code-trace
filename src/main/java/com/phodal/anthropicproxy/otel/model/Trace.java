package com.phodal.anthropicproxy.otel.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
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
    private List<Span> spans = new ArrayList<>();
    
    /**
     * Add a span to this trace
     */
    public void addSpan(Span span) {
        if (spans == null) {
            spans = new ArrayList<>();
        }
        spans.add(span);
    }
    
    /**
     * Get root span (span without parent)
     */
    public Span getRootSpan() {
        return spans.stream()
                .filter(span -> span.getParentSpanId() == null)
                .findFirst()
                .orElse(null);
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
        
        for (Span span : spans) {
            Map<String, Object> spanMap = Map.of(
                    "traceId", span.getTraceId(),
                    "spanId", span.getSpanId(),
                    "parentSpanId", span.getParentSpanId() != null ? span.getParentSpanId() : "",
                    "name", span.getName(),
                    "kind", span.getKind().name(),
                    "startTimeUnixNano", span.getStartTime().toEpochMilli() * 1_000_000,
                    "endTimeUnixNano", span.getEndTime().toEpochMilli() * 1_000_000,
                    "attributes", span.getAttributes(),
                    "status", Map.of(
                            "code", span.getStatus() != null ? span.getStatus().getCode().name() : "UNSET",
                            "message", span.getStatus() != null && span.getStatus().getMessage() != null ? 
                                    span.getStatus().getMessage() : ""
                    )
            );
            spanList.add(spanMap);
        }
        
        return Map.of(
                "traceId", traceId,
                "spans", spanList
        );
    }
}
