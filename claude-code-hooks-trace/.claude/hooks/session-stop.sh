#!/bin/bash
# Session Stop Hook - Finalize trace session
# This hook fires when Claude Code finishes a response

set -e

# Read input from stdin
INPUT=$(cat)

# Check if this is a stop hook continuation (prevent infinite loop)
STOP_HOOK_ACTIVE=$(echo "$INPUT" | jq -r '.stop_hook_active // false')
if [ "$STOP_HOOK_ACTIVE" = "true" ]; then
    exit 0
fi

# Extract session info
SESSION_ID=$(echo "$INPUT" | jq -r '.session_id // empty')
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(pwd)}"

# Get trace file path
TRACE_DIR="$PROJECT_DIR/.agent-trace"
TRACE_FILE=""
if [ -f "$TRACE_DIR/.current_trace_file" ]; then
    TRACE_FILE=$(cat "$TRACE_DIR/.current_trace_file")
fi

# If no trace file, nothing to do
if [ -z "$TRACE_FILE" ] || [ ! -f "$TRACE_FILE" ]; then
    exit 0
fi

# Count events in this session
EVENT_COUNT=0
if [ -f "$TRACE_FILE" ]; then
    EVENT_COUNT=$(wc -l < "$TRACE_FILE" | tr -d ' ')
fi

# Get session start timestamp
SESSION_START=""
if [ -f "$TRACE_FILE" ]; then
    SESSION_START=$(head -1 "$TRACE_FILE" | jq -r '.timestamp // empty' 2>/dev/null || echo "")
fi

# Calculate session duration
DURATION_SEC=0
if [ -n "$SESSION_START" ]; then
    START_EPOCH=$(date -jf "%Y-%m-%dT%H:%M:%SZ" "$SESSION_START" +%s 2>/dev/null || date -d "$SESSION_START" +%s 2>/dev/null || echo 0)
    END_EPOCH=$(date +%s)
    if [ "$START_EPOCH" -gt 0 ]; then
        DURATION_SEC=$((END_EPOCH - START_EPOCH))
    fi
fi

# Get git diff summary for the session
GIT_DIFF_SUMMARY=""
if [ -d "$PROJECT_DIR/.git" ]; then
    FILES_CHANGED=$(git -C "$PROJECT_DIR" diff --stat HEAD 2>/dev/null | tail -1 || echo "")
    GIT_DIFF_SUMMARY="$FILES_CHANGED"
fi

# Create session stop trace record (use -nc for compact single-line JSON)
TRACE_RECORD=$(jq -nc \
    --arg session_id "$SESSION_ID" \
    --arg timestamp "$TIMESTAMP" \
    --arg event_type "session_stop" \
    --argjson event_count "$EVENT_COUNT" \
    --argjson duration_sec "$DURATION_SEC" \
    --arg git_diff "$GIT_DIFF_SUMMARY" \
    '{
        "version": "0.1.0",
        "event_type": $event_type,
        "session_id": $session_id,
        "timestamp": $timestamp,
        "summary": {
            "event_count": $event_count,
            "duration_seconds": $duration_sec,
            "git_changes": (if $git_diff != "" then $git_diff else null end)
        }
    }')

# Write to trace file
echo "$TRACE_RECORD" >> "$TRACE_FILE"

exit 0

