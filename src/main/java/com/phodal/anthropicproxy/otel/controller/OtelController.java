package com.phodal.anthropicproxy.otel.controller;

import com.phodal.anthropicproxy.otel.exporter.TraceExporter;
import com.phodal.anthropicproxy.otel.model.Trace;
import com.phodal.anthropicproxy.otel.service.ExporterService;
import com.phodal.anthropicproxy.otel.service.TraceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Controller for OTEL trace APIs
 */
@Slf4j
@RestController
@RequestMapping("/otel")
@RequiredArgsConstructor
public class OtelController {
    
    private final TraceService traceService;
    private final ExporterService exporterService;
    
    /**
     * Get all recent traces
     */
    @GetMapping("/traces")
    public ResponseEntity<Map<String, Object>> getTraces(
            @RequestParam(defaultValue = "50") int limit) {
        
        List<Trace> traces = traceService.getRecentTraces(limit);
        
        List<Map<String, Object>> traceSummaries = traces.stream()
                .map(this::traceToSummary)
                .collect(Collectors.toList());
        
        Map<String, Object> response = new HashMap<>();
        response.put("traces", traceSummaries);
        response.put("total", traces.size());
        
        return ResponseEntity.ok(response);
    }
    
    /**
     * Get specific trace by ID
     */
    @GetMapping("/traces/{traceId}")
    public ResponseEntity<Map<String, Object>> getTrace(@PathVariable String traceId) {
        Trace trace = traceService.getTrace(traceId);
        
        if (trace == null) {
            return ResponseEntity.notFound().build();
        }
        
        return ResponseEntity.ok(trace.toOtelJson());
    }
    
    /**
     * Export a specific trace to all enabled exporters
     */
    @PostMapping("/traces/{traceId}/export")
    public ResponseEntity<Map<String, Object>> exportTrace(@PathVariable String traceId) {
        Trace trace = traceService.getTrace(traceId);
        
        if (trace == null) {
            return ResponseEntity.notFound().build();
        }
        
        exporterService.exportTrace(trace);
        
        return ResponseEntity.ok(Map.of(
                "message", "Trace exported successfully",
                "traceId", traceId
        ));
    }
    
    /**
     * Get exporter status
     */
    @GetMapping("/exporters")
    public ResponseEntity<Map<String, Object>> getExporters() {
        List<Map<String, Object>> exporterList = exporterService.getExporters().stream()
                .map(exporter -> Map.of(
                        "name", (Object) exporter.getName(),
                        "enabled", exporter.isEnabled()
                ))
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(Map.of(
                "exporters", exporterList
        ));
    }
    
    /**
     * Clear all traces (for testing)
     */
    @DeleteMapping("/traces")
    public ResponseEntity<Map<String, String>> clearTraces() {
        traceService.clearAllTraces();
        return ResponseEntity.ok(Map.of("message", "All traces cleared"));
    }
    
    /**
     * Get OTEL metrics summary
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        int activeTraces = traceService.getActiveTraces().size();
        int completedTraces = traceService.getCompletedTraces().size();
        
        long totalSpans = traceService.getCompletedTraces().stream()
                .mapToLong(trace -> trace.getSpans().size())
                .sum();
        
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("activeTraces", activeTraces);
        metrics.put("completedTraces", completedTraces);
        metrics.put("totalSpans", totalSpans);
        
        return ResponseEntity.ok(metrics);
    }
    
    private Map<String, Object> traceToSummary(Trace trace) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("traceId", trace.getTraceId());
        summary.put("spanCount", trace.getSpans().size());
        summary.put("durationMs", trace.getTotalDurationMs());
        
        if (trace.getRootSpan() != null) {
            summary.put("rootSpanName", trace.getRootSpan().getName());
            summary.put("startTime", trace.getRootSpan().getStartTime().toString());
        }
        
        return summary;
    }
}
