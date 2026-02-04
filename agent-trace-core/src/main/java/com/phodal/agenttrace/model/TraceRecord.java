package com.phodal.agenttrace.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.*;

/**
 * The fundamental unit of Agent Trace - a trace record.
 * Records attribution data for AI-generated code.
 * 
 * @param version Agent Trace specification version (e.g., "0.1.0")
 * @param id Unique identifier for this trace record
 * @param timestamp RFC 3339 timestamp when trace was recorded
 * @param vcs Version control system information
 * @param tool The tool that generated this trace
 * @param files Array of files with attributed ranges
 * @param metadata Additional metadata for implementation-specific data
 * @see <a href="https://agent-trace.dev/">Agent Trace Specification</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TraceRecord(
    String version,
    UUID id,
    Instant timestamp,
    Vcs vcs,
    Tool tool,
    List<FileEntry> files,
    Map<String, Object> metadata
) {
    /**
     * Current Agent Trace specification version.
     */
    public static final String CURRENT_VERSION = "0.1.0";

    /**
     * Builder for creating trace records.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Create a minimal trace record.
     */
    public static TraceRecord minimal(List<FileEntry> files) {
        return new TraceRecord(
            CURRENT_VERSION,
            UUID.randomUUID(),
            Instant.now(),
            null,
            null,
            files,
            null
        );
    }

    public static class Builder {
        private String version = CURRENT_VERSION;
        private UUID id = UUID.randomUUID();
        private Instant timestamp = Instant.now();
        private Vcs vcs;
        private Tool tool;
        private List<FileEntry> files = new ArrayList<>();
        private Map<String, Object> metadata;

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder vcs(Vcs vcs) {
            this.vcs = vcs;
            return this;
        }

        public Builder gitRevision(String commitSha) {
            this.vcs = Vcs.git(commitSha);
            return this;
        }

        public Builder tool(Tool tool) {
            this.tool = tool;
            return this;
        }

        public Builder tool(String name, String version) {
            this.tool = Tool.of(name, version);
            return this;
        }

        public Builder addFile(FileEntry file) {
            this.files.add(file);
            return this;
        }

        public Builder files(List<FileEntry> files) {
            this.files = new ArrayList<>(files);
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder addMetadata(String key, Object value) {
            if (this.metadata == null) {
                this.metadata = new HashMap<>();
            }
            this.metadata.put(key, value);
            return this;
        }

        public TraceRecord build() {
            if (files.isEmpty()) {
                throw new IllegalStateException("TraceRecord must have at least one file entry");
            }
            return new TraceRecord(version, id, timestamp, vcs, tool, files, metadata);
        }
    }

    /**
     * Get total number of files in this trace.
     */
    public int fileCount() {
        return files.size();
    }

    /**
     * Get total line count across all files.
     */
    public int totalLineCount() {
        return files.stream()
            .mapToInt(FileEntry::totalLineCount)
            .sum();
    }

    /**
     * Get all unique model IDs used in this trace.
     */
    public Set<String> getModelIds() {
        Set<String> modelIds = new HashSet<>();
        for (FileEntry file : files) {
            for (Conversation conv : file.conversations()) {
                if (conv.contributor() != null && conv.contributor().modelId() != null) {
                    modelIds.add(conv.contributor().modelId());
                }
                for (Range range : conv.ranges()) {
                    if (range.contributor() != null && range.contributor().modelId() != null) {
                        modelIds.add(range.contributor().modelId());
                    }
                }
            }
        }
        return modelIds;
    }
}
