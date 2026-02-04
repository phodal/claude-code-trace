package com.phodal.anthropicproxy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.*;
import com.openai.models.completions.CompletionUsage;
import com.phodal.anthropicproxy.model.anthropic.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Service using official OpenAI Java SDK for API calls
 * Records traces using Agent Trace specification
 */
@Slf4j
@Service
public class OpenAISdkService {

    // Pattern to match <think>...</think> tags (including multiline content)
    private static final Pattern THINK_TAG_PATTERN = Pattern.compile("<think>.*?</think>", Pattern.DOTALL);

    private final String baseUrl;
    private final ObjectMapper objectMapper;
    private final TraceService traceService;

    public OpenAISdkService(
            @Value("${proxy.openai.base-url}") String baseUrl,
            ObjectMapper objectMapper,
            TraceService traceService) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.traceService = traceService;
    }

    private OpenAIClient createClient(String apiKey) {
        return OpenAIOkHttpClient.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();
    }

    /**
     * Send non-streaming request using OpenAI SDK
     */
    public Mono<AnthropicResponse> sendRequest(AnthropicRequest anthropicRequest, String userId, String conversationId, String apiKey) {
        return Mono.fromCallable(() -> {
            long startTime = System.currentTimeMillis();
            OpenAIClient client = createClient(apiKey);
            ChatCompletionCreateParams params = buildChatCompletionParams(anthropicRequest);
            
            ChatCompletion completion = client.chat().completions().create(params);
            long latencyMs = System.currentTimeMillis() - startTime;
            
            // Record response metrics
            int promptTokens = 0, completionTokens = 0;
            if (completion.usage().isPresent()) {
                promptTokens = (int) completion.usage().get().promptTokens();
                completionTokens = (int) completion.usage().get().completionTokens();
            }
            traceService.recordResponse(conversationId, promptTokens, completionTokens, latencyMs);
            
            // Record tool calls
            ChatCompletionMessage message = completion.choices().get(0).message();
            message.toolCalls().ifPresent(toolCalls -> {
                for (var toolCall : toolCalls) {
                    toolCall.function().ifPresent(func -> {
                        traceService.recordToolCall(conversationId, 
                            func.function().name(), 
                            func.function().arguments());
                    });
                }
            });
            
            return convertToAnthropicResponse(completion, anthropicRequest.getModel());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Send streaming request using OpenAI SDK
     */
    public Flux<String> sendStreamingRequest(AnthropicRequest anthropicRequest, String userId, String conversationId, String apiKey) {
        return Flux.<String>create(sink -> {
            long startTime = System.currentTimeMillis();
            try {
                OpenAIClient client = createClient(apiKey);
                ChatCompletionCreateParams params = buildChatCompletionParams(anthropicRequest);
                
                String requestModel = anthropicRequest.getModel();
                String messageId = "msg_" + UUID.randomUUID().toString().replace("-", "");

                // Stream state - match Python proxy behavior
                boolean[] messageStartSent = {false};
                String[] finalStopReason = {"end_turn"};
                boolean[] shouldStopReading = {false};
                int[] textBlockIndex = {0};
                int[] toolBlockCounter = {0};
                boolean[] textBlockHasContent = {false};  // Track if text block received any content
                boolean[] textBlockClosed = {false};      // Track if text block was already closed
                // Track <think> tag state for filtering model thinking output
                boolean[] insideThinkTag = {false};
                StringBuilder[] thinkBuffer = {new StringBuilder()};
                // Track tool calls by OpenAI index, matching Python's current_tool_calls dict
                Map<Integer, ToolCallState> currentToolCalls = new HashMap<>();
                List<ToolCallInfo> collectedToolCalls = new ArrayList<>();

                try (StreamResponse<ChatCompletionChunk> stream = 
                        client.chat().completions().createStreaming(params)) {

                    var iterator = stream.stream().iterator();
                    while (iterator.hasNext()) {
                        ChatCompletionChunk chunk = iterator.next();
                        List<String> events = new ArrayList<>();

                        // Send initial events on first chunk (match Python/Anthropic behavior)
                        if (!messageStartSent[0]) {
                            messageStartSent[0] = true;
                            events.add(formatSSE(Map.of(
                                "type", "message_start",
                                "message", createMessageStartData(messageId, requestModel)
                            )));

                            // Always open an empty text block at index 0 first.
                            events.add(formatSSE(Map.of(
                                "type", "content_block_start",
                                "index", 0,
                                "content_block", Map.of("type", "text", "text", "")
                            )));
                            // Anthropic sends a ping early; some clients behave better with it.
                            events.add(formatSSE(Map.of("type", "ping")));
                        }

                        if (chunk.choices().isEmpty()) continue;
                        
                        ChatCompletionChunk.Choice choice = chunk.choices().get(0);
                        ChatCompletionChunk.Choice.Delta delta = choice.delta();

                        // Handle text content - filter out <think>...</think> tags
                        delta.content().filter(c -> !c.isEmpty()).ifPresent(content -> {
                            String filteredContent = filterThinkTags(content, insideThinkTag, thinkBuffer);
                            if (!filteredContent.isEmpty()) {
                                textBlockHasContent[0] = true;  // Mark that text block has content
                                events.add(formatSSE(Map.of(
                                    "type", "content_block_delta",
                                    "index", textBlockIndex[0],
                                    "delta", Map.of("type", "text_delta", "text", filteredContent)
                                )));
                            }
                        });

                        // Handle tool calls - match Python behavior exactly
                        // Python checks: if "tool_calls" in delta and delta["tool_calls"]:
                        delta.toolCalls().filter(tc -> !tc.isEmpty()).ifPresent(toolCalls -> {
                            for (var toolCall : toolCalls) {
                                int tcIndex = (int) toolCall.index();
                                
                                // Initialize tool call tracking by index if not exists (Python: current_tool_calls dict)
                                if (!currentToolCalls.containsKey(tcIndex)) {
                                    currentToolCalls.put(tcIndex, new ToolCallState());
                                }
                                
                                ToolCallState tcState = currentToolCalls.get(tcIndex);
                                
                                // Update tool call ID if provided
                                toolCall.id().ifPresent(id -> tcState.id = id);
                                
                                // Update function name if provided
                                toolCall.function()
                                    .flatMap(ChatCompletionChunk.Choice.Delta.ToolCall.Function::name)
                                    .ifPresent(name -> tcState.name = name);
                                
                                // Start content block when we have complete initial data (id and name)
                                // This matches Python: if (tool_call["id"] and tool_call["name"] and not tool_call["started"])
                                if (tcState.id != null && tcState.name != null && !tcState.started) {
                                    // Before starting the first tool_use block, close the text block if it's empty
                                    // This prevents Claude Code CLI from rendering an empty step line
                                    if (!textBlockClosed[0] && toolBlockCounter[0] == 0) {
                                        // If text block had no content, send a minimal NBSP to fill it
                                        // (matches mock stream behavior to avoid empty line rendering)
                                        if (!textBlockHasContent[0]) {
                                            events.add(formatSSE(Map.of(
                                                "type", "content_block_delta",
                                                "index", textBlockIndex[0],
                                                "delta", Map.of("type", "text_delta", "text", "\u00A0")
                                            )));
                                        }
                                        // Close the text block before starting tool_use
                                        events.add(formatSSE(Map.of(
                                            "type", "content_block_stop",
                                            "index", textBlockIndex[0]
                                        )));
                                        textBlockClosed[0] = true;
                                    }

                                    toolBlockCounter[0]++;
                                    int claudeIndex = textBlockIndex[0] + toolBlockCounter[0];
                                    tcState.claudeIndex = claudeIndex;
                                    tcState.started = true;

                                    events.add(formatSSE(Map.of(
                                        "type", "content_block_start",
                                        "index", claudeIndex,
                                        "content_block", Map.of(
                                            "type", "tool_use",
                                            "id", tcState.id,
                                            "name", tcState.name,
                                            "input", Map.of()
                                        )
                                    )));
                                }
                                
                                // Handle function arguments - match Python: accumulate and only send when JSON is complete
                                toolCall.function().flatMap(f -> f.arguments()).ifPresent(args -> {
                                    if (tcState.started && args != null) {
                                        tcState.argsBuffer.append(args);
                                        
                                        // Try to parse complete JSON and send delta when we have valid JSON
                                        // This matches Python behavior exactly
                                        try {
                                            objectMapper.readTree(tcState.argsBuffer.toString());
                                            // If parsing succeeds and we haven't sent this JSON yet
                                            if (!tcState.jsonSent) {
                                                events.add(formatSSE(Map.of(
                                                    "type", "content_block_delta",
                                                    "index", tcState.claudeIndex,
                                                    "delta", Map.of("type", "input_json_delta", "partial_json", tcState.argsBuffer.toString())
                                                )));
                                                tcState.jsonSent = true;
                                            }
                                        } catch (Exception e) {
                                            // JSON is incomplete, continue accumulating
                                        }
                                    }
                                });
                            }
                        });

                        // Handle finish reason - match Python behavior
                        choice.finishReason().ifPresent(reason -> {
                            // Map OpenAI finish reason to Anthropic stop_reason
                            String r = reason.toString().toLowerCase();
                            if (r.contains("length")) {
                                finalStopReason[0] = "max_tokens";
                            } else if (r.contains("tool")) {
                                finalStopReason[0] = "tool_use";
                            } else if (r.contains("stop")) {
                                finalStopReason[0] = "end_turn";
                            } else {
                                finalStopReason[0] = "end_turn";
                            }
                            // Important: stop reading upstream stream immediately.
                            shouldStopReading[0] = true;
                        });

                        if (!events.isEmpty()) {
                            String joined = String.join("", events);
                            if (log.isDebugEnabled()) {
                                log.debug("Sending SSE events: {}", joined.replace("\n", "\\n"));
                            }
                            sink.next(joined);
                        }
                        if (shouldStopReading[0]) {
                            break;
                        }
                    }

                    // Final events - match Python behavior exactly
                    StringBuilder finalOut = new StringBuilder();

                    // Close text block first if not already closed (Python: content_block_stop for text_block_index)
                    if (!textBlockClosed[0]) {
                        finalOut.append(formatSSE(Map.of(
                            "type", "content_block_stop",
                            "index", textBlockIndex[0]
                        )));
                    }

                    // Close all tool blocks and collect tool call info
                    for (ToolCallState tcState : currentToolCalls.values()) {
                        if (tcState.started && tcState.claudeIndex != null) {
                            finalOut.append(formatSSE(Map.of(
                                "type", "content_block_stop",
                                "index", tcState.claudeIndex
                            )));
                            collectedToolCalls.add(new ToolCallInfo(
                                tcState.id, tcState.name, tcState.argsBuffer.toString()));
                        }
                    }
                    
                    Map<String, Object> deltaContent = new HashMap<>();
                    deltaContent.put("stop_reason", finalStopReason[0]);
                    deltaContent.put("stop_sequence", null);
                    
                    finalOut.append(formatSSE(Map.of(
                        "type", "message_delta",
                        "delta", deltaContent,
                        "usage", Map.of("input_tokens", 0, "output_tokens", 0)
                    )));
                    finalOut.append(formatSSE(Map.of("type", "message_stop")));
                    // Note: Python version does NOT send "data: [DONE]" - removed to match

                    if (!finalOut.isEmpty()) {
                        sink.next(finalOut.toString());
                    }
                    
                    long latencyMs = System.currentTimeMillis() - startTime;
                    traceService.recordStreamingToolCalls(userId, conversationId, collectedToolCalls, latencyMs);
                    sink.complete();
                }
            } catch (Exception e) {
                log.error("Error during streaming: {}", e.getMessage(), e);
                sink.error(e);
            }
        }, reactor.core.publisher.FluxSink.OverflowStrategy.BUFFER)
        .subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> createMessageStartData(String messageId, String model) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", messageId);
        data.put("type", "message");
        data.put("role", "assistant");
        data.put("content", List.of());
        data.put("model", model != null ? model : "unknown");
        data.put("stop_reason", null);
        data.put("stop_sequence", null);
        // Match Python proxy / Anthropic shape used by Claude Code
        data.put("usage", Map.of(
            "input_tokens", 0,
            "cache_creation_input_tokens", 0,
            "cache_read_input_tokens", 0,
            "output_tokens", 0
        ));
        return data;
    }

    private ChatCompletionCreateParams buildChatCompletionParams(AnthropicRequest request) {
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(request.getModel());

        // System message
        String systemText = extractSystemText(request.getSystem());
        if (systemText != null && !systemText.isEmpty()) {
            builder.addSystemMessage(systemText);
        }

        // Messages
        if (request.getMessages() != null) {
            for (AnthropicMessage msg : request.getMessages()) {
                addMessage(builder, msg);
            }
        }

        // Optional parameters
        if (request.getMaxTokens() != null) {
            builder.maxTokens(request.getMaxTokens().longValue());
        }
        if (request.getTemperature() != null) {
            builder.temperature(request.getTemperature());
        }
        if (request.getTopP() != null) {
            builder.topP(request.getTopP());
        }
        if (request.getStopSequences() != null && !request.getStopSequences().isEmpty()) {
            builder.stop(ChatCompletionCreateParams.Stop.ofStrings(request.getStopSequences()));
        }

        // Tools
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            for (AnthropicTool tool : request.getTools()) {
                builder.addTool(ChatCompletionTool.ofFunction(
                    ChatCompletionFunctionTool.builder()
                        .function(FunctionDefinition.builder()
                            .name(tool.getName())
                            .description(tool.getDescription() != null ? tool.getDescription() : "")
                            .parameters(FunctionParameters.builder()
                                .putAllAdditionalProperties(tool.getInputSchema() != null ? 
                                    convertToJsonValueMap(tool.getInputSchema()) : Map.of())
                                .build())
                            .build())
                        .build()
                ));
            }
        }

        return builder.build();
    }

    private void addMessage(ChatCompletionCreateParams.Builder builder, AnthropicMessage msg) {
        Object content = msg.getContent();
        String role = msg.getRole();

        if (content instanceof String text) {
            if ("user".equals(role)) {
                builder.addUserMessage(text);
            } else if ("assistant".equals(role)) {
                builder.addAssistantMessage(text);
            }
        } else if (content instanceof List<?> contentList) {
            processContentList(builder, role, contentList);
        }
    }

    @SuppressWarnings("unchecked")
    private void processContentList(ChatCompletionCreateParams.Builder builder, String role, List<?> contentList) {
        StringBuilder textContent = new StringBuilder();
        List<ChatCompletionMessageToolCall> toolCalls = new ArrayList<>();

        for (Object item : contentList) {
            if (!(item instanceof Map)) continue;
            Map<String, Object> itemMap = (Map<String, Object>) item;
            String type = (String) itemMap.get("type");

            switch (type) {
                case "text" -> textContent.append(itemMap.get("text"));
                case "tool_use" -> {
                    String arguments;
                    try {
                        arguments = objectMapper.writeValueAsString(itemMap.get("input"));
                    } catch (JsonProcessingException e) {
                        arguments = "{}";
                    }
                    toolCalls.add(ChatCompletionMessageToolCall.ofFunction(
                        ChatCompletionMessageFunctionToolCall.builder()
                            .id((String) itemMap.get("id"))
                            .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                .name((String) itemMap.get("name"))
                                .arguments(arguments)
                                .build())
                            .build()
                    ));
                }
                case "tool_result" -> {
                    Object resultContent = itemMap.get("content");
                    String toolResultText = resultContent instanceof String ? 
                        (String) resultContent : serializeToJson(resultContent);
                    builder.addMessage(ChatCompletionToolMessageParam.builder()
                        .toolCallId((String) itemMap.get("tool_use_id"))
                        .content(toolResultText)
                        .build());
                }
            }
        }

        if (!textContent.isEmpty() || !toolCalls.isEmpty()) {
            if ("user".equals(role)) {
                builder.addUserMessage(textContent.toString());
            } else if ("assistant".equals(role)) {
                if (toolCalls.isEmpty()) {
                    builder.addAssistantMessage(textContent.toString());
                } else {
                    builder.addMessage(ChatCompletionAssistantMessageParam.builder()
                        .content(textContent.toString())
                        .toolCalls(toolCalls)
                        .build());
                }
            }
        }
    }

    private AnthropicResponse convertToAnthropicResponse(ChatCompletion completion, String requestModel) {
        if (completion.choices().isEmpty()) return null;

        ChatCompletion.Choice choice = completion.choices().get(0);
        ChatCompletionMessage message = choice.message();
        List<AnthropicContent> content = new ArrayList<>();

        // Text content
        message.content().filter(c -> !c.isEmpty()).ifPresent(text ->
            content.add(AnthropicContent.builder().type("text").text(text).build())
        );

        // Tool calls
        message.toolCalls().ifPresent(toolCalls -> {
            for (var toolCall : toolCalls) {
                toolCall.function().ifPresent(func -> {
                    Object input;
                    try {
                        input = objectMapper.readValue(func.function().arguments(), Map.class);
                    } catch (JsonProcessingException e) {
                        input = Map.of();
                    }
                    content.add(AnthropicContent.builder()
                        .type("tool_use")
                        .id(func.id())
                        .name(func.function().name())
                        .input(input)
                        .build());
                });
            }
        });

        AnthropicUsage usage = completion.usage().map(u -> 
            AnthropicUsage.builder()
                .inputTokens((int) u.promptTokens())
                .outputTokens((int) u.completionTokens())
                .build()
        ).orElse(null);

        return AnthropicResponse.builder()
            .id(completion.id())
            .type("message")
            .role("assistant")
            .content(content)
            .model(requestModel)
            .stopReason(convertFinishReason(choice.finishReason()))
            .usage(usage)
            .build();
    }

    private String convertFinishReason(ChatCompletion.Choice.FinishReason reason) {
        if (reason == null) return "end_turn";
        String str = reason.toString().toLowerCase();
        if (str.contains("stop")) return "end_turn";
        if (str.contains("length")) return "max_tokens";
        if (str.contains("tool")) return "tool_use";
        return "end_turn";
    }

    private Map<String, com.openai.core.JsonValue> convertToJsonValueMap(Map<String, Object> map) {
        Map<String, com.openai.core.JsonValue> result = new HashMap<>();
        for (var entry : map.entrySet()) {
            result.put(entry.getKey(), com.openai.core.JsonValue.from(entry.getValue()));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private String extractSystemText(Object system) {
        if (system == null) return null;
        if (system instanceof String s) return s;
        if (system instanceof List<?> blocks) {
            StringBuilder sb = new StringBuilder();
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> map) {
                    Object text = ((Map<String, Object>) map).get("text");
                    if (text != null) {
                        if (!sb.isEmpty()) sb.append("\n");
                        sb.append(text);
                    }
                }
            }
            return sb.isEmpty() ? null : sb.toString();
        }
        return system.toString();
    }

    private String formatSSE(Map<String, Object> data) {
        try {
            return "event: " + data.get("type") + "\ndata: " + objectMapper.writeValueAsString(data) + "\n\n";
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize SSE: {}", e.getMessage());
            return "";
        }
    }

    private String serializeToJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    public record ToolCallInfo(String id, String name, String arguments) {}

    /**
     * Filter out <think>...</think> tags from streaming content.
     * Handles tags that may span multiple chunks.
     *
     * @param content The incoming content chunk
     * @param insideThinkTag Mutable state tracking if we're inside a think tag
     * @param buffer Buffer for accumulating partial tags
     * @return Filtered content with think tags removed
     */
    private String filterThinkTags(String content, boolean[] insideThinkTag, StringBuilder[] buffer) {
        StringBuilder result = new StringBuilder();
        buffer[0].append(content);
        String text = buffer[0].toString();

        int i = 0;
        while (i < text.length()) {
            if (insideThinkTag[0]) {
                // Look for closing </think> tag
                int closeIdx = text.indexOf("</think>", i);
                if (closeIdx != -1) {
                    // Found closing tag, skip everything up to and including it
                    i = closeIdx + "</think>".length();
                    // Also skip any leading newlines after </think>
                    while (i < text.length() && (text.charAt(i) == '\n' || text.charAt(i) == '\r')) {
                        i++;
                    }
                    insideThinkTag[0] = false;
                } else {
                    // No closing tag yet, might be partial - keep buffering
                    // Check if we have a partial </think> at the end
                    String remaining = text.substring(i);
                    if ("</think>".startsWith(remaining) || remaining.contains("<")) {
                        // Partial tag, keep in buffer
                        buffer[0] = new StringBuilder(remaining);
                        return result.toString();
                    }
                    // No partial tag, discard (still inside think)
                    buffer[0] = new StringBuilder();
                    return result.toString();
                }
            } else {
                // Look for opening <think> tag
                int openIdx = text.indexOf("<think>", i);
                if (openIdx != -1) {
                    // Output everything before the tag
                    result.append(text, i, openIdx);
                    i = openIdx + "<think>".length();
                    insideThinkTag[0] = true;
                } else {
                    // Check for partial <think> at the end
                    String remaining = text.substring(i);
                    int partialIdx = -1;
                    for (int j = 1; j < "<think>".length() && j <= remaining.length(); j++) {
                        if ("<think>".startsWith(remaining.substring(remaining.length() - j))) {
                            partialIdx = remaining.length() - j;
                            break;
                        }
                    }
                    if (partialIdx != -1) {
                        // Partial tag at end, output up to it and keep rest in buffer
                        result.append(remaining, 0, partialIdx);
                        buffer[0] = new StringBuilder(remaining.substring(partialIdx));
                        return result.toString();
                    }
                    // No partial tag, output everything
                    result.append(remaining);
                    buffer[0] = new StringBuilder();
                    return result.toString();
                }
            }
        }

        buffer[0] = new StringBuilder();
        return result.toString();
    }

    /**
     * Mutable state for tracking tool call accumulation during streaming.
     * Matches Python's current_tool_calls dict structure.
     */
    private static class ToolCallState {
        String id = null;
        String name = null;
        StringBuilder argsBuffer = new StringBuilder();
        boolean jsonSent = false;
        Integer claudeIndex = null;
        boolean started = false;
    }
}
