package com.phodal.agenttrace.util;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Normalizes model identifiers to the models.dev convention: provider/model-name
 * 
 * @see <a href="https://agent-trace.dev/">Agent Trace Specification</a>
 */
public final class ModelIdNormalizer {
    
    private ModelIdNormalizer() {
        // Utility class
    }

    /**
     * Known model prefixes mapped to their providers.
     */
    private static final Map<String, String> PROVIDER_PREFIXES = Map.ofEntries(
        // Anthropic
        Map.entry("claude-", "anthropic"),
        
        // OpenAI
        Map.entry("gpt-", "openai"),
        Map.entry("o1-", "openai"),
        Map.entry("o3-", "openai"),
        Map.entry("chatgpt-", "openai"),
        Map.entry("text-davinci-", "openai"),
        Map.entry("text-embedding-", "openai"),
        Map.entry("dall-e-", "openai"),
        Map.entry("whisper-", "openai"),
        Map.entry("tts-", "openai"),
        
        // Google
        Map.entry("gemini-", "google"),
        Map.entry("palm-", "google"),
        Map.entry("bard-", "google"),
        
        // Meta
        Map.entry("llama-", "meta"),
        Map.entry("llama2-", "meta"),
        Map.entry("llama3-", "meta"),
        Map.entry("codellama-", "meta"),
        
        // Mistral
        Map.entry("mistral-", "mistral"),
        Map.entry("mixtral-", "mistral"),
        Map.entry("codestral-", "mistral"),
        
        // Cohere
        Map.entry("command-", "cohere"),
        Map.entry("embed-", "cohere"),
        
        // Amazon
        Map.entry("titan-", "amazon"),
        
        // DeepSeek
        Map.entry("deepseek-", "deepseek")
    );

    private static final Pattern ALREADY_NORMALIZED = Pattern.compile("^[a-z0-9-]+/[a-z0-9._-]+$", Pattern.CASE_INSENSITIVE);

    /**
     * Normalize a model ID to the format: provider/model-name
     * 
     * @param modelId The raw model identifier
     * @return Normalized model ID in provider/model-name format
     */
    public static String normalize(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return "unknown/unknown";
        }

        String trimmed = modelId.trim();

        // Already normalized
        if (ALREADY_NORMALIZED.matcher(trimmed).matches()) {
            return trimmed.toLowerCase();
        }

        // Try to detect provider from prefix
        String lowerModel = trimmed.toLowerCase();
        for (Map.Entry<String, String> entry : PROVIDER_PREFIXES.entrySet()) {
            if (lowerModel.startsWith(entry.getKey())) {
                return entry.getValue() + "/" + trimmed;
            }
        }

        // Unknown provider
        return "unknown/" + trimmed;
    }

    /**
     * Extract the provider from a normalized model ID.
     * 
     * @param normalizedModelId A model ID in provider/model-name format
     * @return The provider name
     */
    public static String extractProvider(String normalizedModelId) {
        if (normalizedModelId == null || !normalizedModelId.contains("/")) {
            return "unknown";
        }
        return normalizedModelId.substring(0, normalizedModelId.indexOf('/'));
    }

    /**
     * Extract the model name from a normalized model ID.
     * 
     * @param normalizedModelId A model ID in provider/model-name format
     * @return The model name without provider
     */
    public static String extractModelName(String normalizedModelId) {
        if (normalizedModelId == null || !normalizedModelId.contains("/")) {
            return normalizedModelId != null ? normalizedModelId : "unknown";
        }
        return normalizedModelId.substring(normalizedModelId.indexOf('/') + 1);
    }

    /**
     * Check if a model ID is already in normalized format.
     */
    public static boolean isNormalized(String modelId) {
        return modelId != null && ALREADY_NORMALIZED.matcher(modelId).matches();
    }
}
