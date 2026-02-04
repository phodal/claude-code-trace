package com.phodal.agenttrace.vcs;

import com.phodal.agenttrace.model.Vcs;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Interface for version control system providers.
 * Implementations detect and retrieve VCS information from a workspace.
 */
public interface VcsProvider {
    
    /**
     * Check if this VCS is present in the workspace.
     * 
     * @param workspacePath Path to the workspace root
     * @return true if this VCS is detected
     */
    boolean isPresent(Path workspacePath);

    /**
     * Get the VCS information for the workspace.
     * 
     * @param workspacePath Path to the workspace root
     * @return VCS information if available
     */
    Optional<Vcs> getVcs(Path workspacePath);

    /**
     * Get the current revision identifier.
     * 
     * @param workspacePath Path to the workspace root
     * @return Revision identifier (commit SHA, change ID, etc.)
     */
    Optional<String> getCurrentRevision(Path workspacePath);

    /**
     * Get the repository root path.
     * 
     * @param workspacePath Path within the workspace
     * @return Repository root path
     */
    Optional<Path> getRepositoryRoot(Path workspacePath);

    /**
     * Convert an absolute path to a path relative to the repository root.
     * 
     * @param workspacePath Workspace or file path
     * @param absolutePath Absolute path to convert
     * @return Relative path from repository root
     */
    default Optional<String> toRelativePath(Path workspacePath, Path absolutePath) {
        return getRepositoryRoot(workspacePath)
            .map(root -> root.relativize(absolutePath).toString().replace('\\', '/'));
    }
}
