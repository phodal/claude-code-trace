package com.phodal.anthropicproxy.otel.exporter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phodal.anthropicproxy.otel.model.Span;
import com.phodal.anthropicproxy.otel.model.Trace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Console/Logging trace exporter
 * Exports traces to application logs for debugging
 */
@Slf4j
@Component
public class ConsoleExporter implements TraceExporter {
    
    private final ObjectMapper objectMapper;
    
    @Value("${otel.exporter.console.enabled:true}")
    private boolean enabled;
    
    public ConsoleExporter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    
    @Override
    public void export(Trace trace) {
        if (!enabled) {
            return;
        }
        
        try {
            log.info("=== OTEL Trace: {} ===", trace.getTraceId());
            log.info("Total spans: {}", trace.getSpans().size());
            log.info("Duration: {}ms", trace.getTotalDurationMs());
            
            for (Span span : trace.getSpans()) {
                log.info("  Span: {} | {} | {}ms | Status: {}", 
                        span.getName(),
                        span.getKind(),
                        span.getDurationMs(),
                        span.getStatus() != null ? span.getStatus().getCode() : "UNSET"
                );
                
                if (!span.getAttributes().isEmpty()) {
                    log.debug("    Attributes: {}", 
                            objectMapper.writeValueAsString(span.getAttributes()));
                }
            }
        } catch (Exception e) {
            log.error("Error logging trace: {}", e.getMessage(), e);
        }
    }
    
    @Override
    public String getName() {
        return "Console";
    }
    
    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
