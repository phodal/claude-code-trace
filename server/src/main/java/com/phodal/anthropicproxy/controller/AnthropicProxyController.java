package com.phodal.anthropicproxy.controller;

import com.phodal.anthropicproxy.model.anthropic.AnthropicRequest;
import com.phodal.anthropicproxy.model.anthropic.AnthropicResponse;
import com.phodal.anthropicproxy.otel.model.Span;
import com.phodal.anthropicproxy.otel.model.SpanKind;
import com.phodal.anthropicproxy.otel.model.SpanStatus;
import com.phodal.anthropicproxy.otel.model.Trace;
import com.phodal.anthropicproxy.otel.service.ExporterService;
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
import java.util.Map;

/**
 * Controller to handle Anthropic API proxy requests
 * Uses official OpenAI Java SDK for API calls
 * Records traces using Agent Trace specification
 */
@Slf4j
@RestController
@RequestMapping("/anthropic")
@RequiredArgsConstructor
public class AnthropicProxyController {

    private final OpenAISdkService sdkService;
    private final TraceService traceService;
    private final UserIdentificationService userIdentificationService;
    private final com.phodal.anthropicproxy.otel.service.TraceService otelTraceService;
    private final ExporterService exporterService;

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

        log.info("Received request from user: {}, model: {}, stream: {}, traceId: {}", 
                userId, request.getModel(), request.getStream(), traceId);

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

                        // Always end spans and complete/export the OTEL trace to avoid leaks.
                        otelTraceService.endSpan(streamSpan, streamSpan.getStatus());
                        otelTraceService.endSpan(rootSpan, rootSpan.getStatus());
                        otelTraceService.completeTrace(traceId);
                        exporterService.exportTrace(trace);

                        // Ensure conversation is ended on non-complete signals (errors/cancel).
                        if (signalType != SignalType.ON_COMPLETE) {
                            traceService.endConversation(conversationId);
                        }
                    })
                    .blockLast();
        } catch (Exception e) {
            log.error("Error handling streaming request: {}", e.getMessage(), e);
        }
    }


    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "healthy"));
    }
}
