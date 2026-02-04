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
            info.toolCallsDetailsJson = str(attrs.get("tool.calls.details"));
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
     * JSON API for recent turns (all conversations, even without file edits)
     * This is different from /api/traces which only returns conversations with file edits
     */
    @GetMapping("/api/turns")
    @ResponseBody
    public List<Map<String, Object>> getRecentTurns() {
        // Get turn summaries from TraceService (includes all conversations)
        List<Map<String, Object>> turns = new ArrayList<>(traceService.getRecentTurns());
        
        // Convert to UI-friendly format with proper tool call counts
        return turns.stream()
                .map(this::turnSummaryToTurnMap)
                .sorted((a, b) -> {
                    String timeA = (String) a.get("timestamp");
                    String timeB = (String) b.get("timestamp");
                    return timeB.compareTo(timeA); // Most recent first
                })
                .collect(Collectors.toList());
    }
    
    private Map<String, Object> turnSummaryToTurnMap(Map<String, Object> summary) {
        Map<String, Object> map = new HashMap<>(summary);
        
        // Calculate tool call counts
        long totalToolCalls = toLong(summary.get("toolCalls"));
        long editToolCalls = toLong(summary.get("editToolCalls"));
        long nonEditToolCalls = Math.max(0, totalToolCalls - editToolCalls);
        
        map.put("toolCallCount", nonEditToolCalls);
        map.put("toolCallCountTotal", totalToolCalls);
        map.put("editToolCallCount", editToolCalls);
        
        // Get tool calls details
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolCallsDetails = (List<Map<String, Object>>) summary.get("toolCallsDetails");
        map.put("toolCalls", toolCallsDetails != null ? toolCallsDetails : List.of());
        
        return map;
    }
    
    private long toLong(Object value) {
        if (value == null) return 0;
        if (value instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Backwards compatibility - returns traces as "turns"
     * DEPRECATED: Use /api/turns instead which includes all conversations
     */
    @Deprecated
    @GetMapping("/api/turns-old")
    @ResponseBody
    public List<Map<String, Object>> getRecentTurnsOld() {
        return traceService.getRecentTraces(50).stream()
                .map(this::traceRecordToTurnMap)
                .collect(Collectors.toList());
    }

    /**
     * Backwards compatibility - returns a specific "turn" detail.
     *
     * <p>UI uses this to fetch a full tooltip message by turnId (conversation_id).</p>
     */
    @GetMapping("/api/turns/{turnId}")
    @ResponseBody
    public Map<String, Object> getTurnDetail(@PathVariable String turnId) {
        TraceRecord trace = null;

        // 1) If client passes a trace UUID, resolve directly.
        try {
            UUID id = UUID.fromString(turnId);
            trace = traceService.findTraceById(id).orElse(null);
        } catch (IllegalArgumentException ignored) {
            // ignore
        }

        // 2) Otherwise treat as conversation_id (e.g. conv-key:sk-...)
        if (trace == null) {
            trace = traceService.getRecentTraces(200).stream()
                    .filter(t -> t.metadata() != null)
                    .filter(t -> Objects.equals(String.valueOf(t.metadata().get("conversation_id")), turnId))
                    .max(Comparator.comparing(TraceRecord::timestamp))
                    .orElse(null);
        }

        if (trace == null) {
            return Map.of(
                    "turnId", turnId,
                    "error", "Turn not found"
            );
        }

        String lastUserMessage = "";
        if (trace.metadata() != null && trace.metadata().get("last_user_message") != null) {
            lastUserMessage = String.valueOf(trace.metadata().get("last_user_message"));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("turnId", turnId);
        resp.put("traceId", trace.id().toString());
        resp.put("timestamp", trace.timestamp().toString());
        resp.put("userId", trace.metadata() != null ? trace.metadata().get("user_id") : null);
        resp.put("conversationId", trace.metadata() != null ? trace.metadata().get("conversation_id") : null);
        resp.put("lastUserMessage", lastUserMessage);
        return resp;
    }

    /**
     * Get session-like aggregated data by grouping turns by user.
     * A session represents all activity for a given user.
     * This provides a better session view where one session can contain multiple messages.
     */
    @GetMapping("/api/sessions")
    @ResponseBody
    public List<Map<String, Object>> getRecentSessions() {
        List<Map<String, Object>> turns = traceService.getRecentTurns();
        
        // Group turns by userId to create "sessions"
        Map<String, List<Map<String, Object>>> sessionMap = new LinkedHashMap<>();
        for (Map<String, Object> turn : turns) {
            String userId = (String) turn.get("userId");
            if (userId == null) userId = "unknown";
            sessionMap.computeIfAbsent(userId, k -> new ArrayList<>()).add(turn);
        }
        
        // Build session summaries
        List<Map<String, Object>> sessions = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> entry : sessionMap.entrySet()) {
            String userId = entry.getKey();
            List<Map<String, Object>> userTurns = entry.getValue();
            
            if (userTurns.isEmpty()) continue;
            
            // Sort by timestamp
            userTurns.sort((a, b) -> {
                String timeA = (String) a.get("timestamp");
                String timeB = (String) b.get("timestamp");
                if (timeA == null || timeB == null) return 0;
                return timeA.compareTo(timeB);
            });
            
            Map<String, Object> first = userTurns.get(0);
            Map<String, Object> last = userTurns.get(userTurns.size() - 1);
            
            // Aggregate metrics
            long totalToolCalls = 0;
            long totalLinesModified = 0;
            int errorCount = 0;
            
            for (Map<String, Object> turn : userTurns) {
                Object tc = turn.get("toolCalls");
                if (tc instanceof Number) totalToolCalls += ((Number) tc).longValue();
                Object lm = turn.get("linesModified");
                if (lm instanceof Number) totalLinesModified += ((Number) lm).longValue();
            }
            
            Map<String, Object> session = new HashMap<>();
            session.put("sessionId", userId);  // Use userId as sessionId
            session.put("userId", userId);
            session.put("startTime", first.get("timestamp"));
            session.put("lastActivityTime", last.get("timestamp"));
            session.put("turnCount", userTurns.size());
            session.put("totalToolCalls", totalToolCalls);
            session.put("avgToolCallsPerTurn", userTurns.size() > 0 ? (double) totalToolCalls / userTurns.size() : 0.0);
            session.put("totalLinesModified", totalLinesModified);
            session.put("errorCount", errorCount);
            
            sessions.add(session);
        }
        
        // Sort by last activity (most recent first)
        sessions.sort((a, b) -> {
            String timeA = (String) a.get("lastActivityTime");
            String timeB = (String) b.get("lastActivityTime");
            if (timeA == null || timeB == null) return 0;
            return timeB.compareTo(timeA);
        });
        
        return sessions;
    }

    /**
     * Get all turns for a specific session (user).
     * This allows viewing all messages in a session.
     */
    @GetMapping("/api/sessions/{sessionId}/turns")
    @ResponseBody
    public List<Map<String, Object>> getSessionTurns(@PathVariable String sessionId) {
        List<Map<String, Object>> allTurns = traceService.getRecentTurns();
        
        // Filter turns by userId (sessionId is userId)
        List<Map<String, Object>> sessionTurns = allTurns.stream()
                .filter(turn -> {
                    String userId = (String) turn.get("userId");
                    return sessionId.equals(userId);
                })
                .sorted((a, b) -> {
                    String timeA = (String) a.get("timestamp");
                    String timeB = (String) b.get("timestamp");
                    if (timeA == null || timeB == null) return 0;
                    return timeA.compareTo(timeB);  // Chronological order
                })
                .collect(Collectors.toList());
        
        return sessionTurns;
    }

    /**
     * Get tool performance statistics for the Tool Performance Matrix view.
     * Returns aggregated metrics per tool including call count, success rate, and lines changed.
     */
    @GetMapping("/api/tools/performance")
    @ResponseBody
    public List<Map<String, Object>> getToolPerformance() {
        List<Map<String, Object>> allTurns = traceService.getRecentTurns();
        
        // Aggregate tool call statistics
        Map<String, ToolStats> toolStatsMap = new LinkedHashMap<>();
        
        for (Map<String, Object> turn : allTurns) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) turn.get("toolCallsDetails");
            if (toolCalls == null) continue;
            
            for (Map<String, Object> tc : toolCalls) {
                String toolName = (String) tc.get("name");
                if (toolName == null) continue;
                
                ToolStats stats = toolStatsMap.computeIfAbsent(toolName, ToolStats::new);
                stats.incrementCalls();
                
                // Track success (assume ok if not explicitly error)
                String status = (String) tc.get("status");
                if (status == null || "ok".equals(status)) {
                    stats.incrementSuccess();
                }
                
                // Track lines changed (for edit tools)
                Object linesAdded = tc.get("linesAdded");
                Object linesRemoved = tc.get("linesRemoved");
                if (linesAdded instanceof Number) {
                    stats.addLinesAdded(((Number) linesAdded).intValue());
                }
                if (linesRemoved instanceof Number) {
                    stats.addLinesRemoved(((Number) linesRemoved).intValue());
                }
            }
        }
        
        // Convert to list and sort by call count (descending)
        return toolStatsMap.values().stream()
                .map(stats -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("toolName", stats.toolName);
                    map.put("calls", stats.calls);
                    map.put("successRate", stats.calls > 0 ? (stats.successCount * 100.0 / stats.calls) : 100.0);
                    map.put("linesAdded", stats.linesAdded);
                    map.put("linesRemoved", stats.linesRemoved);
                    map.put("isEditTool", stats.linesAdded > 0 || stats.linesRemoved > 0);
                    map.put("isSkill", "Skill".equalsIgnoreCase(stats.toolName));
                    return map;
                })
                .sorted((a, b) -> Long.compare((Long) b.get("calls"), (Long) a.get("calls")))
                .collect(Collectors.toList());
    }

    /**
     * Get skills statistics - aggregated by individual skill names parsed from tool arguments.
     * Skills are tool calls with name "Skill" that contain {"skill": "skill-name"} in their arguments.
     */
    @GetMapping("/api/skills/statistics")
    @ResponseBody
    public List<Map<String, Object>> getSkillsStatistics() {
        List<Map<String, Object>> allTurns = traceService.getRecentTurns();
        
        // Aggregate skill statistics by skill name
        Map<String, SkillStats> skillStatsMap = new LinkedHashMap<>();
        
        for (Map<String, Object> turn : allTurns) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) turn.get("toolCallsDetails");
            if (toolCalls == null) continue;
            
            for (Map<String, Object> tc : toolCalls) {
                String toolName = (String) tc.get("name");
                if (!"Skill".equalsIgnoreCase(toolName)) continue;
                
                // Extract skill name from args
                String skillName = extractSkillName(tc);
                if (skillName == null) skillName = "unknown";
                
                SkillStats stats = skillStatsMap.computeIfAbsent(skillName, SkillStats::new);
                stats.incrementCalls();
                
                // Track success
                String status = (String) tc.get("status");
                if (status == null || "ok".equals(status)) {
                    stats.incrementSuccess();
                }
                
                // Track timestamp for last used
                String timestamp = (String) tc.get("timestamp");
                if (timestamp != null) {
                    stats.updateLastUsed(timestamp);
                }
            }
        }
        
        // Convert to list and sort by call count (descending)
        return skillStatsMap.values().stream()
                .map(stats -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("skillName", stats.skillName);
                    map.put("calls", stats.calls);
                    map.put("successRate", stats.calls > 0 ? (stats.successCount * 100.0 / stats.calls) : 100.0);
                    map.put("lastUsed", stats.lastUsed);
                    return map;
                })
                .sorted((a, b) -> Long.compare((Long) b.get("calls"), (Long) a.get("calls")))
                .collect(Collectors.toList());
    }
    
    /**
     * Extract skill name from tool call arguments.
     * Expects JSON like: {"skill": "generate-crud"}
     */
    private String extractSkillName(Map<String, Object> toolCall) {
        String argsPreview = (String) toolCall.get("argsPreview");
        if (argsPreview == null || argsPreview.isEmpty()) {
            return null;
        }
        
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = objectMapper.readValue(argsPreview, Map.class);
            Object skill = args.get("skill");
            if (skill != null) {
                return String.valueOf(skill);
            }
        } catch (Exception e) {
            // Try simple parsing if JSON parsing fails
            if (argsPreview.contains("\"skill\"")) {
                int start = argsPreview.indexOf("\"skill\"");
                int colon = argsPreview.indexOf(":", start);
                if (colon > 0) {
                    int quote1 = argsPreview.indexOf("\"", colon);
                    if (quote1 > 0) {
                        int quote2 = argsPreview.indexOf("\"", quote1 + 1);
                        if (quote2 > 0) {
                            return argsPreview.substring(quote1 + 1, quote2);
                        }
                    }
                }
            }
        }
        
        return null;
    }
    
    /**
     * Helper class for aggregating tool statistics
     */
    private static class ToolStats {
        String toolName;
        long calls = 0;
        long successCount = 0;
        long linesAdded = 0;
        long linesRemoved = 0;
        
        ToolStats(String toolName) {
            this.toolName = toolName;
        }
        
        void incrementCalls() { calls++; }
        void incrementSuccess() { successCount++; }
        void addLinesAdded(long n) { linesAdded += n; }
        void addLinesRemoved(long n) { linesRemoved += n; }
    }
    
    /**
     * Helper class for aggregating skill statistics
     */
    private static class SkillStats {
        String skillName;
        long calls = 0;
        long successCount = 0;
        String lastUsed = null;
        
        SkillStats(String skillName) {
            this.skillName = skillName;
        }
        
        void incrementCalls() { calls++; }
        void incrementSuccess() { successCount++; }
        void updateLastUsed(String timestamp) {
            if (lastUsed == null || timestamp.compareTo(lastUsed) > 0) {
                lastUsed = timestamp;
            }
        }
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
        // Prefer tool call details (with argsPreview) if present; fallback to summary
        String toolCallsJson = info.toolCallsDetailsJson != null && !info.toolCallsDetailsJson.isBlank()
                ? info.toolCallsDetailsJson
                : info.toolCallsSummaryJson;
        m.put("toolCalls", parseToolCallsSummary(toolCallsJson));
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
        String toolCallsDetailsJson;
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
        // Tool calls:
        // - tool_calls: total tool calls in the conversation (including edits)
        // - edit_tool_calls: tool calls that touched a file (edit tools)
        // UI should distinguish non-edit vs edit to avoid double-counting in the table.
        long totalToolCalls = 0;
        if (trace.metadata() != null && trace.metadata().get("tool_calls") instanceof Number n) {
            totalToolCalls = n.longValue();
        } else if (trace.metadata() != null && trace.metadata().get("tool_calls") != null) {
            try {
                totalToolCalls = Long.parseLong(String.valueOf(trace.metadata().get("tool_calls")));
            } catch (Exception ignored) { }
        }

        // Prefer true edit tool call count (tool calls that touched a file), fallback to file count for older traces.
        long editToolCalls = -1;
        if (trace.metadata() != null && trace.metadata().get("edit_tool_calls") instanceof Number n) {
            editToolCalls = n.longValue();
        } else if (trace.metadata() != null && trace.metadata().get("edit_tool_calls") != null) {
            try {
                editToolCalls = Long.parseLong(String.valueOf(trace.metadata().get("edit_tool_calls")));
            } catch (Exception ignored) { }
        }
        if (editToolCalls < 0) {
            editToolCalls = trace.fileCount();
        }

        long nonEditToolCalls = Math.max(0, totalToolCalls - editToolCalls);
        map.put("toolCallCount", nonEditToolCalls);
        map.put("toolCallCountTotal", totalToolCalls);
        map.put("editToolCallCount", editToolCalls);
        map.put("linesAdded", trace.metadata() != null ? trace.metadata().get("lines_added") : 0);
        map.put("linesRemoved", trace.metadata() != null ? trace.metadata().get("lines_removed") : 0);
        map.put("linesModified", trace.totalLineCount());
        map.put("promptTokens", trace.metadata() != null ? trace.metadata().get("prompt_tokens") : 0);
        map.put("completionTokens", trace.metadata() != null ? trace.metadata().get("completion_tokens") : 0);
        map.put("latencyMs", trace.metadata() != null ? trace.metadata().get("latency_ms") : 0);
        String lastUserMessage = trace.metadata() != null ? String.valueOf(trace.metadata().get("last_user_message")) : "";
        String preview = (lastUserMessage != null && !lastUserMessage.isBlank())
                ? lastUserMessage
                : ("Trace: " + trace.files().stream()
                    .map(f -> f.path())
                    .limit(3)
                    .collect(Collectors.joining(", ")));
        map.put("lastUserMessagePreview", preview);
        
        // Tool calls: try to parse from metadata first (contains all tool calls),
        // fallback to constructing from file edits for older traces
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        
        // Try to get tool calls details from metadata if available
        if (trace.metadata() != null && trace.metadata().get("tool_calls_details") != null) {
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> parsed = (List<Map<String, Object>>) trace.metadata().get("tool_calls_details");
                toolCalls.addAll(parsed);
            } catch (Exception ignored) {
                // Failed to parse tool_calls_details, will fallback to file edits
            }
        }
        
        // Fallback: construct from file edits for backwards compatibility
        if (toolCalls.isEmpty() && !trace.files().isEmpty()) {
            toolCalls = trace.files().stream()
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
        }
        
        map.put("toolCalls", toolCalls);
        
        return map;
    }
}
