package com.phodal.anthropicproxy.otel.exporter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phodal.anthropicproxy.otel.model.Span;
import com.phodal.anthropicproxy.otel.model.Trace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Jaeger trace exporter
 * Exports traces to Jaeger using the OTLP HTTP protocol
 */
@Slf4j
@Component
public class JaegerExporter implements TraceExporter {
    
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    
    @Value("${otel.exporter.jaeger.enabled:false}")
    private boolean enabled;
    
    @Value("${otel.exporter.jaeger.endpoint:http://localhost:14250}")
    private String endpoint;
    
    public JaegerExporter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder().build();
    }
    
    @Override
    public void export(Trace trace) {
        if (!enabled) {
            return;
        }
        
        try {
            Map<String, Object> jaegerFormat = convertToJaegerFormat(trace);
            
            webClient.post()
                    .uri(endpoint + "/api/traces")
                    .header("Content-Type", "application/json")
                    .bodyValue(jaegerFormat)
                    .retrieve()
                    .bodyToMono(String.class)
                    .onErrorResume(e -> {
                        log.error("Failed to export trace to Jaeger: {}", e.getMessage());
                        return Mono.empty();
                    })
                    .subscribe(response -> 
                        log.debug("Exported trace {} to Jaeger", trace.getTraceId())
                    );
        } catch (Exception e) {
            log.error("Error exporting to Jaeger: {}", e.getMessage(), e);
        }
    }
    
    private Map<String, Object> convertToJaegerFormat(Trace trace) {
        List<Map<String, Object>> spans = trace.getSpans().stream()
                .map(this::convertSpanToJaeger)
                .collect(Collectors.toList());
        
        Map<String, Object> jaegerTrace = new HashMap<>();
        jaegerTrace.put("traceID", trace.getTraceId());
        jaegerTrace.put("spans", spans);
        jaegerTrace.put("processes", Map.of(
                "p1", Map.of(
                        "serviceName", "anthropic-proxy",
                        "tags", List.of()
                )
        ));
        
        return Map.of("data", List.of(jaegerTrace));
    }
    
    private Map<String, Object> convertSpanToJaeger(Span span) {
        Map<String, Object> jaegerSpan = new HashMap<>();
        jaegerSpan.put("traceID", span.getTraceId());
        jaegerSpan.put("spanID", span.getSpanId());
        jaegerSpan.put("operationName", span.getName());
        jaegerSpan.put("startTime", span.getStartTime().toEpochMilli() * 1000);
        jaegerSpan.put("duration", span.getDurationMs() * 1000);
        jaegerSpan.put("processID", "p1");
        
        if (span.getParentSpanId() != null) {
            jaegerSpan.put("references", List.of(
                    Map.of(
                            "refType", "CHILD_OF",
                            "traceID", span.getTraceId(),
                            "spanID", span.getParentSpanId()
                    )
            ));
        }
        
        // Convert attributes to tags
        List<Map<String, Object>> tags = span.getAttributes().entrySet().stream()
                .map(entry -> {
                    Map<String, Object> tag = new HashMap<>();
                    tag.put("key", entry.getKey());
                    tag.put("type", getJaegerType(entry.getValue()));
                    tag.put("value", entry.getValue());
                    return tag;
                })
                .collect(Collectors.toList());
        
        jaegerSpan.put("tags", tags);
        jaegerSpan.put("logs", List.of());
        
        return jaegerSpan;
    }
    
    private String getJaegerType(Object value) {
        if (value instanceof String) return "string";
        if (value instanceof Integer || value instanceof Long) return "int64";
        if (value instanceof Double || value instanceof Float) return "float64";
        if (value instanceof Boolean) return "bool";
        return "string";
    }
    
    @Override
    public String getName() {
        return "Jaeger";
    }
    
    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
