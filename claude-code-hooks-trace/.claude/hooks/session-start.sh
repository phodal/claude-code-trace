#!/bin/bash
# Session Start Hook - Initialize trace session
# This hook fires when a new Claude Code session begins

set -e

# Read input from stdin
INPUT=$(cat)

# Extract session info
SESSION_ID=$(echo "$INPUT" | jq -r '.session_id // empty')
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(pwd)}"

# Ensure trace directory exists
TRACE_DIR="$PROJECT_DIR/.agent-trace"
mkdir -p "$TRACE_DIR"

# Session trace file
TRACE_FILE="$TRACE_DIR/session-$(date +%Y%m%d-%H%M%S).jsonl"
export TRACE_FILE

# Get git info if available
GIT_COMMIT=""
GIT_BRANCH=""
if [ -d "$PROJECT_DIR/.git" ]; then
    GIT_COMMIT=$(git -C "$PROJECT_DIR" rev-parse HEAD 2>/dev/null || echo "")
    GIT_BRANCH=$(git -C "$PROJECT_DIR" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")
fi

# Create session start trace record
TRACE_RECORD=$(jq -n \
    --arg session_id "$SESSION_ID" \
    --arg timestamp "$TIMESTAMP" \
    --arg event_type "session_start" \
    --arg project_dir "$PROJECT_DIR" \
    --arg git_commit "$GIT_COMMIT" \
    --arg git_branch "$GIT_BRANCH" \
    --arg trace_file "$TRACE_FILE" \
    '{
        "version": "0.1.0",
        "event_type": $event_type,
        "session_id": $session_id,
        "timestamp": $timestamp,
        "context": {
            "project_dir": $project_dir,
            "vcs": {
                "type": "git",
                "commit": $git_commit,
                "branch": $git_branch
            }
        },
        "trace_file": $trace_file
    }')

# Write to trace file
echo "$TRACE_RECORD" >> "$TRACE_FILE"

# Store session info for other hooks
echo "$SESSION_ID" > "$TRACE_DIR/.current_session"
echo "$TRACE_FILE" > "$TRACE_DIR/.current_trace_file"

# Output success (optional system message)
echo '{"systemMessage": "Trace session initialized"}'

