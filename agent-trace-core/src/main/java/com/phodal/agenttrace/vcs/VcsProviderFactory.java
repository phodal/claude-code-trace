package com.phodal.agenttrace.vcs;

import com.phodal.agenttrace.model.Vcs;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Factory for detecting and creating VCS providers.
 * Tries each provider in order until one is found.
 */
public class VcsProviderFactory {
    
    private static final List<VcsProvider> PROVIDERS = List.of(
        new GitVcsProvider()
        // Add more providers here: JjVcsProvider, HgVcsProvider, etc.
    );

    /**
     * Detect the VCS in use for a workspace.
     * 
     * @param workspacePath Path to the workspace
     * @return The detected VCS provider, or empty if none found
     */
    public static Optional<VcsProvider> detect(Path workspacePath) {
        for (VcsProvider provider : PROVIDERS) {
            if (provider.isPresent(workspacePath)) {
                return Optional.of(provider);
            }
        }
        return Optional.empty();
    }

    /**
     * Get VCS information for a workspace.
     * 
     * @param workspacePath Path to the workspace
     * @return VCS information if any VCS is detected
     */
    public static Optional<Vcs> getVcs(Path workspacePath) {
        return detect(workspacePath)
            .flatMap(provider -> provider.getVcs(workspacePath));
    }

    /**
     * Get the current revision for a workspace.
     * 
     * @param workspacePath Path to the workspace
     * @return Current revision if any VCS is detected
     */
    public static Optional<String> getCurrentRevision(Path workspacePath) {
        return detect(workspacePath)
            .flatMap(provider -> provider.getCurrentRevision(workspacePath));
    }

    /**
     * Get the repository root for a workspace.
     * 
     * @param workspacePath Path within the workspace
     * @return Repository root path
     */
    public static Optional<Path> getRepositoryRoot(Path workspacePath) {
        return detect(workspacePath)
            .flatMap(provider -> provider.getRepositoryRoot(workspacePath));
    }

    /**
     * Convert an absolute path to a relative path from repository root.
     * 
     * @param workspacePath Workspace path
     * @param absolutePath Absolute path to convert
     * @return Relative path from repository root
     */
    public static Optional<String> toRelativePath(Path workspacePath, Path absolutePath) {
        return detect(workspacePath)
            .flatMap(provider -> provider.toRelativePath(workspacePath, absolutePath));
    }
}
