package com.phodal.agenttrace.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * A conversation that produced code changes.
 * Contains contributor info and ranges grouped by conversation.
 * 
 * @param url URL to look up the conversation that produced this code
 * @param contributor The contributor for ranges in this conversation (can be overridden per-range)
 * @param ranges Array of line ranges produced by this conversation
 * @param related Other related resources
 * @see <a href="https://agent-trace.dev/">Agent Trace Specification</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Conversation(
    String url,
    Contributor contributor,
    List<Range> ranges,
    List<RelatedResource> related
) {
    /**
     * Create a conversation with just a contributor and ranges.
     */
    public static Conversation of(Contributor contributor, List<Range> ranges) {
        return new Conversation(null, contributor, ranges, null);
    }

    /**
     * Create a conversation with URL, contributor, and ranges.
     */
    public static Conversation of(String url, Contributor contributor, List<Range> ranges) {
        return new Conversation(url, contributor, ranges, null);
    }

    /**
     * Builder for creating conversations.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String url;
        private Contributor contributor;
        private List<Range> ranges = new ArrayList<>();
        private List<RelatedResource> related;

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public Builder contributor(Contributor contributor) {
            this.contributor = contributor;
            return this;
        }

        public Builder addRange(Range range) {
            this.ranges.add(range);
            return this;
        }

        public Builder addRange(int startLine, int endLine) {
            this.ranges.add(Range.of(startLine, endLine));
            return this;
        }

        public Builder ranges(List<Range> ranges) {
            this.ranges = new ArrayList<>(ranges);
            return this;
        }

        public Builder addRelated(RelatedResource resource) {
            if (this.related == null) {
                this.related = new ArrayList<>();
            }
            this.related.add(resource);
            return this;
        }

        public Conversation build() {
            return new Conversation(url, contributor, ranges, related);
        }
    }
}
