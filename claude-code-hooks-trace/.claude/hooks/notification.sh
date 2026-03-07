#!/bin/bash
# Notification Hook - Record notifications and optionally send desktop alerts
# This hook fires when Claude Code needs user attention

set -e

# Read input from stdin
INPUT=$(cat)

# Extract notification info
NOTIFICATION_TYPE=$(echo "$INPUT" | jq -r '.type // "unknown"')
NOTIFICATION_TITLE=$(echo "$INPUT" | jq -r '.title // empty')
NOTIFICATION_MESSAGE=$(echo "$INPUT" | jq -r '.message // empty')
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
if [ -z "$TRACE_FILE" ]; then
    TRACE_FILE="$TRACE_DIR/session-$(date +%Y%m%d).jsonl"
fi

# Create notification trace record (use -nc for compact single-line JSON)
TRACE_RECORD=$(jq -nc \
    --arg session_id "$SESSION_ID" \
    --arg timestamp "$TIMESTAMP" \
    --arg event_type "notification" \
    --arg notification_type "$NOTIFICATION_TYPE" \
    --arg title "$NOTIFICATION_TITLE" \
    --arg message "$NOTIFICATION_MESSAGE" \
    '{
        "version": "0.1.0",
        "event_type": $event_type,
        "session_id": $session_id,
        "timestamp": $timestamp,
        "notification": {
            "type": $notification_type,
            "title": (if $title != "" then $title else null end),
            "message": (if $message != "" then $message else null end)
        }
    }')

# Write to trace file if it exists
if [ -n "$TRACE_FILE" ]; then
    echo "$TRACE_RECORD" >> "$TRACE_FILE"
fi

# Optional: Send desktop notification (uncomment for your OS)
# macOS:
# osascript -e "display notification \"$NOTIFICATION_MESSAGE\" with title \"Claude Code: $NOTIFICATION_TITLE\""

# Linux (requires notify-send):
# notify-send "Claude Code: $NOTIFICATION_TITLE" "$NOTIFICATION_MESSAGE"

# Windows (PowerShell via WSL):
# powershell.exe -Command "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] > \$null; \$xml = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02); \$xml.GetElementsByTagName('text')[0].AppendChild(\$xml.CreateTextNode('$NOTIFICATION_TITLE')); \$xml.GetElementsByTagName('text')[1].AppendChild(\$xml.CreateTextNode('$NOTIFICATION_MESSAGE')); [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('Claude Code').Show(\$xml)"

exit 0

