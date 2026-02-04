package com.phodal.anthropicproxy.controller;

import com.phodal.agenttrace.model.TraceRecord;
import com.phodal.anthropicproxy.service.TraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Controller for Agent Trace dashboard
 * Displays AI code contribution traces following Agent Trace specification
 */
@Controller
@RequestMapping("/metrics")
@RequiredArgsConstructor
public class MetricsDashboardController {

    private final TraceService traceService;

    /**
     * Main dashboard page
     */
    @GetMapping("")
    public String dashboard(Model model) {
        TraceService.TraceSummary summary = traceService.getSummary();
        
        model.addAttribute("totalRequests", summary.getTotalRequests());
        model.addAttribute("totalToolCalls", summary.getTotalToolCalls());
        model.addAttribute("totalEditToolCalls", summary.getTotalEditToolCalls());
        model.addAttribute("totalLinesModified", summary.getTotalLinesModified());
        model.addAttribute("totalInputTokens", summary.getTotalInputTokens());
        model.addAttribute("totalOutputTokens", summary.getTotalOutputTokens());
        model.addAttribute("activeUsers", summary.getActiveUsers());
        model.addAttribute("totalTraces", summary.getTotalTraces());
        model.addAttribute("toolCallsByName", summary.getToolCallsByName() != null ? summary.getToolCallsByName() : Map.of());
        
        // Get user metrics
        List<Map<String, Object>> userMetricsList = traceService.getUserMetrics().values().stream()
                .map(this::userMetricsToMap)
                .collect(Collectors.toList());
        model.addAttribute("userMetrics", userMetricsList);
        
        return "dashboard";
    }

    /**
     * JSON API for summary data
     */
    @GetMapping("/api/summary")
    @ResponseBody
    public TraceService.TraceSummary getMetricsSummary() {
        return traceService.getSummary();
    }

    /**
     * JSON API for user metrics
     */
    @GetMapping("/api/users")
    @ResponseBody
    public List<Map<String, Object>> getUserMetrics() {
        return traceService.getUserMetrics().values().stream()
                .map(this::userMetricsToMap)
                .collect(Collectors.toList());
    }

    /**
     * JSON API for recent traces
     */
    @GetMapping("/api/traces")
    @ResponseBody
    public List<Map<String, Object>> getRecentTraces(@RequestParam(defaultValue = "50") int limit) {
        return traceService.getRecentTraces(limit).stream()
                .map(this::traceRecordToMap)
                .collect(Collectors.toList());
    }

    /**
     * JSON API for a specific trace
     */
    @GetMapping("/api/traces/{traceId}")
    @ResponseBody
    public Map<String, Object> getTraceDetail(@PathVariable String traceId) {
        try {
            UUID id = UUID.fromString(traceId);
            return traceService.findTraceById(id)
                    .map(this::traceRecordToDetailMap)
                    .orElse(Map.of("error", "Trace not found"));
        } catch (IllegalArgumentException e) {
            return Map.of("error", "Invalid trace ID");
        }
    }

    /**
     * JSON API for traces by file
     */
    @GetMapping("/api/traces/by-file")
    @ResponseBody
    public List<Map<String, Object>> getTracesByFile(@RequestParam String filePath) {
        return traceService.getTracesByFile(filePath).stream()
                .map(this::traceRecordToMap)
                .collect(Collectors.toList());
    }

    /**
     * JSON API for traces by time range
     */
    @GetMapping("/api/traces/by-time")
    @ResponseBody
    public List<Map<String, Object>> getTracesByTimeRange(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        Instant fromInstant = from != null ? Instant.parse(from) : Instant.now().minus(24, ChronoUnit.HOURS);
        Instant toInstant = to != null ? Instant.parse(to) : Instant.now();
        
        return traceService.getTracesByTimeRange(fromInstant, toInstant).stream()
                .map(this::traceRecordToMap)
                .collect(Collectors.toList());
    }

    /**
     * Backwards compatibility - returns traces as "turns"
     */
    @GetMapping("/api/turns")
    @ResponseBody
    public List<Map<String, Object>> getRecentTurns() {
        return traceService.getRecentTraces(50).stream()
                .map(this::traceRecordToTurnMap)
                .collect(Collectors.toList());
    }

    /**
     * Backwards compatibility - returns empty sessions
     */
    @GetMapping("/api/sessions")
    @ResponseBody
    public List<Map<String, Object>> getRecentSessions() {
        // Sessions are now implicit in traces
        return Collections.emptyList();
    }

    // Helper methods

    private Map<String, Object> userMetricsToMap(TraceService.UserTraceMetrics metrics) {
        Map<String, Object> map = new HashMap<>();
        map.put("userId", metrics.getUserId());
        map.put("totalRequests", metrics.getTotalRequests().get());
        map.put("totalToolCalls", metrics.getTotalToolCalls().get());
        map.put("editToolCalls", metrics.getEditToolCalls().get());
        map.put("linesModified", metrics.getLinesModified().get());
        map.put("inputTokens", metrics.getInputTokens().get());
        map.put("outputTokens", metrics.getOutputTokens().get());
        map.put("firstSeen", metrics.getFirstSeen() != null ? metrics.getFirstSeen().toString() : null);
        map.put("lastSeen", metrics.getLastSeen() != null ? metrics.getLastSeen().toString() : null);
        return map;
    }

    private Map<String, Object> traceRecordToMap(TraceRecord trace) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", trace.id().toString());
        map.put("timestamp", trace.timestamp().toString());
        map.put("version", trace.version());
        map.put("fileCount", trace.fileCount());
        map.put("totalLineCount", trace.totalLineCount());
        map.put("modelIds", trace.getModelIds());
        
        if (trace.vcs() != null) {
            map.put("vcsType", trace.vcs().type().getValue());
            map.put("revision", trace.vcs().revision());
        }
        
        if (trace.tool() != null) {
            map.put("toolName", trace.tool().name());
            map.put("toolVersion", trace.tool().version());
        }
        
        if (trace.metadata() != null) {
            map.put("userId", trace.metadata().get("user_id"));
            map.put("conversationId", trace.metadata().get("conversation_id"));
            map.put("latencyMs", trace.metadata().get("latency_ms"));
            map.put("linesAdded", trace.metadata().get("lines_added"));
            map.put("linesRemoved", trace.metadata().get("lines_removed"));
        }
        
        // File paths
        map.put("files", trace.files().stream()
                .map(f -> f.path())
                .collect(Collectors.toList()));
        
        return map;
    }

    private Map<String, Object> traceRecordToDetailMap(TraceRecord trace) {
        Map<String, Object> map = traceRecordToMap(trace);
        
        // Add detailed file information
        List<Map<String, Object>> fileDetails = trace.files().stream()
                .map(file -> {
                    Map<String, Object> fileMap = new HashMap<>();
                    fileMap.put("path", file.path());
                    fileMap.put("lineCount", file.totalLineCount());
                    fileMap.put("conversations", file.conversations().stream()
                            .map(conv -> {
                                Map<String, Object> convMap = new HashMap<>();
                                convMap.put("url", conv.url());
                                if (conv.contributor() != null) {
                                    convMap.put("contributorType", conv.contributor().type().getValue());
                                    convMap.put("modelId", conv.contributor().modelId());
                                }
                                convMap.put("ranges", conv.ranges().stream()
                                        .map(r -> Map.of(
                                                "startLine", r.startLine(),
                                                "endLine", r.endLine(),
                                                "lineCount", r.lineCount()
                                        ))
                                        .collect(Collectors.toList()));
                                return convMap;
                            })
                            .collect(Collectors.toList()));
                    return fileMap;
                })
                .collect(Collectors.toList());
        
        map.put("fileDetails", fileDetails);
        map.put("metadata", trace.metadata());
        
        return map;
    }

    /**
     * Convert trace to turn-like format for backwards compatibility
     */
    private Map<String, Object> traceRecordToTurnMap(TraceRecord trace) {
        Map<String, Object> map = new HashMap<>();
        String conversationId = trace.metadata() != null ? 
                (String) trace.metadata().get("conversation_id") : trace.id().toString();
        
        map.put("turnId", conversationId);
        map.put("timestamp", trace.timestamp().toString());
        map.put("userId", trace.metadata() != null ? trace.metadata().get("user_id") : "unknown");
        map.put("model", trace.getModelIds().isEmpty() ? "unknown" : trace.getModelIds().iterator().next());
        map.put("toolCallCount", trace.metadata() != null ? trace.metadata().get("tool_calls") : 0);
        map.put("editToolCallCount", trace.fileCount());
        map.put("linesAdded", trace.metadata() != null ? trace.metadata().get("lines_added") : 0);
        map.put("linesRemoved", trace.metadata() != null ? trace.metadata().get("lines_removed") : 0);
        map.put("linesModified", trace.totalLineCount());
        map.put("promptTokens", trace.metadata() != null ? trace.metadata().get("prompt_tokens") : 0);
        map.put("completionTokens", trace.metadata() != null ? trace.metadata().get("completion_tokens") : 0);
        map.put("latencyMs", trace.metadata() != null ? trace.metadata().get("latency_ms") : 0);
        map.put("lastUserMessagePreview", "Trace: " + trace.files().stream()
                .map(f -> f.path())
                .limit(3)
                .collect(Collectors.joining(", ")));
        
        // Tool calls as file edits
        List<Map<String, Object>> toolCalls = trace.files().stream()
                .map(file -> {
                    Map<String, Object> tc = new HashMap<>();
                    tc.put("name", "FileEdit");
                    tc.put("filePath", file.path());
                    tc.put("linesAdded", file.totalLineCount());
                    tc.put("linesRemoved", 0);
                    tc.put("status", "ok");
                    tc.put("argsPreview", file.conversations().stream()
                            .flatMap(c -> c.ranges().stream())
                            .map(r -> "L" + r.startLine() + "-" + r.endLine())
                            .collect(Collectors.joining(", ")));
                    return tc;
                })
                .collect(Collectors.toList());
        
        map.put("toolCalls", toolCalls);
        
        return map;
    }
}
