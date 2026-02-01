package com.phodal.anthropicproxy.mapper;

import com.phodal.anthropicproxy.model.metrics.SessionInfo;
import com.phodal.anthropicproxy.model.metrics.ToolCallLog;
import com.phodal.anthropicproxy.model.metrics.TurnLog;
import com.phodal.anthropicproxy.service.MetricsService;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Converter for transforming metrics domain objects to API response maps
 */
@Component
public class MetricsConverter {

    /**
     * Convert UserMetrics to Map for API response
     */
    public Map<String, Object> userMetricsToMap(MetricsService.UserMetrics userMetrics) {
        Map<String, Object> map = new HashMap<>();
        map.put("userId", userMetrics.getUserId());
        map.put("totalRequests", userMetrics.getTotalRequests().get());
        map.put("totalToolCalls", userMetrics.getTotalToolCalls().get());
        map.put("editToolCalls", userMetrics.getEditToolCalls().get());
        map.put("linesModified", userMetrics.getLinesModified().get());
        map.put("inputTokens", userMetrics.getInputTokens().get());
        map.put("outputTokens", userMetrics.getOutputTokens().get());
        map.put("firstSeen", userMetrics.getFirstSeen());
        map.put("lastSeen", userMetrics.getLastSeen());
        return map;
    }

    /**
     * Convert UserMetrics to Map with detailed tool calls breakdown
     */
    public Map<String, Object> userMetricsToDetailedMap(MetricsService.UserMetrics userMetrics) {
        Map<String, Object> map = userMetricsToMap(userMetrics);
        map.put("toolCallsByName", userMetrics.getToolCallsByName().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get())));
        return map;
    }

    /**
     * Convert list of UserMetrics to list of maps
     */
    public List<Map<String, Object>> userMetricsListToMap(List<MetricsService.UserMetrics> userMetricsList) {
        return userMetricsList.stream()
                .map(this::userMetricsToMap)
                .collect(Collectors.toList());
    }

    /**
     * Convert TurnLog to Map (summary view)
     */
    public Map<String, Object> turnLogToMap(TurnLog turn) {
        Map<String, Object> map = new HashMap<>();
        map.put("turnId", turn.getTurnId());
        map.put("userId", turn.getUserId());
        map.put("sessionId", turn.getSessionId());
        map.put("timestamp", turn.getTimestamp());
        map.put("model", turn.getModel());
        map.put("stream", turn.isStream());
        map.put("toolsOfferedCount", turn.getToolsOfferedCount());
        map.put("lastUserMessagePreview", turn.getLastUserMessagePreview());
        map.put("promptTokens", turn.getPromptTokens());
        map.put("completionTokens", turn.getCompletionTokens());
        map.put("latencyMs", turn.getLatencyMs());
        map.put("toolCallCount", turn.getToolCallCount());
        map.put("editToolCallCount", turn.getEditToolCallCount());
        map.put("linesModified", turn.getLinesModified());
        map.put("linesAdded", turn.getLinesAdded());
        map.put("linesRemoved", turn.getLinesRemoved());
        map.put("hasError", turn.isHasError());
        map.put("errorMessage", turn.getErrorMessage());

        // Include tool calls
        if (turn.getToolCalls() != null) {
            map.put("toolCalls", turn.getToolCalls().stream()
                    .map(this::toolCallLogToMap)
                    .collect(Collectors.toList()));
        }

        return map;
    }

    /**
     * Convert TurnLog to Map (detailed view with full user message)
     */
    public Map<String, Object> turnLogToDetailMap(TurnLog turn) {
        Map<String, Object> map = turnLogToMap(turn);
        map.put("lastUserMessage", turn.getLastUserMessage());
        return map;
    }

    /**
     * Convert list of TurnLogs to list of maps
     */
    public List<Map<String, Object>> turnLogListToMap(List<TurnLog> turns) {
        return turns.stream()
                .map(this::turnLogToMap)
                .collect(Collectors.toList());
    }

    /**
     * Convert ToolCallLog to Map
     */
    public Map<String, Object> toolCallLogToMap(ToolCallLog toolCall) {
        Map<String, Object> map = new HashMap<>();
        map.put("toolCallId", toolCall.getToolCallId());
        map.put("turnId", toolCall.getTurnId());
        map.put("name", toolCall.getName());
        map.put("argsPreview", toolCall.getArgsPreview());
        map.put("timestamp", toolCall.getTimestamp());
        map.put("durationMs", toolCall.getDurationMs());
        map.put("status", toolCall.getStatus());
        map.put("linesModified", toolCall.getLinesModified());
        map.put("linesAdded", toolCall.getLinesAdded());
        map.put("linesRemoved", toolCall.getLinesRemoved());
        map.put("filePath", toolCall.getFilePath());
        map.put("errorMessage", toolCall.getErrorMessage());
        return map;
    }

    /**
     * Convert SessionInfo to Map
     */
    public Map<String, Object> sessionInfoToMap(SessionInfo session) {
        Map<String, Object> map = new HashMap<>();
        map.put("sessionId", session.getSessionId());
        map.put("userId", session.getUserId());
        map.put("startTime", session.getStartTime());
        map.put("lastActivityTime", session.getLastActivityTime());
        map.put("turnCount", session.getTurnCount().get());
        map.put("totalToolCalls", session.getTotalToolCalls().get());
        map.put("editToolCalls", session.getEditToolCalls().get());
        map.put("totalLinesModified", session.getTotalLinesModified().get());
        map.put("totalPromptTokens", session.getTotalPromptTokens().get());
        map.put("totalCompletionTokens", session.getTotalCompletionTokens().get());
        map.put("avgToolCallsPerTurn", session.getAvgToolCallsPerTurn());
        map.put("avgLatencyMs", session.getAvgLatencyMs());
        map.put("errorCount", session.getErrorCount().get());
        map.put("toolUsage", session.getToolUsageSnapshot());
        return map;
    }

    /**
     * Convert list of SessionInfo to list of maps
     */
    public List<Map<String, Object>> sessionInfoListToMap(List<SessionInfo> sessions) {
        return sessions.stream()
                .map(this::sessionInfoToMap)
                .collect(Collectors.toList());
    }
}

