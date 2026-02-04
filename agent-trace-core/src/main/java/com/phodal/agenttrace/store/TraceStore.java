package com.phodal.agenttrace.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.phodal.agenttrace.model.TraceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Storage layer for Agent Trace records.
 * Stores traces in JSONL format (one JSON object per line).
 * 
 * <p>Default storage path: {@code .agent-trace/traces.jsonl}</p>
 * 
 * @see <a href="https://agent-trace.dev/">Agent Trace Specification</a>
 */
public class TraceStore {
    private static final Logger log = LoggerFactory.getLogger(TraceStore.class);
    
    public static final String DEFAULT_TRACE_DIR = ".agent-trace";
    public static final String DEFAULT_TRACE_FILE = "traces.jsonl";
    
    private final Path tracePath;
    private final ObjectMapper objectMapper;
    private final Object writeLock = new Object();

    /**
     * Create a TraceStore with default path in the workspace root.
     */
    public TraceStore(Path workspacePath) {
        this.tracePath = workspacePath.resolve(DEFAULT_TRACE_DIR).resolve(DEFAULT_TRACE_FILE);
        this.objectMapper = createObjectMapper();
    }

    /**
     * Create a TraceStore with a custom trace file path.
     * 
     * @param tracePath The absolute path to the trace file
     * @param isAbsolute Flag to indicate this is an absolute path (not workspace-relative)
     */
    public TraceStore(Path tracePath, boolean isAbsolute) {
        this.tracePath = tracePath;
        this.objectMapper = createObjectMapper();
    }

    private ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(SerializationFeature.INDENT_OUTPUT);
        return mapper;
    }

    /**
     * Get the trace file path.
     */
    public Path getTracePath() {
        return tracePath;
    }

    /**
     * Ensure the trace directory exists.
     */
    private void ensureDirectoryExists() throws IOException {
        Path dir = tracePath.getParent();
        if (dir != null && !Files.exists(dir)) {
            Files.createDirectories(dir);
            log.info("Created trace directory: {}", dir);
        }
    }

    /**
     * Append a trace record to the store.
     * Thread-safe operation.
     */
    public void appendTrace(TraceRecord record) throws IOException {
        synchronized (writeLock) {
            ensureDirectoryExists();
            String json = objectMapper.writeValueAsString(record);
            Files.writeString(
                tracePath,
                json + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
            log.debug("Appended trace record: {}", record.id());
        }
    }

    /**
     * Read all trace records from the store.
     */
    public List<TraceRecord> readAllTraces() throws IOException {
        if (!Files.exists(tracePath)) {
            return new ArrayList<>();
        }
        
        List<TraceRecord> records = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(tracePath, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                try {
                    TraceRecord record = objectMapper.readValue(line, TraceRecord.class);
                    records.add(record);
                } catch (Exception e) {
                    log.warn("Failed to parse trace record at line {}: {}", lineNumber, e.getMessage());
                }
            }
        }
        return records;
    }

    /**
     * Read traces with a filter predicate.
     */
    public List<TraceRecord> readTraces(Predicate<TraceRecord> filter) throws IOException {
        return readAllTraces().stream()
            .filter(filter)
            .toList();
    }

    /**
     * Read traces that contain modifications to a specific file.
     */
    public List<TraceRecord> readTracesByFile(String filePath) throws IOException {
        return readTraces(record -> 
            record.files().stream()
                .anyMatch(f -> f.path().equals(filePath))
        );
    }

    /**
     * Read traces within a time range.
     */
    public List<TraceRecord> readTracesByTimeRange(Instant from, Instant to) throws IOException {
        return readTraces(record -> {
            Instant ts = record.timestamp();
            return !ts.isBefore(from) && !ts.isAfter(to);
        });
    }

    /**
     * Read traces by model ID.
     */
    public List<TraceRecord> readTracesByModel(String modelId) throws IOException {
        return readTraces(record -> record.getModelIds().contains(modelId));
    }

    /**
     * Read the most recent traces.
     */
    public List<TraceRecord> readRecentTraces(int limit) throws IOException {
        List<TraceRecord> all = readAllTraces();
        int start = Math.max(0, all.size() - limit);
        return all.subList(start, all.size());
    }

    /**
     * Find a trace by ID.
     */
    public Optional<TraceRecord> findById(UUID id) throws IOException {
        return readTraces(record -> record.id().equals(id))
            .stream()
            .findFirst();
    }

    /**
     * Get the count of traces in the store.
     */
    public long getTraceCount() throws IOException {
        if (!Files.exists(tracePath)) {
            return 0;
        }
        try (Stream<String> lines = Files.lines(tracePath, StandardCharsets.UTF_8)) {
            return lines.filter(line -> !line.isBlank()).count();
        }
    }

    /**
     * Check if the trace store exists and has data.
     */
    public boolean hasTraces() {
        return Files.exists(tracePath) && tracePath.toFile().length() > 0;
    }

    /**
     * Clear all traces (use with caution).
     */
    public void clear() throws IOException {
        synchronized (writeLock) {
            if (Files.exists(tracePath)) {
                Files.delete(tracePath);
                log.info("Cleared trace store: {}", tracePath);
            }
        }
    }

    /**
     * Create a TraceStore for a workspace path.
     */
    public static TraceStore forWorkspace(Path workspacePath) {
        return new TraceStore(workspacePath);
    }

    /**
     * Create a TraceStore for a specific file path.
     */
    public static TraceStore forFile(Path filePath) {
        return new TraceStore(filePath, true);
    }
}
