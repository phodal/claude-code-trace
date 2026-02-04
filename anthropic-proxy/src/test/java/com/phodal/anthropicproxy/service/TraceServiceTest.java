package com.phodal.anthropicproxy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phodal.agenttrace.model.TraceRecord;
import com.phodal.anthropicproxy.model.anthropic.AnthropicMessage;
import com.phodal.anthropicproxy.model.anthropic.AnthropicRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TraceServiceTest {

    @Test
    void shouldCreateJsonlTraceFileWhenFileEditsExist(@TempDir Path workspace) throws Exception {
        TraceService traceService = new TraceService(
                new ObjectMapper(),
                new SimpleMeterRegistry(),
                workspace.toString()
        );
        traceService.init();

        AnthropicRequest request = AnthropicRequest.builder()
                .model("claude-3-sonnet-20240229")
                .maxTokens(10)
                .stream(false)
                .messages(List.of(
                        AnthropicMessage.builder()
                                .role("user")
                                .content("test")
                                .build()
                ))
                .build();

        String conversationId = traceService.startConversation("user-1", request, Map.of());

        Path editedFile = workspace.resolve("src").resolve("Example.java");
        Files.createDirectories(editedFile.getParent());
        Files.writeString(editedFile, "class Example {}\n", StandardCharsets.UTF_8);

        traceService.recordFileEdit(
                conversationId,
                editedFile.toString(),
                1,
                3,
                3,
                0,
                "write_file",
                "{\"file_path\":\"" + editedFile.toString().replace("\\", "\\\\") + "\"}"
        );

        TraceRecord record = traceService.endConversation(conversationId);
        assertNotNull(record, "TraceRecord should be created when file edits exist");
        assertEquals(1, record.fileCount());
        assertTrue(record.totalLineCount() >= 1);

        Path traceFile = workspace.resolve(".agent-trace").resolve("traces.jsonl");
        assertTrue(Files.exists(traceFile), "Trace JSONL file should be created");

        List<String> lines = Files.readAllLines(traceFile, StandardCharsets.UTF_8);
        assertEquals(1, lines.stream().filter(l -> !l.isBlank()).count(), "Should append exactly one record");
        assertTrue(lines.get(0).contains("\"files\""), "Stored JSON should contain files field");
    }
}

