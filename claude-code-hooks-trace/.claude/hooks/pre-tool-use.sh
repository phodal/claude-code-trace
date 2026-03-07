#!/bin/bash
# Pre Tool Use Hook - Record tool invocation before execution
# This hook fires before Write, Edit, or Bash tools execute

set -e

# Read input from stdin
INPUT=$(cat)

# Extract tool info
TOOL_NAME=$(echo "$INPUT" | jq -r '.tool_name // "unknown"')
TOOL_INPUT=$(echo "$INPUT" | jq -c '.tool_input // {}')
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

# Generate span ID for this tool use
SPAN_ID=$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid 2>/dev/null || echo "span-$(date +%s%N)")

# Extract file path if applicable
FILE_PATH=""
case "$TOOL_NAME" in
    "Write"|"Edit")
        FILE_PATH=$(echo "$TOOL_INPUT" | jq -r '.file_path // .path // empty')
        ;;
    "Bash")
        # Extract from command if it's a file operation
        ;;
esac

# Create pre-tool trace record
TRACE_RECORD=$(jq -n \
    --arg session_id "$SESSION_ID" \
    --arg span_id "$SPAN_ID" \
    --arg timestamp "$TIMESTAMP" \
    --arg event_type "pre_tool_use" \
    --arg tool_name "$TOOL_NAME" \
    --arg file_path "$FILE_PATH" \
    --argjson tool_input "$TOOL_INPUT" \
    '{
        "version": "0.1.0",
        "event_type": $event_type,
        "session_id": $session_id,
        "span_id": $span_id,
        "timestamp": $timestamp,
        "tool": {
            "name": $tool_name,
            "input": $tool_input
        },
        "file_path": (if $file_path != "" then $file_path else null end)
    }')

# Write to trace file
echo "$TRACE_RECORD" >> "$TRACE_FILE"

# Store span ID for post-tool-use correlation
echo "$SPAN_ID" > "$TRACE_DIR/.current_span_id"

# Exit 0 to allow tool execution to proceed
exit 0

