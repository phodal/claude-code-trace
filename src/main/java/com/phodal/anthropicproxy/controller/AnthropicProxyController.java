package com.phodal.anthropicproxy.controller;

import com.phodal.anthropicproxy.model.anthropic.AnthropicRequest;
import com.phodal.anthropicproxy.model.anthropic.AnthropicResponse;
import com.phodal.anthropicproxy.otel.model.Span;
import com.phodal.anthropicproxy.otel.model.SpanKind;
import com.phodal.anthropicproxy.otel.model.SpanStatus;
import com.phodal.anthropicproxy.otel.model.Trace;
import com.phodal.anthropicproxy.otel.service.ExporterService;
import com.phodal.anthropicproxy.otel.service.TraceService;
import com.phodal.anthropicproxy.service.MetricsService;
import com.phodal.anthropicproxy.service.OpenAISdkService;
import com.phodal.anthropicproxy.service.UserIdentificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;

/**
 * Controller to handle Anthropic API proxy requests
 * Uses official OpenAI Java SDK for API calls
 */
@Slf4j
@RestController
@RequestMapping("/anthropic")
@RequiredArgsConstructor
public class AnthropicProxyController {

    private final OpenAISdkService sdkService;
    private final MetricsService metricsService;
    private final UserIdentificationService userIdentificationService;
    private final TraceService traceService;
    private final ExporterService exporterService;

    /**
     * Handle Anthropic Messages API requests
     */
    @PostMapping(value = "/v1/messages")
    public ResponseEntity<?> handleMessages(
            @RequestBody AnthropicRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) throws IOException {

        // Start OTEL trace
        String traceId = traceService.generateTraceId();
        Trace trace = traceService.startTrace(traceId);
        
        // Create root span for the request
        Span rootSpan = traceService.startSpan(traceId, "anthropic.messages", SpanKind.SERVER, null);
        rootSpan.addAttribute("http.method", "POST");
        rootSpan.addAttribute("http.route", "/anthropic/v1/messages");
        rootSpan.addAttribute("model", request.getModel());
        rootSpan.addAttribute("stream", request.getStream() != null ? request.getStream() : false);

        String userId = userIdentificationService.identifyUser(httpRequest);
        String apiKey = userIdentificationService.extractApiKey(httpRequest);
        Map<String, String> headers = userIdentificationService.collectHeaders(httpRequest);
        
        rootSpan.addAttribute("user.id", userId);

        log.info("Received request from user: {}, model: {}, stream: {}, traceId: {}", 
                userId, request.getModel(), request.getStream(), traceId);

        // Record the request and get turnId
        String turnId = metricsService.recordRequest(userId, request, headers);
        rootSpan.addAttribute("turn.id", turnId);

        if (apiKey == null || apiKey.isEmpty()) {
            log.error("No API key provided");
            rootSpan.setStatus(SpanStatus.error("No API key provided"));
            traceService.endSpan(rootSpan, rootSpan.getStatus());
            traceService.completeTrace(traceId);
            exporterService.exportTrace(trace);
            
            return ResponseEntity.status(401).body(Map.of(
                    "type", "error",
                    "error", Map.of(
                            "type", "authentication_error",
                            "message", "No API key provided"
                    )
            ));
        }

        // Handle streaming vs non-streaming
        try {
            if (Boolean.TRUE.equals(request.getStream())) {
                handleStreamingRequest(request, userId, turnId, apiKey, httpResponse, traceId, rootSpan);
                return null;
            } else {
                return handleNonStreamingRequest(request, userId, turnId, apiKey, traceId, rootSpan);
            }
        } catch (Exception e) {
            rootSpan.setStatus(SpanStatus.error(e.getMessage()));
            traceService.endSpan(rootSpan, rootSpan.getStatus());
            traceService.completeTrace(traceId);
            exporterService.exportTrace(trace);
            throw e;
        }
    }

    /**
     * Handle non-streaming request
     */
    private ResponseEntity<?> handleNonStreamingRequest(
            AnthropicRequest request, String userId, String turnId, String apiKey,
            String traceId, Span rootSpan) {
        
        // Create span for API call
        Span apiSpan = traceService.startSpan(traceId, "anthropic.api.call", SpanKind.CLIENT, rootSpan.getSpanId());
        apiSpan.addAttribute("api.endpoint", "messages");
        apiSpan.addAttribute("api.model", request.getModel());
        
        try {
            AnthropicResponse response = sdkService.sendRequest(request, userId, turnId, apiKey).block();
            
            // Add response attributes
            apiSpan.addAttribute("response.id", response != null ? response.getId() : "unknown");
            if (response != null && response.getUsage() != null) {
                apiSpan.addAttribute("tokens.input", response.getUsage().getInputTokens());
                apiSpan.addAttribute("tokens.output", response.getUsage().getOutputTokens());
            }
            
            apiSpan.setStatus(SpanStatus.ok());
            traceService.endSpan(apiSpan, apiSpan.getStatus());
            
            rootSpan.setStatus(SpanStatus.ok());
            traceService.endSpan(rootSpan, rootSpan.getStatus());
            
            // Complete trace and export
            traceService.completeTrace(traceId);
            exporterService.exportTrace(traceService.getTrace(traceId));
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error handling non-streaming request: {}", e.getMessage(), e);
            
            apiSpan.setStatus(SpanStatus.error(e.getMessage()));
            traceService.endSpan(apiSpan, apiSpan.getStatus());
            
            rootSpan.setStatus(SpanStatus.error(e.getMessage()));
            traceService.endSpan(rootSpan, rootSpan.getStatus());
            
            // Complete trace and export even on error
            traceService.completeTrace(traceId);
            exporterService.exportTrace(traceService.getTrace(traceId));
            
            return ResponseEntity.internalServerError().body(Map.of(
                    "type", "error",
                    "error", Map.of(
                            "type", "api_error",
                            "message", e.getMessage() != null ? e.getMessage() : "Unknown error"
                    )
            ));
        }
    }

    /**
     * Handle streaming request - writes directly to response
     */
    private void handleStreamingRequest(
            AnthropicRequest request, String userId, String turnId, String apiKey,
            HttpServletResponse httpResponse, String traceId, Span rootSpan) throws IOException {

        // Create span for streaming API call
        Span streamSpan = traceService.startSpan(traceId, "anthropic.api.stream", SpanKind.CLIENT, rootSpan.getSpanId());
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
            sdkService.sendStreamingRequest(request, userId, turnId, apiKey)
                    .doOnNext(chunk -> {
                        writer.print(chunk);
                        writer.flush();
                    })
                    .doOnError(e -> {
                        log.error("Error in streaming: {}", e.getMessage());
                        writer.print("event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}}\n\n");
                        writer.flush();
                        
                        streamSpan.setStatus(SpanStatus.error(e.getMessage()));
                        rootSpan.setStatus(SpanStatus.error(e.getMessage()));
                    })
                    .doOnComplete(() -> {
                        streamSpan.setStatus(SpanStatus.ok());
                        traceService.endSpan(streamSpan, streamSpan.getStatus());
                        
                        rootSpan.setStatus(SpanStatus.ok());
                        traceService.endSpan(rootSpan, rootSpan.getStatus());
                        
                        // Complete trace and export
                        traceService.completeTrace(traceId);
                        exporterService.exportTrace(traceService.getTrace(traceId));
                    })
                    .blockLast();
        } catch (Exception e) {
            log.error("Error handling streaming request: {}", e.getMessage(), e);
            writer.print("event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"" + (e.getMessage() != null ? e.getMessage().replace("\"", "\\\"") : "Unknown error") + "\"}}\n\n");
            writer.flush();
            
            streamSpan.setStatus(SpanStatus.error(e.getMessage()));
            traceService.endSpan(streamSpan, streamSpan.getStatus());
            
            rootSpan.setStatus(SpanStatus.error(e.getMessage()));
            traceService.endSpan(rootSpan, rootSpan.getStatus());
            
            // Complete trace and export even on error
            traceService.completeTrace(traceId);
            exporterService.exportTrace(traceService.getTrace(traceId));
        }
    }


    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "healthy"));
    }
}
