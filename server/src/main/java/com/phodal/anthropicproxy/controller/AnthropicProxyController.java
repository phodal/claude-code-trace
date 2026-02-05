package com.phodal.anthropicproxy.controller;

import com.phodal.anthropicproxy.model.anthropic.AnthropicRequest;
import com.phodal.anthropicproxy.model.anthropic.AnthropicResponse;
import com.phodal.anthropicproxy.otel.OtelSpanManager;
import com.phodal.anthropicproxy.otel.model.Span;
import com.phodal.anthropicproxy.otel.model.SpanKind;
import com.phodal.anthropicproxy.otel.model.SpanStatus;
import com.phodal.anthropicproxy.otel.model.Trace;
import com.phodal.anthropicproxy.otel.service.ExporterService;
import com.phodal.anthropicproxy.otel.service.OtelTraceService;
import com.phodal.anthropicproxy.service.OpenAISdkService;
import com.phodal.anthropicproxy.service.TraceService;
import com.phodal.anthropicproxy.service.UserIdentificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.SignalType;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Controller to handle Anthropic API proxy requests.
 * Uses official OpenAI Java SDK for API calls.
 * Records traces using Agent Trace specification and OTEL for observability.
 */
@Slf4j
@RestController
@RequestMapping("/anthropic")
@RequiredArgsConstructor
public class AnthropicProxyController {

    private final OpenAISdkService sdkService;
    private final TraceService traceService;
    private final UserIdentificationService userIdentificationService;
    private final OtelTraceService otelTraceService;
    private final ExporterService exporterService;
    private final OtelSpanManager otelSpanManager;
    
    // Whitelist only (avoid recording sensitive headers)
    private static final Set<String> CORRELATION_HEADER_KEYS = Set.of(
            "x-request-id",
            "x-correlation-id",
            "x-session-id",
            "x-conversation-id",
            "x-turn-id",
            "traceparent",
            "tracestate",
            "user-agent",
            "anthropic-version",
            "anthropic-beta",
            "x-stainless-lang",
            "x-stainless-package-version",
            "x-stainless-runtime",
            "x-stainless-runtime-version",
            "x-stainless-os",
            "x-stainless-arch"
    );

    /**
     * Handle Anthropic Messages API requests
     */
    @PostMapping(value = "/v1/messages")
    public ResponseEntity<?> handleMessages(
            @RequestBody AnthropicRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) throws IOException {

        String userId = userIdentificationService.identifyUser(httpRequest);
        String apiKey = userIdentificationService.extractApiKey(httpRequest);
        Map<String, String> headers = userIdentificationService.collectHeaders(httpRequest);

        // Start OTEL trace
        String traceId = otelTraceService.generateTraceId();
        Trace trace = otelTraceService.startTrace(traceId);

        // Create root span for the request
        Span rootSpan = otelTraceService.startSpan(traceId, "anthropic.messages", SpanKind.SERVER, null);
        rootSpan.addAttribute("http.method", "POST");
        rootSpan.addAttribute("http.route", "/anthropic/v1/messages");
        rootSpan.addAttribute("model", request.getModel());
        rootSpan.addAttribute("stream", request.getStream() != null ? request.getStream() : false);
        rootSpan.addAttribute("user.id", userId);
        addSafeHeaderAttributes(rootSpan, httpRequest);

        log.info("Received request from user: {}, model: {}, stream: {}, traceId: {}", 
                userId, request.getModel(), request.getStream(), traceId);

        // Add attributes to real OTEL SDK span (created by TraceContextFilter)
        boolean streaming = Boolean.TRUE.equals(request.getStream());
        otelSpanManager.addRequestAttributes(httpRequest, request.getModel(), userId, null, streaming);

        if (apiKey == null || apiKey.isEmpty()) {
            log.error("No API key provided");
            rootSpan.setStatus(SpanStatus.error("No API key provided"));
            otelTraceService.endSpan(rootSpan, rootSpan.getStatus());
            otelTraceService.completeTrace(traceId);
            exporterService.exportTrace(trace);
            
            return ResponseEntity.status(401).body(Map.of(
                    "type", "error",
                    "error", Map.of(
                            "type", "authentication_error",
                            "message", "No API key provided"
                    )
            ));
        }

        // Start conversation and get conversationId for Agent Trace tracking
        String conversationId = traceService.startConversation(userId, request, headers);
        rootSpan.addAttribute("conversation.id", conversationId);
        
        // Update OTEL SDK span with conversation ID
        otelSpanManager.addRequestAttributes(httpRequest, request.getModel(), userId, conversationId, streaming);

        // Correlation: tool_use_ids consumed by this request (tool_result.tool_use_id)
        List<String> consumedToolUseIds = extractConsumedToolUseIds(request);
        if (!consumedToolUseIds.isEmpty()) {
            rootSpan.addAttribute("tool.use_ids.consumed_count", consumedToolUseIds.size());
            rootSpan.addAttribute("tool.use_ids.consumed", String.join(",", consumedToolUseIds));
        }

        // Handle streaming vs non-streaming
        if (Boolean.TRUE.equals(request.getStream())) {
            handleStreamingRequest(request, userId, conversationId, apiKey, httpResponse, traceId, trace, rootSpan);
            return null;
        } else {
            return handleNonStreamingRequest(request, userId, conversationId, apiKey, traceId, trace, rootSpan);
        }
    }

    /**
     * Handle non-streaming request
     */
    private ResponseEntity<?> handleNonStreamingRequest(
            AnthropicRequest request, String userId, String conversationId, String apiKey,
            String traceId, Trace trace, Span rootSpan) {

        // Create span for API call
        Span apiSpan = otelTraceService.startSpan(traceId, "anthropic.api.call", SpanKind.CLIENT, rootSpan.getSpanId());
        apiSpan.addAttribute("api.endpoint", "messages");
        apiSpan.addAttribute("api.model", request.getModel());

        try {
            AnthropicResponse response = sdkService.sendRequest(request, userId, conversationId, apiKey).block();

            // Add response attributes
            apiSpan.addAttribute("response.id", response != null ? response.getId() : "unknown");
            if (response != null && response.getUsage() != null) {
                apiSpan.addAttribute("tokens.input", response.getUsage().getInputTokens());
                apiSpan.addAttribute("tokens.output", response.getUsage().getOutputTokens());
            }

            // Attach safe tool call summaries for UI/debugging (no args/content)
            String toolCallsJson = traceService.getToolCallsSummaryJson(conversationId, 50, 4000);
            if (toolCallsJson != null && !toolCallsJson.isBlank()) {
                apiSpan.addAttribute("tool.calls.count", traceService.getToolCallIds(conversationId).size());
                apiSpan.addAttribute("tool.calls.summary", toolCallsJson);
                rootSpan.addAttribute("tool.calls.count", traceService.getToolCallIds(conversationId).size());
                rootSpan.addAttribute("tool.calls.summary", toolCallsJson);
            }
            // Optional: attach tool call args preview (may contain sensitive info)
            String toolCallsDetailsJson = traceService.getToolCallsDetailsJson(conversationId, 50, 4000);
            if (toolCallsDetailsJson != null && !toolCallsDetailsJson.isBlank()) {
                apiSpan.addAttribute("tool.calls.details", toolCallsDetailsJson);
                rootSpan.addAttribute("tool.calls.details", toolCallsDetailsJson);
            }

            // Correlation: tool_use ids emitted by the model in this response (non-streaming)
            List<String> emittedToolUseIds = extractEmittedToolUseIds(response);
            if (!emittedToolUseIds.isEmpty()) {
                String joined = String.join(",", emittedToolUseIds);
                apiSpan.addAttribute("tool.use_ids.emitted_count", emittedToolUseIds.size());
                apiSpan.addAttribute("tool.use_ids.emitted", joined);
                rootSpan.addAttribute("tool.use_ids.emitted_count", emittedToolUseIds.size());
                rootSpan.addAttribute("tool.use_ids.emitted", joined);
            }

            apiSpan.setStatus(SpanStatus.ok());
            rootSpan.setStatus(SpanStatus.ok());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error handling non-streaming request: {}", e.getMessage(), e);

            apiSpan.setStatus(SpanStatus.error(e.getMessage()));
            rootSpan.setStatus(SpanStatus.error(e.getMessage()));

            return ResponseEntity.internalServerError().body(Map.of(
                    "type", "error",
                    "error", Map.of(
                            "type", "api_error",
                            "message", e.getMessage() != null ? e.getMessage() : "Unknown error"
                    )
            ));
        } finally {
            otelTraceService.endSpan(apiSpan, apiSpan.getStatus());
            otelTraceService.endSpan(rootSpan, rootSpan.getStatus());

            // End conversation to generate Agent Trace record (if any)
            traceService.endConversation(conversationId);

            // Complete OTEL trace and export using captured reference
            otelTraceService.completeTrace(traceId);
            exporterService.exportTrace(trace);
        }
    }

    /**
     * Handle streaming request - writes directly to response
     */
    private void handleStreamingRequest(
            AnthropicRequest request, String userId, String conversationId, String apiKey,
            HttpServletResponse httpResponse, String traceId, Trace trace, Span rootSpan) throws IOException {

        // Create span for streaming API call
        Span streamSpan = otelTraceService.startSpan(traceId, "anthropic.api.stream", SpanKind.CLIENT, rootSpan.getSpanId());
        streamSpan.addAttribute("api.endpoint", "messages");
        streamSpan.addAttribute("api.model", request.getModel());
        streamSpan.addAttribute("streaming", true);

        httpResponse.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        httpResponse.setCharacterEncoding("UTF-8");
        httpResponse.setHeader("Cache-Control", "no-cache");
        httpResponse.setHeader("Connection", "keep-alive");
        httpResponse.setHeader("X-Accel-Buffering", "no");

        PrintWriter writer = httpResponse.getWriter();

        try {
            sdkService.sendStreamingRequest(request, userId, conversationId, apiKey)
                    .doOnNext(chunk -> {
                        writer.print(chunk);
                        writer.flush();
                    })
                    .doOnError(e -> {
                        log.error("Error in streaming: {}", e.getMessage());
                        String msg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                        writer.print("event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"" + msg.replace("\"", "\\\"") + "\"}}\n\n");
                        writer.flush();

                        streamSpan.setStatus(SpanStatus.error(e.getMessage()));
                        rootSpan.setStatus(SpanStatus.error(e.getMessage()));
                    })
                    .doFinally(signalType -> {
                        if (signalType == SignalType.ON_COMPLETE) {
                            streamSpan.setStatus(SpanStatus.ok());
                            rootSpan.setStatus(SpanStatus.ok());
                        } else if (streamSpan.getStatus() == null) {
                            streamSpan.setStatus(SpanStatus.error("Streaming terminated: " + signalType));
                            rootSpan.setStatus(SpanStatus.error("Streaming terminated: " + signalType));
                        }

                        // Correlation: tool_use ids emitted during streaming (recorded by TraceService)
                        List<String> emitted = traceService.getToolCallIds(conversationId);
                        if (!emitted.isEmpty()) {
                            String joined = String.join(",", emitted);
                            streamSpan.addAttribute("tool.use_ids.emitted_count", emitted.size());
                            streamSpan.addAttribute("tool.use_ids.emitted", joined);
                            rootSpan.addAttribute("tool.use_ids.emitted_count", emitted.size());
                            rootSpan.addAttribute("tool.use_ids.emitted", joined);
                        }

                        // Attach safe tool call summaries for UI/debugging (no args/content)
                        String toolCallsJson = traceService.getToolCallsSummaryJson(conversationId, 50, 4000);
                        if (toolCallsJson != null && !toolCallsJson.isBlank()) {
                            int callCount = traceService.getToolCallIds(conversationId).size();
                            streamSpan.addAttribute("tool.calls.count", callCount);
                            streamSpan.addAttribute("tool.calls.summary", toolCallsJson);
                            rootSpan.addAttribute("tool.calls.count", callCount);
                            rootSpan.addAttribute("tool.calls.summary", toolCallsJson);
                        }
                        // Optional: attach tool call args preview (may contain sensitive info)
                        String toolCallsDetailsJson = traceService.getToolCallsDetailsJson(conversationId, 50, 4000);
                        if (toolCallsDetailsJson != null && !toolCallsDetailsJson.isBlank()) {
                            streamSpan.addAttribute("tool.calls.details", toolCallsDetailsJson);
                            rootSpan.addAttribute("tool.calls.details", toolCallsDetailsJson);
                        }

                        // Always end spans and complete/export the OTEL trace to avoid leaks.
                        otelTraceService.endSpan(streamSpan, streamSpan.getStatus());
                        otelTraceService.endSpan(rootSpan, rootSpan.getStatus());
                        otelTraceService.completeTrace(traceId);
                        exporterService.exportTrace(trace);

                        // Always end conversation here - Controller owns the lifecycle.
                        // This is idempotent, so it's safe even if SDK already recorded tool calls.
                        traceService.endConversation(conversationId);
                    })
                    .blockLast();
        } catch (Exception e) {
            log.error("Error handling streaming request: {}", e.getMessage(), e);
        }
    }

    /**
     * Extract tool_use ids consumed by this request.
     * We look for content blocks with type "tool_result" and take "tool_use_id".
     */
    @SuppressWarnings("unchecked")
    private List<String> extractConsumedToolUseIds(AnthropicRequest request) {
        if (request == null || request.getMessages() == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (var msg : request.getMessages()) {
            if (msg == null) continue;
            Object content = msg.getContent();
            if (!(content instanceof List<?> list)) continue;
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) continue;
                Object type = map.get("type");
                if (!Objects.equals(String.valueOf(type), "tool_result")) continue;
                Object toolUseId = map.get("tool_use_id");
                if (toolUseId != null) {
                    String id = String.valueOf(toolUseId).trim();
                    if (!id.isEmpty()) ids.add(id);
                }
            }
        }
        return ids.stream().distinct().toList();
    }

    /**
     * Extract tool_use ids emitted by the model in a non-streaming response.
     */
    private List<String> extractEmittedToolUseIds(AnthropicResponse response) {
        if (response == null || response.getContent() == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (var block : response.getContent()) {
            if (block == null) continue;
            if (!"tool_use".equals(block.getType())) continue;
            if (block.getId() != null && !block.getId().isBlank()) {
                ids.add(block.getId().trim());
            }
        }
        return ids.stream().distinct().toList();
    }

    /**
     * Add a safe, whitelisted set of request headers to the root span.
     * This helps investigate whether the client provides stable correlation IDs
     * (session/turn/request) that can be used to link multiple /messages calls.
     */
    private void addSafeHeaderAttributes(Span rootSpan, HttpServletRequest httpRequest) {
        if (rootSpan == null || httpRequest == null) {
            return;
        }

        int present = 0;
        for (String key : CORRELATION_HEADER_KEYS) {
            String value = httpRequest.getHeader(key);
            if (value == null || value.isBlank()) {
                continue;
            }
            present++;
            // Avoid huge values
            String v = value.length() > 200 ? value.substring(0, 200) + "..." : value;
            rootSpan.addAttribute("http.header." + key, v);
        }
        rootSpan.addAttribute("http.header.correlation_present_count", present);

        // Promote a best-effort request id if available (useful for debugging)
        String requestId = firstHeader(httpRequest, "x-request-id", "x-correlation-id");
        if (requestId != null) {
            rootSpan.addAttribute("request.id", requestId);
        }
        String sessionId = firstHeader(httpRequest, "x-session-id", "x-conversation-id", "x-turn-id");
        if (sessionId != null) {
            rootSpan.addAttribute("client.session_or_turn_id", sessionId);
        }
        String traceparent = httpRequest.getHeader("traceparent");
        if (traceparent != null && !traceparent.isBlank()) {
            rootSpan.addAttribute("client.traceparent", traceparent);
        }
    }

    private String firstHeader(HttpServletRequest req, String... keys) {
        for (String k : keys) {
            String v = req.getHeader(k);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }


    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "healthy"));
    }
}
