package com.phodal.agenttrace.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Information about who/what contributed to the code.
 * 
 * @param type The contributor type (human, ai, mixed, unknown)
 * @param modelId The model's unique identifier following models.dev convention
 *                (e.g., "anthropic/claude-opus-4-5-20251101")
 * @see <a href="https://agent-trace.dev/">Agent Trace Specification</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Contributor(
    ContributorType type,
    @JsonProperty("model_id") String modelId
) {
    /**
     * Create an AI contributor with a model ID.
     */
    public static Contributor ai(String modelId) {
        return new Contributor(ContributorType.AI, modelId);
    }

    /**
     * Create a human contributor.
     */
    public static Contributor human() {
        return new Contributor(ContributorType.HUMAN, null);
    }

    /**
     * Create an unknown contributor.
     */
    public static Contributor unknown() {
        return new Contributor(ContributorType.UNKNOWN, null);
    }

    /**
     * Create a mixed contributor (human + AI collaboration).
     */
    public static Contributor mixed(String modelId) {
        return new Contributor(ContributorType.MIXED, modelId);
    }
}
