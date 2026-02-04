package com.phodal.agenttrace.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Version control system information for a trace record.
 * 
 * @param type The VCS type (git, jj, hg, svn)
 * @param revision The revision identifier (e.g., git commit SHA, jj change ID)
 * @see <a href="https://agent-trace.dev/">Agent Trace Specification</a>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Vcs(
    VcsType type,
    String revision
) {
    /**
     * Create a Git VCS reference.
     */
    public static Vcs git(String commitSha) {
        return new Vcs(VcsType.GIT, commitSha);
    }

    /**
     * Create a Jujutsu VCS reference.
     */
    public static Vcs jj(String changeId) {
        return new Vcs(VcsType.JJ, changeId);
    }
}
