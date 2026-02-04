package com.phodal.agenttrace.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A related resource linked to a conversation.
 * 
 * @param type The type of related resource (e.g., "session", "prompt")
 * @param url URL to the related resource
 * @see <a href="https://agent-trace.dev/">Agent Trace Specification</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelatedResource(
    String type,
    String url
) {
    /**
     * Create a session reference.
     */
    public static RelatedResource session(String url) {
        return new RelatedResource("session", url);
    }

    /**
     * Create a prompt reference.
     */
    public static RelatedResource prompt(String url) {
        return new RelatedResource("prompt", url);
    }

    /**
     * Create a custom resource reference.
     */
    public static RelatedResource of(String type, String url) {
        return new RelatedResource(type, url);
    }
}
