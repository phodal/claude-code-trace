package com.phodal.agenttrace.vcs;

import com.phodal.agenttrace.model.Vcs;
import com.phodal.agenttrace.model.VcsType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Git VCS provider implementation.
 * Uses git commands to retrieve repository information.
 */
public class GitVcsProvider implements VcsProvider {
    private static final Logger log = LoggerFactory.getLogger(GitVcsProvider.class);
    
    private static final String GIT_DIR = ".git";
    private static final long COMMAND_TIMEOUT_SECONDS = 5;

    @Override
    public boolean isPresent(Path workspacePath) {
        return findGitDir(workspacePath).isPresent();
    }

    @Override
    public Optional<Vcs> getVcs(Path workspacePath) {
        return getCurrentRevision(workspacePath)
            .map(revision -> new Vcs(VcsType.GIT, revision));
    }

    @Override
    public Optional<String> getCurrentRevision(Path workspacePath) {
        return runGitCommand(workspacePath, "rev-parse", "HEAD")
            .map(String::trim)
            .filter(sha -> sha.length() == 40);
    }

    @Override
    public Optional<Path> getRepositoryRoot(Path workspacePath) {
        return runGitCommand(workspacePath, "rev-parse", "--show-toplevel")
            .map(String::trim)
            .map(Path::of);
    }

    /**
     * Get the current branch name.
     */
    public Optional<String> getCurrentBranch(Path workspacePath) {
        return runGitCommand(workspacePath, "rev-parse", "--abbrev-ref", "HEAD")
            .map(String::trim);
    }

    /**
     * Check if there are uncommitted changes.
     */
    public boolean hasUncommittedChanges(Path workspacePath) {
        return runGitCommand(workspacePath, "status", "--porcelain")
            .map(output -> !output.isBlank())
            .orElse(false);
    }

    /**
     * Get the short commit hash (7 characters).
     */
    public Optional<String> getShortRevision(Path workspacePath) {
        return runGitCommand(workspacePath, "rev-parse", "--short", "HEAD")
            .map(String::trim);
    }

    /**
     * Find the .git directory, searching up the directory tree.
     */
    private Optional<Path> findGitDir(Path startPath) {
        Path current = startPath.toAbsolutePath().normalize();
        while (current != null) {
            Path gitDir = current.resolve(GIT_DIR);
            if (Files.exists(gitDir)) {
                return Optional.of(current);
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    /**
     * Run a git command and return the output.
     */
    private Optional<String> runGitCommand(Path workspacePath, String... args) {
        try {
            String[] command = new String[args.length + 1];
            command[0] = "git";
            System.arraycopy(args, 0, command, 1, args.length);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workspacePath.toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();
            
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > 0) {
                        output.append("\n");
                    }
                    output.append(line);
                }
            }

            boolean completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                log.warn("Git command timed out: git {}", String.join(" ", args));
                return Optional.empty();
            }

            if (process.exitValue() != 0) {
                log.debug("Git command failed with exit code {}: git {}", 
                    process.exitValue(), String.join(" ", args));
                return Optional.empty();
            }

            return Optional.of(output.toString());
        } catch (IOException | InterruptedException e) {
            log.debug("Failed to run git command: {}", e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }
    }
}
