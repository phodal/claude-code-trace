package com.phodal.anthropicproxy.controller;

import com.phodal.agenttrace.model.TraceRecord;
import com.phodal.anthropicproxy.service.TraceService;
import com.phodal.anthropicproxy.otel.model.Span;
import com.phodal.anthropicproxy.otel.model.Trace;
import com.phodal.anthropicproxy.otel.service.OtelTraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final OtelTraceService otelTraceService;
    private final ObjectMapper objectMapper;

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
     * JSON API: linked OTEL chains ("turns") inferred from tool_use_id emitted/consumed.
     * This groups multiple /messages calls into a chain using:
     * - tool.use_ids.emitted (from response/tool_use)
     * - tool.use_ids.consumed (from request/tool_result.tool_use_id)
     */
    @GetMapping("/api/otel/chains")
    @ResponseBody
    public Map<String, Object> getOtelChains(@RequestParam(defaultValue = "200") int limit) {
        List<Trace> traces = otelTraceService.getRecentTraces(limit);
        // Most recent first from service; reverse to process in time order
        List<Trace> ordered = new ArrayList<>(traces);
        Collections.reverse(ordered);

        // Build per-trace info
        Map<String, OtelTraceInfo> infoByTraceId = new LinkedHashMap<>();
        Map<String, String> emittedToProducerTrace = new HashMap<>(); // tool_use_id -> traceId

        for (Trace t : ordered) {
            Span root = t.getRootSpan();
            if (root == null) continue;
            Map<String, Object> attrs = root.getAttributes() != null ? root.getAttributes() : Map.of();

            OtelTraceInfo info = new OtelTraceInfo();
            info.traceId = t.getTraceId();
            info.startTime = root.getStartTime() != null ? root.getStartTime().toString() : null;
            info.userId = str(attrs.get("user.id"));
            info.model = str(attrs.get("model"));
            info.route = str(attrs.get("http.route"));
            info.conversationId = str(attrs.get("conversation.id"));
            info.emitted = splitIds(str(attrs.get("tool.use_ids.emitted")));
            info.consumed = splitIds(str(attrs.get("tool.use_ids.consumed")));
            info.toolCallsSummaryJson = str(attrs.get("tool.calls.summary"));
            info.toolCallsCount = intVal(attrs.get("tool.calls.count"));

            infoByTraceId.put(info.traceId, info);

            for (String id : info.emitted) {
                // last-wins if duplicated; should be rare
                emittedToProducerTrace.put(id, info.traceId);
            }
        }

        // Build edges: producerTraceId -> consumerTraceId
        Map<String, Set<String>> edges = new HashMap<>();
        Map<String, Set<String>> reverseEdges = new HashMap<>();
        for (OtelTraceInfo info : infoByTraceId.values()) {
            for (String consumedId : info.consumed) {
                String producer = emittedToProducerTrace.get(consumedId);
                if (producer == null) continue;
                if (producer.equals(info.traceId)) continue;
                edges.computeIfAbsent(producer, k -> new LinkedHashSet<>()).add(info.traceId);
                reverseEdges.computeIfAbsent(info.traceId, k -> new LinkedHashSet<>()).add(producer);
            }
        }

        // Connected components (undirected) to form "chains"
        Set<String> visited = new HashSet<>();
        List<Map<String, Object>> chains = new ArrayList<>();
        for (String traceId : infoByTraceId.keySet()) {
            if (visited.contains(traceId)) continue;
            // BFS component
            Deque<String> dq = new ArrayDeque<>();
            dq.add(traceId);
            visited.add(traceId);
            List<String> component = new ArrayList<>();
            while (!dq.isEmpty()) {
                String cur = dq.removeFirst();
                component.add(cur);
                for (String nxt : edges.getOrDefault(cur, Set.of())) {
                    if (visited.add(nxt)) dq.addLast(nxt);
                }
                for (String prev : reverseEdges.getOrDefault(cur, Set.of())) {
                    if (visited.add(prev)) dq.addLast(prev);
                }
            }
            // Sort component by time (startTime string is ISO-ish)
            component.sort(Comparator.comparing(id -> Optional.ofNullable(infoByTraceId.get(id)).map(i -> i.startTime).orElse("")));

            Map<String, Object> chain = new LinkedHashMap<>();
            chain.put("chainId", component.get(0));
            chain.put("traceCount", component.size());
            chain.put("traces", component.stream().map(id -> otelTraceInfoToMap(infoByTraceId.get(id))).toList());
            chain.put("startTime", infoByTraceId.get(component.get(0)).startTime);
            chain.put("endTime", infoByTraceId.get(component.get(component.size() - 1)).startTime);

            // Aggregate basic fields (best-effort)
            OtelTraceInfo first = infoByTraceId.get(component.get(0));
            OtelTraceInfo last = infoByTraceId.get(component.get(component.size() - 1));
            chain.put("userId", first.userId != null ? first.userId : last.userId);
            chain.put("model", first.model != null ? first.model : last.model);
            chain.put("route", first.route != null ? first.route : last.route);

            // Aggregate emitted/consumed sets
            Set<String> allEmitted = new LinkedHashSet<>();
            Set<String> allConsumed = new LinkedHashSet<>();
            int toolCallsTotal = 0;
            for (String id : component) {
                OtelTraceInfo ti = infoByTraceId.get(id);
                allEmitted.addAll(ti.emitted);
                allConsumed.addAll(ti.consumed);
                toolCallsTotal += Math.max(0, ti.toolCallsCount);
            }
            chain.put("toolUseEmittedCount", allEmitted.size());
            chain.put("toolUseConsumedCount", allConsumed.size());
            chain.put("toolCallsTotal", toolCallsTotal);

            chains.add(chain);
        }

        // newest chains first
        Collections.reverse(chains);

        return Map.of(
                "totalTraces", infoByTraceId.size(),
                "totalChains", chains.size(),
                "chains", chains
        );
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

    private Map<String, Object> otelTraceInfoToMap(OtelTraceInfo info) {
        if (info == null) return Map.of();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("traceId", info.traceId);
        m.put("startTime", info.startTime);
        m.put("userId", info.userId);
        m.put("model", info.model);
        m.put("route", info.route);
        m.put("conversationId", info.conversationId);
        m.put("emitted", info.emitted);
        m.put("consumed", info.consumed);
        m.put("toolCallsCount", info.toolCallsCount);
        m.put("toolCalls", parseToolCallsSummary(info.toolCallsSummaryJson));
        return m;
    }

    private List<Map<String, Object>> parseToolCallsSummary(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return List.of(Map.of("error", "failed_to_parse_tool_calls_summary"));
        }
    }

    private static List<String> splitIds(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o);
        return s.isBlank() ? null : s;
    }

    private static int intVal(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return 0;
        }
    }

    private static class OtelTraceInfo {
        String traceId;
        String startTime;
        String userId;
        String model;
        String route;
        String conversationId;
        List<String> emitted = List.of();
        List<String> consumed = List.of();
        int toolCallsCount;
        String toolCallsSummaryJson;
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
