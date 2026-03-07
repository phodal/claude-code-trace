#!/bin/bash
# Post Tool Use Hook - Record tool execution result
# This hook fires after Write or Edit tools complete

set -e

# Read input from stdin
INPUT=$(cat)

# Extract tool info
TOOL_NAME=$(echo "$INPUT" | jq -r '.tool_name // "unknown"')
TOOL_INPUT=$(echo "$INPUT" | jq -c '.tool_input // {}')
TOOL_OUTPUT=$(echo "$INPUT" | jq -c '.tool_output // {}')
SESSION_ID=$(echo "$INPUT" | jq -r '.session_id // empty')
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(pwd)}"

# Get trace file path
TRACE_DIR="$PROJECT_DIR/.agent-trace"
TRACE_FILE=""
if [ -f "$TRACE_DIR/.current_trace_file" ]; then
    TRACE_FILE=$(cat "$TRACE_DIR/.current_trace_file")
fi

# Fallback if no trace file
if [ -z "$TRACE_FILE" ] || [ ! -f "$TRACE_FILE" ]; then
    TRACE_FILE="$TRACE_DIR/session-$(date +%Y%m%d).jsonl"
fi

# Get span ID from pre-tool-use
SPAN_ID=""
if [ -f "$TRACE_DIR/.current_span_id" ]; then
    SPAN_ID=$(cat "$TRACE_DIR/.current_span_id")
fi

# Extract file info
FILE_PATH=""
case "$TOOL_NAME" in
    "Write"|"Edit")
        FILE_PATH=$(echo "$TOOL_INPUT" | jq -r '.file_path // .path // empty')
        ;;
esac

# Get line count if file exists
LINE_COUNT=0
if [ -n "$FILE_PATH" ] && [ -f "$PROJECT_DIR/$FILE_PATH" ]; then
    LINE_COUNT=$(wc -l < "$PROJECT_DIR/$FILE_PATH" | tr -d ' ')
fi

# Get current git status for the file
GIT_STATUS=""
if [ -n "$FILE_PATH" ] && [ -d "$PROJECT_DIR/.git" ]; then
    GIT_STATUS=$(git -C "$PROJECT_DIR" status --porcelain "$FILE_PATH" 2>/dev/null | head -1 || echo "")
fi

# Create post-tool trace record
TRACE_RECORD=$(jq -n \
    --arg session_id "$SESSION_ID" \
    --arg span_id "$SPAN_ID" \
    --arg timestamp "$TIMESTAMP" \
    --arg event_type "post_tool_use" \
    --arg tool_name "$TOOL_NAME" \
    --arg file_path "$FILE_PATH" \
    --arg git_status "$GIT_STATUS" \
    --argjson line_count "$LINE_COUNT" \
    --argjson tool_output "$TOOL_OUTPUT" \
    '{
        "version": "0.1.0",
        "event_type": $event_type,
        "session_id": $session_id,
        "span_id": $span_id,
        "timestamp": $timestamp,
        "tool": {
            "name": $tool_name,
            "output": $tool_output
        },
        "file": {
            "path": (if $file_path != "" then $file_path else null end),
            "line_count": $line_count,
            "git_status": (if $git_status != "" then $git_status else null end)
        }
    }')

# Write to trace file
echo "$TRACE_RECORD" >> "$TRACE_FILE"

# Clear span ID
rm -f "$TRACE_DIR/.current_span_id"

exit 0

