#!/bin/bash
# Claude Code Hooks Trace - Installation Script
# Usage: ./install.sh [target_project_dir] [--python|--javascript|--bash]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_DIR="${1:-.}"
HANDLER_TYPE="${2:---bash}"

# Resolve target directory
if [[ ! -d "$TARGET_DIR" ]]; then
    echo "Error: Target directory does not exist: $TARGET_DIR"
    exit 1
fi

TARGET_DIR="$(cd "$TARGET_DIR" && pwd)"
echo "Installing Claude Code Hooks Trace to: $TARGET_DIR"

# Create .claude directory structure
mkdir -p "$TARGET_DIR/.claude/hooks"
mkdir -p "$TARGET_DIR/.agent-trace"

# Copy hooks
echo "Copying hook scripts..."
cp "$SCRIPT_DIR/.claude/hooks/"*.sh "$TARGET_DIR/.claude/hooks/"
cp "$SCRIPT_DIR/.claude/hooks/trace_handler.py" "$TARGET_DIR/.claude/hooks/"
cp "$SCRIPT_DIR/.claude/hooks/trace_handler.js" "$TARGET_DIR/.claude/hooks/" 2>/dev/null || true

# Make scripts executable
chmod +x "$TARGET_DIR/.claude/hooks/"*.sh
chmod +x "$TARGET_DIR/.claude/hooks/"*.py 2>/dev/null || true
chmod +x "$TARGET_DIR/.claude/hooks/"*.js 2>/dev/null || true

# Copy settings based on handler type
case "$HANDLER_TYPE" in
    --python)
        echo "Using Python handler..."
        cp "$SCRIPT_DIR/.claude/settings.python.json" "$TARGET_DIR/.claude/settings.json"
        ;;
    --javascript|--js|--node)
        echo "Using JavaScript (Node.js) handler..."
        cp "$SCRIPT_DIR/.claude/settings.javascript.json" "$TARGET_DIR/.claude/settings.json"
        ;;
    --bash|*)
        echo "Using Bash handler..."
        cp "$SCRIPT_DIR/.claude/settings.json" "$TARGET_DIR/.claude/settings.json"
        ;;
esac

# Copy scripts
echo "Copying analysis scripts..."
mkdir -p "$TARGET_DIR/scripts"
cp "$SCRIPT_DIR/scripts/"*.py "$TARGET_DIR/scripts/" 2>/dev/null || true
chmod +x "$TARGET_DIR/scripts/"*.py 2>/dev/null || true

# Add .agent-trace to .gitignore if not already there
GITIGNORE="$TARGET_DIR/.gitignore"
if [[ -f "$GITIGNORE" ]]; then
    if ! grep -q "^\.agent-trace" "$GITIGNORE"; then
        echo "" >> "$GITIGNORE"
        echo "# Claude Code Hooks Trace output" >> "$GITIGNORE"
        echo ".agent-trace/" >> "$GITIGNORE"
        echo "Added .agent-trace to .gitignore"
    fi
else
    echo "# Claude Code Hooks Trace output" > "$GITIGNORE"
    echo ".agent-trace/" >> "$GITIGNORE"
    echo "Created .gitignore with .agent-trace"
fi

echo ""
echo "✅ Installation complete!"
echo ""
echo "Files installed:"
echo "  $TARGET_DIR/.claude/settings.json"
echo "  $TARGET_DIR/.claude/hooks/*.sh"
echo "  $TARGET_DIR/.agent-trace/ (trace output directory)"
echo ""
echo "Next steps:"
echo "  1. Open Claude Code in your project"
echo "  2. Run /hooks to verify hooks are registered"
echo "  3. Start coding - traces will be saved to .agent-trace/"
echo ""
echo "To analyze traces:"
echo "  python scripts/trace_analyzer.py .agent-trace/session-*.jsonl"

