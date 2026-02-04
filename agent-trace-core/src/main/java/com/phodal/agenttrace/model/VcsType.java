package com.phodal.agenttrace.model;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Version control system types supported by Agent Trace.
 * 
 * @see <a href="https://agent-trace.dev/">Agent Trace Specification</a>
 */
public enum VcsType {
    GIT("git"),
    JJ("jj"),
    HG("hg"),
    SVN("svn");

    private final String value;

    VcsType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static VcsType fromValue(String value) {
        for (VcsType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown VCS type: " + value);
    }
}
