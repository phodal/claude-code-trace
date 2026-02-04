package com.phodal.anthropicproxy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phodal.agenttrace.model.*;
import com.phodal.agenttrace.store.TraceStore;
import com.phodal.agenttrace.util.ModelIdNormalizer;
import com.phodal.agenttrace.vcs.VcsProviderFactory;
import com.phodal.anthropicproxy.model.anthropic.AnthropicMessage;
import com.phodal.anthropicproxy.model.anthropic.AnthropicRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service for recording AI agent traces following the Agent Trace specification.
 * Replaces MetricsService with Agent Trace based tracking.
 *
 * @see <a href="https://agent-trace.dev/">Agent Trace Specification</a>
 */
@Slf4j
@Service
public class TraceService {

    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final TraceStore traceStore;
    private final Path workspacePath;

    // Counters for Prometheus metrics
    private final Counter totalRequestsCounter;
    private final Counter totalToolCallsCounter;
    private final Counter totalFileEditsCounter;
    private final Counter totalLinesModifiedCounter;

    // Active conversations (conversationId -> ConversationContext)
    private final Map<String, ConversationContext> activeConversations = new ConcurrentHashMap<>();

    // In-memory cache for recent traces (for dashboard)
    @Getter
    private final List<TraceRecord> recentTraces = Collections.synchronizedList(new LinkedList<>());
    private static final int MAX_RECENT_TRACES = 200;
    
    // In-memory cache for recent "turns" (all conversations, even without file edits)
    // This allows the UI to show all requests, not just those with file edits
    @Getter
    private final List<Map<String, Object>> recentTurns = Collections.synchronizedList(new LinkedList<>());
    private static final int MAX_RECENT_TURNS = 200;

    // User metrics aggregation
    @Getter
    private final Map<String, UserTraceMetrics> userMetrics = new ConcurrentHashMap<>();

    // Tool names that are considered edit tools
    private static final Set<String> EDIT_TOOL_NAMES = Set.of(
            "str_replace_editor", "StrReplace",
            "edit_file", "EditFile",
            "replace_string_in_file",
            "multi_replace_string_in_file",
            "create_file", "Write",
            "write_file",
            "insert_code",
            "delete_file", "Delete",
            "edit_notebook_file", "EditNotebook"
    );

    private static final int ARGS_PREVIEW_LENGTH = 150;
    private static final int LAST_USER_MESSAGE_MAX_LENGTH = 4000;

    public TraceService(
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${trace.workspace-path:#{systemProperties['user.dir']}}") String workspacePath) {
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.workspacePath = Path.of(workspacePath);
        this.traceStore = new TraceStore(this.workspacePath);

        this.totalRequestsCounter = Counter.builder("agent_trace.requests.total")
                .description("Total number of requests")
                .register(meterRegistry);

        this.totalToolCallsCounter = Counter.builder("agent_trace.tool_calls.total")
                .description("Total number of tool calls")
                .register(meterRegistry);

        this.totalFileEditsCounter = Counter.builder("agent_trace.file_edits.total")
                .description("Total number of file edit operations")
                .register(meterRegistry);

        this.totalLinesModifiedCounter = Counter.builder("agent_trace.lines_modified.total")
                .description("Total number of lines modified")
                .register(meterRegistry);
    }

    @PostConstruct
    public void init() {
        // Load recent traces from store on startup
        try {
            List<TraceRecord> existing = traceStore.readRecentTraces(MAX_RECENT_TRACES);
            synchronized (recentTraces) {
                recentTraces.addAll(existing);
            }
            log.info("Loaded {} existing traces from store", existing.size());
        } catch (IOException e) {
            log.warn("Failed to load existing traces: {}", e.getMessage());
        }
    }

    /**
     * Start a new conversation context and return a conversation ID.
     */
    public String startConversation(String userId, AnthropicRequest request, Map<String, String> headers) {
        totalRequestsCounter.increment();

        String conversationId = generateConversationId(userId);
        String model = request.getModel();
        String normalizedModelId = ModelIdNormalizer.normalize(model);

        ConversationContext context = ConversationContext.builder()
                .conversationId(conversationId)
                .userId(userId)
                .model(model)
                .normalizedModelId(normalizedModelId)
                .startTime(Instant.now())
                .lastUserMessage(extractLastUserMessage(request))
                .stream(Boolean.TRUE.equals(request.getStream()))
                .build();

        activeConversations.put(conversationId, context);

        // Update user metrics
        UserTraceMetrics metrics = userMetrics.computeIfAbsent(userId, UserTraceMetrics::new);
        metrics.incrementRequests();

        // Register model counter
        Counter.builder("agent_trace.requests.by_model")
                .tag("model", normalizedModelId)
                .tag("user", userId)
                .register(meterRegistry)
                .increment();

        log.debug("Started conversation {} for user {}, model {}", conversationId, userId, normalizedModelId);
        return conversationId;
    }

    /**
     * Record a file edit from a tool call.
     */
    public void recordFileEdit(String conversationId, String filePath, int startLine, int endLine,
                               int linesAdded, int linesRemoved, String toolName, String argsPreview) {
        recordFileEdit(conversationId, null, filePath, startLine, endLine, linesAdded, linesRemoved, toolName, argsPreview);
    }

    /**
     * Record a file edit from a tool call with an optional stable tool call ID (tool_use id).
     */
    public void recordFileEdit(String conversationId, String toolCallId, String filePath, int startLine, int endLine,
                               int linesAdded, int linesRemoved, String toolName, String argsPreview) {
        recordFileEditInternal(conversationId, toolCallId, filePath, startLine, endLine, linesAdded, linesRemoved, toolName, argsPreview, true);
    }

    /**
     * Internal file edit recorder that can avoid double-counting total tool calls.
     * When invoked from {@link #recordToolCall(String, String, String, String)}, total tool calls are already incremented.
     */
    private void recordFileEditInternal(String conversationId, String toolCallId, String filePath, int startLine, int endLine,
                                        int linesAdded, int linesRemoved, String toolName, String argsPreview,
                                        boolean countAsToolCall) {
        ConversationContext context = activeConversations.get(conversationId);
        if (context == null) {
            log.warn("No active conversation for ID: {}", conversationId);
            return;
        }

        if (countAsToolCall) {
            totalToolCallsCounter.increment();
        }
        totalFileEditsCounter.increment();

        // Use lines touched (added + removed) instead of net change for more accurate metrics
        int linesTouched = linesAdded + linesRemoved;
        totalLinesModifiedCounter.increment(linesTouched);

        // Create range for this edit - mark as estimated since we derive from tool args, not actual diff
        Range range = Range.estimated(startLine, endLine);

        // Get or create file entry
        FileEditInfo fileEdit = context.fileEdits.computeIfAbsent(filePath, FileEditInfo::new);
        fileEdit.addRange(range);
        fileEdit.addLinesAdded(linesAdded);
        fileEdit.addLinesRemoved(linesRemoved);

        // Record tool call
        context.addToolCall(ToolCallRecord.builder()
                .toolCallId(toolCallId)
                .toolName(toolName)
                .filePath(filePath)
                .startLine(startLine)
                .endLine(endLine)
                .linesAdded(linesAdded)
                .linesRemoved(linesRemoved)
                .argsPreview(argsPreview)
                .timestamp(Instant.now())
                .build());

        // Update user metrics
        UserTraceMetrics metrics = userMetrics.get(context.userId);
        if (metrics != null) {
            metrics.incrementToolCalls();
            metrics.incrementEditToolCalls();
            metrics.addLinesModified(linesTouched);
            metrics.addToolCall(toolName);
        }

        log.debug("Recorded file edit in {}: lines {}-{}, +{} -{}", filePath, startLine, endLine, linesAdded, linesRemoved);
    }

    /**
     * Record a tool call (not necessarily a file edit).
     */
    public void recordToolCall(String conversationId, String toolName, String args) {
        recordToolCall(conversationId, null, toolName, args);
    }

    /**
     * Record a tool call with a stable tool call ID (tool_use id).
     * The tool call ID is useful for linking tool_use -> tool_result across requests.
     */
    public void recordToolCall(String conversationId, String toolCallId, String toolName, String args) {
        ConversationContext context = activeConversations.get(conversationId);
        if (context == null) {
            log.warn("No active conversation for ID: {}", conversationId);
            return;
        }

        totalToolCallsCounter.increment();

        boolean isEdit = isEditTool(toolName);
        if (isEdit) {
            // Extract file edit info from args
            LinesModifiedInfo linesInfo = extractLinesModifiedFromArgs(args);
            if (linesInfo.filePath != null) {
                // Avoid double-counting total tool calls (already incremented above)
                recordFileEditInternal(conversationId, toolCallId, linesInfo.filePath,
                        linesInfo.startLine, linesInfo.endLine,
                        linesInfo.linesAdded, linesInfo.linesRemoved,
                        toolName, createArgsPreview(args),
                        false);
                return;
            }
        }

        // Non-edit tool call
        context.addToolCall(ToolCallRecord.builder()
                .toolCallId(toolCallId)
                .toolName(toolName)
                .argsPreview(createArgsPreview(args))
                .timestamp(Instant.now())
                .build());

        UserTraceMetrics metrics = userMetrics.get(context.userId);
        if (metrics != null) {
            metrics.incrementToolCalls();
            metrics.addToolCall(toolName);
        }

        Counter.builder("agent_trace.tool_calls.by_name")
                .tag("tool", toolName)
                .tag("user", context.userId)
                .register(meterRegistry)
                .increment();
    }

    /**
     * Record multiple tool calls from streaming completion.
     * This method also ends the conversation. Use recordStreamingToolCallsOnly()
     * if you want the Controller to manage conversation lifecycle.
     * 
     * @deprecated Use recordStreamingToolCallsOnly() and let Controller manage lifecycle
     */
    @Deprecated
    public void recordStreamingToolCalls(String userId, String conversationId,
                                          List<OpenAISdkService.ToolCallInfo> toolCalls, long latencyMs) {
        recordStreamingToolCallsOnly(userId, conversationId, toolCalls, latencyMs);
        endConversation(conversationId);
    }

    /**
     * Record multiple tool calls from streaming completion without ending the conversation.
     * The Controller is responsible for calling endConversation() separately.
     * This provides a cleaner separation of concerns.
     */
    public void recordStreamingToolCallsOnly(String userId, String conversationId,
                                              List<OpenAISdkService.ToolCallInfo> toolCalls, long latencyMs) {
        ConversationContext context = activeConversations.get(conversationId);
        if (context == null) {
            log.debug("No active conversation for ID: {} (may have been ended already)", conversationId);
            return;
        }

        context.latencyMs = latencyMs;

        if (toolCalls != null) {
            for (OpenAISdkService.ToolCallInfo toolCall : toolCalls) {
                recordToolCall(conversationId, toolCall.id(), toolCall.name(), toolCall.arguments());
            }
        }
    }

    /**
     * Get recorded tool call IDs (tool_use ids) for a conversation.
     * This can be used to attach correlation IDs to OTEL spans.
     */
    public List<String> getToolCallIds(String conversationId) {
        ConversationContext context = activeConversations.get(conversationId);
        if (context == null) {
            return List.of();
        }
        synchronized (context.toolCalls) {
            return context.toolCalls.stream()
                    .map(ToolCallRecord::getToolCallId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
        }
    }

    /**
     * Get a safe tool call summary JSON string for attaching to OTEL spans.
     * This intentionally omits arguments/content to avoid leaking sensitive data.
     *
     * @param conversationId conversation id
     * @param maxCalls max calls to include
     * @param maxJsonLength max json length (will be truncated)
     * @return JSON string or null if none
     */
    public String getToolCallsSummaryJson(String conversationId, int maxCalls, int maxJsonLength) {
        ConversationContext context = activeConversations.get(conversationId);
        if (context == null) {
            return null;
        }

        List<Map<String, Object>> summaries;
        synchronized (context.toolCalls) {
            summaries = context.toolCalls.stream()
                    .limit(Math.max(0, maxCalls))
                    .map(tc -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        if (tc.toolCallId != null) m.put("id", tc.toolCallId);
                        if (tc.toolName != null) m.put("name", tc.toolName);
                        if (tc.filePath != null) m.put("filePath", tc.filePath);
                        if (tc.startLine > 0) m.put("startLine", tc.startLine);
                        if (tc.endLine > 0) m.put("endLine", tc.endLine);
                        if (tc.linesAdded > 0) m.put("linesAdded", tc.linesAdded);
                        if (tc.linesRemoved > 0) m.put("linesRemoved", tc.linesRemoved);
                        if (tc.timestamp != null) m.put("timestamp", tc.timestamp.toString());
                        return m;
                    })
                    .toList();
        }

        if (summaries.isEmpty()) {
            return null;
        }

        try {
            String json = objectMapper.writeValueAsString(summaries);
            if (maxJsonLength > 0 && json.length() > maxJsonLength) {
                return json.substring(0, maxJsonLength) + "...";
            }
            return json;
        } catch (Exception e) {
            log.debug("Failed to serialize tool call summaries: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get a tool call detail JSON string for attaching to OTEL spans / UI.
     * Includes {@code argsPreview} (already truncated) for debugging.
     *
     * <p>Note: This MAY include sensitive information depending on tool usage.
     * Keep payload size bounded via {@code maxCalls} and {@code maxJsonLength}.</p>
     *
     * @param conversationId conversation id
     * @param maxCalls max calls to include
     * @param maxJsonLength max json length (will be truncated)
     * @return JSON string or null if none
     */
    public String getToolCallsDetailsJson(String conversationId, int maxCalls, int maxJsonLength) {
        ConversationContext context = activeConversations.get(conversationId);
        if (context == null) {
            return null;
        }

        List<Map<String, Object>> details;
        synchronized (context.toolCalls) {
            details = context.toolCalls.stream()
                    .limit(Math.max(0, maxCalls))
                    .map(tc -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        if (tc.toolCallId != null) m.put("id", tc.toolCallId);
                        if (tc.toolName != null) m.put("name", tc.toolName);
                        if (tc.filePath != null) m.put("filePath", tc.filePath);
                        if (tc.startLine > 0) m.put("startLine", tc.startLine);
                        if (tc.endLine > 0) m.put("endLine", tc.endLine);
                        if (tc.linesAdded > 0) m.put("linesAdded", tc.linesAdded);
                        if (tc.linesRemoved > 0) m.put("linesRemoved", tc.linesRemoved);
                        if (tc.argsPreview != null && !tc.argsPreview.isBlank()) m.put("argsPreview", tc.argsPreview);
                        if (tc.timestamp != null) m.put("timestamp", tc.timestamp.toString());
                        return m;
                    })
                    .toList();
        }

        if (details.isEmpty()) {
            return null;
        }

        try {
            String json = objectMapper.writeValueAsString(details);
            if (maxJsonLength > 0 && json.length() > maxJsonLength) {
                return json.substring(0, maxJsonLength) + "...";
            }
            return json;
        } catch (Exception e) {
            log.debug("Failed to serialize tool call details: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Record response tokens and latency.
     */
    public void recordResponse(String conversationId, int promptTokens, int completionTokens, long latencyMs) {
        ConversationContext context = activeConversations.get(conversationId);
        if (context == null) {
            return;
        }

        context.promptTokens = promptTokens;
        context.completionTokens = completionTokens;
        context.latencyMs = latencyMs;

        UserTraceMetrics metrics = userMetrics.get(context.userId);
        if (metrics != null) {
            metrics.addInputTokens(promptTokens);
            metrics.addOutputTokens(completionTokens);
        }
    }

    /**
     * End a conversation and generate the TraceRecord.
     * This method is idempotent - calling it multiple times is safe.
     * 
     * @param conversationId The conversation ID to end
     * @return The generated TraceRecord, or null if no file edits or already ended
     */
    public TraceRecord endConversation(String conversationId) {
        ConversationContext context = activeConversations.remove(conversationId);
        if (context == null) {
            // Idempotent: already ended or never existed - this is normal, not a warning
            log.debug("Conversation already ended or not found: {}", conversationId);
            return null;
        }

        // Calculate tool call metrics first
        int totalToolCalls;
        long editToolCalls;
        synchronized (context.toolCalls) {
            totalToolCalls = context.toolCalls.size();
            // Count edit tool calls as those that recorded a file path (i.e., file edits)
            editToolCalls = context.toolCalls.stream()
                    .filter(tc -> tc.filePath != null && !tc.filePath.isBlank())
                    .count();
        }
        
        // Store tool calls details for UI (with args preview for debugging)
        List<Map<String, Object>> toolCallsDetails = new ArrayList<>();
        synchronized (context.toolCalls) {
            for (ToolCallRecord tc : context.toolCalls) {
                Map<String, Object> tcMap = new HashMap<>();
                if (tc.toolCallId != null) tcMap.put("id", tc.toolCallId);
                if (tc.toolName != null) tcMap.put("name", tc.toolName);
                if (tc.filePath != null && !tc.filePath.isBlank()) tcMap.put("filePath", tc.filePath);
                if (tc.startLine > 0) tcMap.put("startLine", tc.startLine);
                if (tc.endLine > 0) tcMap.put("endLine", tc.endLine);
                if (tc.linesAdded > 0) tcMap.put("linesAdded", tc.linesAdded);
                if (tc.linesRemoved > 0) tcMap.put("linesRemoved", tc.linesRemoved);
                if (tc.argsPreview != null && !tc.argsPreview.isBlank()) tcMap.put("argsPreview", tc.argsPreview);
                if (tc.timestamp != null) tcMap.put("timestamp", tc.timestamp.toString());
                tcMap.put("status", "ok"); // Default to ok for now
                toolCallsDetails.add(tcMap);
            }
        }

        // ALWAYS create a turn summary (even if no file edits)
        // This ensures UI shows all requests, not just those with file edits
        Map<String, Object> turnSummary = new HashMap<>();
        turnSummary.put("turnId", conversationId);
        turnSummary.put("conversationId", conversationId);
        turnSummary.put("timestamp", context.startTime.toString());
        turnSummary.put("userId", context.userId);
        turnSummary.put("model", context.model);
        turnSummary.put("normalizedModelId", context.normalizedModelId);
        turnSummary.put("lastUserMessage", truncate(context.lastUserMessage, LAST_USER_MESSAGE_MAX_LENGTH));
        turnSummary.put("lastUserMessagePreview", truncate(context.lastUserMessage, 100));
        turnSummary.put("latencyMs", context.latencyMs);
        turnSummary.put("promptTokens", context.promptTokens);
        turnSummary.put("completionTokens", context.completionTokens);
        turnSummary.put("toolCalls", totalToolCalls);
        turnSummary.put("editToolCalls", editToolCalls);
        turnSummary.put("linesAdded", context.getTotalLinesAdded());
        turnSummary.put("linesRemoved", context.getTotalLinesRemoved());
        turnSummary.put("linesModified", context.getTotalLinesAdded() + context.getTotalLinesRemoved());
        turnSummary.put("toolCallsDetails", toolCallsDetails);
        
        addRecentTurn(turnSummary);

        // Create trace ONLY if there are file edits (Agent Trace spec requires at least one file)
        boolean hasFileEdits = !context.fileEdits.isEmpty();
        
        if (!hasFileEdits) {
            log.debug("Conversation {} ended without file edits, turn summary created but no trace", conversationId);
            return null;
        }

        // Build TraceRecord (only when there are file edits)
        TraceRecord.Builder builder = TraceRecord.builder()
                .timestamp(context.startTime)
                .tool("anthropic-proxy", "1.0.0");

        // Add VCS info if available
        VcsProviderFactory.getVcs(workspacePath).ifPresent(builder::vcs);

        // Build file entries from edits
        Contributor contributor = Contributor.ai(context.normalizedModelId);

        for (Map.Entry<String, FileEditInfo> entry : context.fileEdits.entrySet()) {
            String relativePath = toRelativePath(entry.getKey());
            FileEditInfo editInfo = entry.getValue();

            Conversation conversation = Conversation.builder()
                    .contributor(contributor)
                    .ranges(editInfo.ranges)
                    .build();

            builder.addFile(FileEntry.of(relativePath, conversation));
        }

        // Add metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("conversation_id", conversationId);
        metadata.put("user_id", context.userId);
        metadata.put("last_user_message", truncate(context.lastUserMessage, LAST_USER_MESSAGE_MAX_LENGTH));
        metadata.put("latency_ms", context.latencyMs);
        metadata.put("prompt_tokens", context.promptTokens);
        metadata.put("completion_tokens", context.completionTokens);
        metadata.put("tool_calls", totalToolCalls);
        metadata.put("edit_tool_calls", editToolCalls);
        metadata.put("lines_added", context.getTotalLinesAdded());
        metadata.put("lines_removed", context.getTotalLinesRemoved());
        
        if (!toolCallsDetails.isEmpty()) {
            metadata.put("tool_calls_details", toolCallsDetails);
        }
        
        builder.metadata(metadata);

        TraceRecord record = builder.build();

        // Store the trace
        try {
            traceStore.appendTrace(record);
            addRecentTrace(record);
            log.info("Created trace {} with {} files, {} lines modified",
                    record.id(), record.fileCount(), record.totalLineCount());
        } catch (IOException e) {
            log.error("Failed to store trace: {}", e.getMessage());
        }

        return record;
    }
    
    private void addRecentTurn(Map<String, Object> turnSummary) {
        synchronized (recentTurns) {
            recentTurns.add(turnSummary);
            while (recentTurns.size() > MAX_RECENT_TURNS) {
                recentTurns.remove(0);
            }
        }
    }

    /**
     * Get aggregated summary statistics.
     */
    public TraceSummary getSummary() {
        TraceSummary summary = new TraceSummary();

        long totalRequests = 0;
        long totalToolCalls = 0;
        long totalEditToolCalls = 0;
        long totalLinesModified = 0;
        long totalInputTokens = 0;
        long totalOutputTokens = 0;
        Map<String, Long> toolCallsByName = new HashMap<>();

        for (UserTraceMetrics metrics : userMetrics.values()) {
            totalRequests += metrics.getTotalRequests().get();
            totalToolCalls += metrics.getTotalToolCalls().get();
            totalEditToolCalls += metrics.getEditToolCalls().get();
            totalLinesModified += metrics.getLinesModified().get();
            totalInputTokens += metrics.getInputTokens().get();
            totalOutputTokens += metrics.getOutputTokens().get();

            for (Map.Entry<String, AtomicLong> entry : metrics.getToolCallsByName().entrySet()) {
                toolCallsByName.merge(entry.getKey(), entry.getValue().get(), Long::sum);
            }
        }

        summary.setTotalRequests(totalRequests);
        summary.setTotalToolCalls(totalToolCalls);
        summary.setTotalEditToolCalls(totalEditToolCalls);
        summary.setTotalLinesModified(totalLinesModified);
        summary.setTotalInputTokens(totalInputTokens);
        summary.setTotalOutputTokens(totalOutputTokens);
        summary.setActiveUsers(userMetrics.size());
        summary.setToolCallsByName(toolCallsByName);

        try {
            summary.setTotalTraces(traceStore.getTraceCount());
        } catch (IOException e) {
            summary.setTotalTraces(recentTraces.size());
        }

        return summary;
    }

    /**
     * Get recent traces for dashboard.
     */
    public List<TraceRecord> getRecentTraces(int limit) {
        synchronized (recentTraces) {
            int start = Math.max(0, recentTraces.size() - limit);
            return new ArrayList<>(recentTraces.subList(start, recentTraces.size()));
        }
    }

    /**
     * Get traces by time range.
     */
    public List<TraceRecord> getTracesByTimeRange(Instant from, Instant to) {
        try {
            return traceStore.readTracesByTimeRange(from, to);
        } catch (IOException e) {
            log.error("Failed to read traces: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get traces by file path.
     */
    public List<TraceRecord> getTracesByFile(String filePath) {
        try {
            return traceStore.readTracesByFile(filePath);
        } catch (IOException e) {
            log.error("Failed to read traces: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Find a trace by ID.
     */
    public Optional<TraceRecord> findTraceById(UUID id) {
        try {
            return traceStore.findById(id);
        } catch (IOException e) {
            log.error("Failed to find trace: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // Helper methods

    private String generateConversationId(String userId) {
        return String.format("conv-%s-%d-%s",
                userId.length() > 6 ? userId.substring(0, 6) : userId,
                System.currentTimeMillis(),
                UUID.randomUUID().toString().substring(0, 6));
    }

    private void addRecentTrace(TraceRecord trace) {
        synchronized (recentTraces) {
            recentTraces.add(trace);
            while (recentTraces.size() > MAX_RECENT_TRACES) {
                recentTraces.remove(0);
            }
        }
    }

    private String extractLastUserMessage(AnthropicRequest request) {
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            return "";
        }

        for (int i = request.getMessages().size() - 1; i >= 0; i--) {
            AnthropicMessage msg = request.getMessages().get(i);
            if ("user".equals(msg.getRole())) {
                Object content = msg.getContent();
                if (content instanceof String) {
                    return (String) content;
                } else if (content instanceof List) {
                    StringBuilder sb = new StringBuilder();
                    try {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> blocks = (List<Map<String, Object>>) content;
                        for (Map<String, Object> block : blocks) {
                            if ("text".equals(block.get("type")) && block.get("text") != null) {
                                sb.append(block.get("text")).append(" ");
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Failed to parse content blocks: {}", e.getMessage());
                    }
                    return sb.toString().trim();
                }
            }
        }
        return "";
    }

    private boolean isEditTool(String toolName) {
        if (toolName == null) return false;
        String lowerName = toolName.toLowerCase();
        return EDIT_TOOL_NAMES.stream().anyMatch(editTool ->
                lowerName.contains(editTool.toLowerCase()) ||
                        lowerName.contains("edit") ||
                        lowerName.contains("write") ||
                        lowerName.contains("replace") ||
                        lowerName.contains("create_file") ||
                        lowerName.contains("modify"));
    }

    @SuppressWarnings("unchecked")
    private LinesModifiedInfo extractLinesModifiedFromArgs(String args) {
        if (args == null || args.isEmpty()) {
            return new LinesModifiedInfo();
        }

        try {
            Map<String, Object> argsMap = objectMapper.readValue(args, Map.class);

            String filePath = null;
            if (argsMap.containsKey("file_path")) {
                filePath = String.valueOf(argsMap.get("file_path"));
            } else if (argsMap.containsKey("path")) {
                filePath = String.valueOf(argsMap.get("path"));
            } else if (argsMap.containsKey("filePath")) {
                filePath = String.valueOf(argsMap.get("filePath"));
            }

            int newLines = 0;
            int oldLines = 0;

            // Replacement patterns
            if (argsMap.containsKey("old_string") && argsMap.containsKey("new_string")) {
                oldLines = countLines(String.valueOf(argsMap.get("old_string")));
                newLines = countLines(String.valueOf(argsMap.get("new_string")));
            } else if (argsMap.containsKey("old_str") && argsMap.containsKey("new_str")) {
                oldLines = countLines(String.valueOf(argsMap.get("old_str")));
                newLines = countLines(String.valueOf(argsMap.get("new_str")));
            } else if (argsMap.containsKey("oldString") && argsMap.containsKey("newString")) {
                oldLines = countLines(String.valueOf(argsMap.get("oldString")));
                newLines = countLines(String.valueOf(argsMap.get("newString")));
            }

            // Write operations
            if (newLines == 0 && oldLines == 0) {
                if (argsMap.containsKey("content")) {
                    newLines = countLines(String.valueOf(argsMap.get("content")));
                } else if (argsMap.containsKey("contents")) {
                    newLines = countLines(String.valueOf(argsMap.get("contents")));
                }
            }

            return new LinesModifiedInfo(filePath, 1, Math.max(1, newLines), newLines, oldLines);
        } catch (Exception e) {
            log.debug("Failed to extract lines modified: {}", e.getMessage());
            return new LinesModifiedInfo();
        }
    }

    private int countLines(String text) {
        if (text == null || text.isEmpty()) return 0;
        return text.split("\n").length;
    }

    private String createArgsPreview(String args) {
        if (args == null || args.isEmpty()) return "";
        if (args.length() <= ARGS_PREVIEW_LENGTH) return args;
        return args.substring(0, ARGS_PREVIEW_LENGTH) + "...";
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (maxLen <= 0) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    private String toRelativePath(String absolutePath) {
        try {
            Path absPath = Path.of(absolutePath);
            if (absPath.startsWith(workspacePath)) {
                return workspacePath.relativize(absPath).toString().replace('\\', '/');
            }
            return absolutePath;
        } catch (Exception e) {
            return absolutePath;
        }
    }

    // Inner classes

    @Data
    @Builder
    private static class ConversationContext {
        private String conversationId;
        private String userId;
        private String model;
        private String normalizedModelId;
        private Instant startTime;
        private String lastUserMessage;
        private boolean stream;
        private int promptTokens;
        private int completionTokens;
        private long latencyMs;

        @Builder.Default
        private Map<String, FileEditInfo> fileEdits = new ConcurrentHashMap<>();
        @Builder.Default
        private List<ToolCallRecord> toolCalls = Collections.synchronizedList(new ArrayList<>());

        public void addToolCall(ToolCallRecord record) {
            toolCalls.add(record);
        }

        public int getTotalLinesAdded() {
            return fileEdits.values().stream().mapToInt(f -> f.linesAdded).sum();
        }

        public int getTotalLinesRemoved() {
            return fileEdits.values().stream().mapToInt(f -> f.linesRemoved).sum();
        }
    }

    @Data
    private static class FileEditInfo {
        private final String filePath;
        private final List<Range> ranges = new ArrayList<>();
        private int linesAdded = 0;
        private int linesRemoved = 0;

        public FileEditInfo(String filePath) {
            this.filePath = filePath;
        }

        public void addRange(Range range) {
            ranges.add(range);
        }

        public void addLinesAdded(int count) {
            linesAdded += count;
        }

        public void addLinesRemoved(int count) {
            linesRemoved += count;
        }
    }

    @Data
    @Builder
    private static class ToolCallRecord {
        private String toolCallId;
        private String toolName;
        private String filePath;
        private int startLine;
        private int endLine;
        private int linesAdded;
        private int linesRemoved;
        private String argsPreview;
        private Instant timestamp;
    }

    private static class LinesModifiedInfo {
        String filePath;
        int startLine = 1;
        int endLine = 1;
        int linesAdded = 0;
        int linesRemoved = 0;

        LinesModifiedInfo() {}

        LinesModifiedInfo(String filePath, int startLine, int endLine, int linesAdded, int linesRemoved) {
            this.filePath = filePath;
            this.startLine = startLine;
            this.endLine = endLine;
            this.linesAdded = linesAdded;
            this.linesRemoved = linesRemoved;
        }
    }

    /**
     * User-level aggregated metrics.
     */
    @Getter
    public static class UserTraceMetrics {
        private final String userId;
        private final AtomicLong totalRequests = new AtomicLong(0);
        private final AtomicLong totalToolCalls = new AtomicLong(0);
        private final AtomicLong editToolCalls = new AtomicLong(0);
        private final AtomicLong linesModified = new AtomicLong(0);
        private final AtomicLong inputTokens = new AtomicLong(0);
        private final AtomicLong outputTokens = new AtomicLong(0);
        private final Map<String, AtomicLong> toolCallsByName = new ConcurrentHashMap<>();
        private LocalDateTime firstSeen;
        private LocalDateTime lastSeen;

        public UserTraceMetrics(String userId) {
            this.userId = userId;
            this.firstSeen = LocalDateTime.now();
            this.lastSeen = LocalDateTime.now();
        }

        public void incrementRequests() {
            totalRequests.incrementAndGet();
            lastSeen = LocalDateTime.now();
        }

        public void incrementToolCalls() {
            totalToolCalls.incrementAndGet();
        }

        public void incrementEditToolCalls() {
            editToolCalls.incrementAndGet();
        }

        public void addLinesModified(int lines) {
            linesModified.addAndGet(lines);
        }

        public void addInputTokens(int tokens) {
            inputTokens.addAndGet(tokens);
        }

        public void addOutputTokens(int tokens) {
            outputTokens.addAndGet(tokens);
        }

        public void addToolCall(String toolName) {
            toolCallsByName.computeIfAbsent(toolName, k -> new AtomicLong(0)).incrementAndGet();
        }
    }

    /**
     * Summary statistics.
     */
    @Data
    public static class TraceSummary {
        private long totalRequests;
        private long totalToolCalls;
        private long totalEditToolCalls;
        private long totalLinesModified;
        private long totalInputTokens;
        private long totalOutputTokens;
        private int activeUsers;
        private long totalTraces;
        private Map<String, Long> toolCallsByName;
    }
}
