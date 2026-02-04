package com.phodal.agenttrace.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Information about the tool that generated a trace.
 * 
 * @param name The tool name (e.g., "cursor", "claude-code")
 * @param version The tool version (e.g., "2.4.0")
 * @see <a href="https://agent-trace.dev/">Agent Trace Specification</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Tool(
    String name,
    String version
) {
    /**
     * Create a tool reference with name only.
     */
    public static Tool of(String name) {
        return new Tool(name, null);
    }

    /**
     * Create a tool reference with name and version.
     */
    public static Tool of(String name, String version) {
        return new Tool(name, version);
    }
}
