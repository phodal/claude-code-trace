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
 * Zipkin trace exporter
 * Exports traces to Zipkin using the Zipkin v2 JSON format
 */
@Slf4j
@Component
public class ZipkinExporter implements TraceExporter {
    
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    
    @Value("${otel.exporter.zipkin.enabled:false}")
    private boolean enabled;
    
    @Value("${otel.exporter.zipkin.endpoint:http://localhost:9411}")
    private String endpoint;
    
    public ZipkinExporter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder().build();
    }
    
    @Override
    public void export(Trace trace) {
        if (!enabled) {
            return;
        }
        
        try {
            List<Map<String, Object>> zipkinFormat = convertToZipkinFormat(trace);
            
            webClient.post()
                    .uri(endpoint + "/api/v2/spans")
                    .header("Content-Type", "application/json")
                    .bodyValue(zipkinFormat)
                    .retrieve()
                    .bodyToMono(String.class)
                    .onErrorResume(e -> {
                        log.error("Failed to export trace to Zipkin: {}", e.getMessage());
                        return Mono.empty();
                    })
                    .subscribe(response -> 
                        log.debug("Exported trace {} to Zipkin", trace.getTraceId())
                    );
        } catch (Exception e) {
            log.error("Error exporting to Zipkin: {}", e.getMessage(), e);
        }
    }
    
    private List<Map<String, Object>> convertToZipkinFormat(Trace trace) {
        return trace.getSpans().stream()
                .map(this::convertSpanToZipkin)
                .collect(Collectors.toList());
    }
    
    private Map<String, Object> convertSpanToZipkin(Span span) {
        Map<String, Object> zipkinSpan = new HashMap<>();
        zipkinSpan.put("traceId", span.getTraceId());
        zipkinSpan.put("id", span.getSpanId());
        zipkinSpan.put("name", span.getName());
        // Zipkin v2 expects timestamp and duration in microseconds since epoch.
        // span.getStartTime().toEpochMilli() and span.getDurationMs() are in milliseconds,
        // so we multiply by 1000 to convert ms → µs as required by the Zipkin specification.
        zipkinSpan.put("timestamp", span.getStartTime() != null ? span.getStartTime().toEpochMilli() * 1000 : 0);
        zipkinSpan.put("duration", span.getDurationMs() * 1000);
        zipkinSpan.put("kind", mapSpanKindToZipkin(span.getKind()));
        
        if (span.getParentSpanId() != null) {
            zipkinSpan.put("parentId", span.getParentSpanId());
        }
        
        // Local endpoint
        zipkinSpan.put("localEndpoint", Map.of(
                "serviceName", span.getServiceName() != null ? span.getServiceName() : "anthropic-proxy"
        ));
        
        // Tags (attributes)
        Map<String, String> tags = new HashMap<>();
        span.getAttributes().forEach((key, value) -> 
            tags.put(key, value != null ? value.toString() : "")
        );
        zipkinSpan.put("tags", tags);
        
        return zipkinSpan;
    }
    
    private String mapSpanKindToZipkin(com.phodal.anthropicproxy.otel.model.SpanKind kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case SERVER -> "SERVER";
            case CLIENT -> "CLIENT";
            case PRODUCER -> "PRODUCER";
            case CONSUMER -> "CONSUMER";
            default -> null;
        };
    }
    
    @Override
    public String getName() {
        return "Zipkin";
    }
    
    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
