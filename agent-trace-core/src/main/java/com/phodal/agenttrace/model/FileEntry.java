package com.phodal.agenttrace.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * A file entry with attributed conversations.
 * 
 * @param path Relative file path from repository root
 * @param conversations Array of conversations that contributed to this file
 * @see <a href="https://agent-trace.dev/">Agent Trace Specification</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FileEntry(
    String path,
    List<Conversation> conversations
) {
    /**
     * Create a file entry with a single conversation.
     */
    public static FileEntry of(String path, Conversation conversation) {
        return new FileEntry(path, List.of(conversation));
    }

    /**
     * Create a file entry with multiple conversations.
     */
    public static FileEntry of(String path, List<Conversation> conversations) {
        return new FileEntry(path, conversations);
    }

    /**
     * Builder for creating file entries.
     */
    public static Builder builder(String path) {
        return new Builder(path);
    }

    public static class Builder {
        private final String path;
        private List<Conversation> conversations = new ArrayList<>();

        public Builder(String path) {
            this.path = path;
        }

        public Builder addConversation(Conversation conversation) {
            this.conversations.add(conversation);
            return this;
        }

        public Builder conversations(List<Conversation> conversations) {
            this.conversations = new ArrayList<>(conversations);
            return this;
        }

        public FileEntry build() {
            return new FileEntry(path, conversations);
        }
    }

    /**
     * Get total line count across all conversations.
     */
    public int totalLineCount() {
        return conversations.stream()
            .flatMap(c -> c.ranges().stream())
            .mapToInt(Range::lineCount)
            .sum();
    }
}
