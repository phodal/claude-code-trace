package com.phodal.anthropicproxy.controller;

import com.phodal.anthropicproxy.converter.MetricsConverter;
import com.phodal.anthropicproxy.model.metrics.TurnLog;
import com.phodal.anthropicproxy.service.MetricsService;
import com.phodal.anthropicproxy.service.SessionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Controller for metrics dashboard
 */
@Controller
@RequestMapping("/metrics")
@RequiredArgsConstructor
public class MetricsDashboardController {

    private final MetricsService metricsService;
    private final MetricsConverter metricsConverter;

    /**
     * Main dashboard page
     */
    @GetMapping("")
    public String dashboard(Model model) {
        MetricsService.AggregatedMetrics metrics = metricsService.getAggregatedMetrics();
        
        model.addAttribute("totalRequests", metrics.getTotalRequests());
        model.addAttribute("totalToolCalls", metrics.getTotalToolCalls());
        model.addAttribute("totalEditToolCalls", metrics.getTotalEditToolCalls());
        model.addAttribute("totalLinesModified", metrics.getTotalLinesModified());
        model.addAttribute("totalInputTokens", metrics.getTotalInputTokens());
        model.addAttribute("totalOutputTokens", metrics.getTotalOutputTokens());
        model.addAttribute("activeUsers", metrics.getActiveUsers());
        model.addAttribute("toolCallsByName", metrics.getToolCallsByName());
        
        // Get user metrics
        List<Map<String, Object>> userMetricsList = metricsConverter.userMetricsListToMap(
                metricsService.getUserMetricsMap().values().stream().toList()
        );
        model.addAttribute("userMetrics", userMetricsList);
        
        // Get recent requests
        model.addAttribute("recentRequests", metricsService.getRecentRequests());
        
        return "dashboard";
    }

    /**
     * JSON API for metrics data
     */
    @GetMapping("/api/summary")
    @ResponseBody
    public MetricsService.AggregatedMetrics getMetricsSummary() {
        return metricsService.getAggregatedMetrics();
    }

    /**
     * JSON API for user metrics
     */
    @GetMapping("/api/users")
    @ResponseBody
    public List<Map<String, Object>> getUserMetrics() {
        return metricsService.getUserMetricsMap().values().stream()
                .map(metricsConverter::userMetricsToDetailedMap)
                .toList();
    }

    /**
     * JSON API for recent requests
     */
    @GetMapping("/api/recent")
    @ResponseBody
    public List<MetricsService.RequestLog> getRecentRequests() {
        return metricsService.getRecentRequests();
    }
    
    /**
     * JSON API for recent turns (message-level detail)
     */
    @GetMapping("/api/turns")
    @ResponseBody
    public List<Map<String, Object>> getRecentTurns() {
        return metricsConverter.turnLogListToMap(metricsService.getRecentTurns());
    }
    
    /**
     * JSON API for turns by user
     */
    @GetMapping("/api/users/{userId}/turns")
    @ResponseBody
    public List<Map<String, Object>> getTurnsByUser(@PathVariable String userId) {
        return metricsConverter.turnLogListToMap(metricsService.getTurnsForUser(userId));
    }
    
    /**
     * JSON API for turns by session
     */
    @GetMapping("/api/sessions/{sessionId}/turns")
    @ResponseBody
    public List<Map<String, Object>> getTurnsBySession(@PathVariable String sessionId) {
        return metricsConverter.turnLogListToMap(metricsService.getTurnsForSession(sessionId));
    }
    
    /**
     * JSON API for a specific turn with tool calls
     */
    @GetMapping("/api/turns/{turnId}")
    @ResponseBody
    public Map<String, Object> getTurnDetail(@PathVariable String turnId) {
        TurnLog turn = metricsService.getTurnById(turnId);
        if (turn == null) {
            return Map.of("error", "Turn not found");
        }
        return metricsConverter.turnLogToDetailMap(turn);
    }
    
    /**
     * JSON API for sessions
     */
    @GetMapping("/api/sessions")
    @ResponseBody
    public List<Map<String, Object>> getRecentSessions() {
        SessionManager sessionManager = metricsService.getSessionManager();
        return metricsConverter.sessionInfoListToMap(sessionManager.getRecentSessions(50));
    }
    
    /**
     * JSON API for sessions by user
     */
    @GetMapping("/api/users/{userId}/sessions")
    @ResponseBody
    public List<Map<String, Object>> getSessionsByUser(@PathVariable String userId) {
        SessionManager sessionManager = metricsService.getSessionManager();
        return metricsConverter.sessionInfoListToMap(sessionManager.getUserSessions(userId));
    }
}
