package com.phodal.agenttrace.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A line range in a file that was modified.
 * Line numbers are 1-indexed and reference positions at the recorded revision.
 * 
 * @param startLine The starting line number (1-indexed)
 * @param endLine The ending line number (1-indexed, inclusive)
 * @param contentHash Hash of attributed content for position-independent tracking
 * @param contributor Override contributor for this specific range (e.g., for agent handoffs)
 * @see <a href="https://agent-trace.dev/">Agent Trace Specification</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Range(
    @JsonProperty("start_line") int startLine,
    @JsonProperty("end_line") int endLine,
    @JsonProperty("content_hash") String contentHash,
    Contributor contributor
) {
    /**
     * Create a range with only line numbers.
     */
    public static Range of(int startLine, int endLine) {
        return new Range(startLine, endLine, null, null);
    }

    /**
     * Create a range with line numbers and content hash.
     */
    public static Range withHash(int startLine, int endLine, String contentHash) {
        return new Range(startLine, endLine, contentHash, null);
    }

    /**
     * Create a range with an overridden contributor.
     */
    public static Range withContributor(int startLine, int endLine, Contributor contributor) {
        return new Range(startLine, endLine, null, contributor);
    }

    /**
     * Get the number of lines in this range.
     */
    public int lineCount() {
        return endLine - startLine + 1;
    }
}
