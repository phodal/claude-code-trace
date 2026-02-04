package com.phodal.anthropicproxy.otel.controller;

import com.phodal.anthropicproxy.otel.model.Trace;
import com.phodal.anthropicproxy.otel.model.Span;
import com.phodal.anthropicproxy.otel.service.ExporterService;
import com.phodal.anthropicproxy.otel.service.OtelTraceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Controller for OTEL trace APIs.
 * Provides endpoints for viewing and exporting OTEL traces.
 */
@Slf4j
@RestController
@RequestMapping("/otel")
@RequiredArgsConstructor
public class OtelController {
    
    private final OtelTraceService traceService;
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
        List<Trace> completed = traceService.getCompletedTraces();
        int completedTraceCount = completed.size();
        
        // Use thread-safe getSpanCount() instead of getSpans().size()
        long totalSpans = completed.stream()
                .mapToLong(Trace::getSpanCount)
                .sum();
        
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("activeTraces", activeTraces);
        metrics.put("completedTraces", completedTraceCount);
        metrics.put("totalSpans", totalSpans);
        
        return ResponseEntity.ok(metrics);
    }
    
    private Map<String, Object> traceToSummary(Trace trace) {
        Map<String, Object> summary = new HashMap<>();
        summary.put("traceId", trace.getTraceId());
        summary.put("spanCount", trace.getSpanCount());
        summary.put("durationMs", trace.getTotalDurationMs());

        // Derive status (ERROR if any span is ERROR, otherwise OK/UNSET)
        String status = "UNSET";
        boolean anyError = false;
        boolean anyOk = false;
        for (Span span : trace.snapshotSpans()) {
            if (span.getStatus() != null && span.getStatus().getCode() != null) {
                switch (span.getStatus().getCode()) {
                    case ERROR -> anyError = true;
                    case OK -> anyOk = true;
                    default -> {}
                }
            }
        }
        if (anyError) {
            status = "ERROR";
        } else if (anyOk) {
            status = "OK";
        }
        summary.put("status", status);

        if (trace.getRootSpan() != null) {
            Span root = trace.getRootSpan();
            summary.put("rootSpanName", root.getName());
            summary.put("startTime", root.getStartTime() != null ? root.getStartTime().toString() : null);
            summary.put("rootStatus", root.getStatus() != null && root.getStatus().getCode() != null ? root.getStatus().getCode().name() : "UNSET");

            // Common attributes (best-effort)
            Map<String, Object> attrs = root.getAttributes() != null ? root.getAttributes() : Map.of();
            Object route = attrs.get("http.route");
            Object model = attrs.get("model");
            Object userId = attrs.get("user.id");
            Object conversationId = attrs.get("conversation.id");
            Object stream = attrs.get("stream");

            if (route != null) summary.put("route", route);
            if (model != null) summary.put("model", model);
            if (userId != null) summary.put("userId", userId);
            if (conversationId != null) summary.put("conversationId", conversationId);
            if (stream != null) summary.put("stream", stream);
        }
        
        return summary;
    }
}
