#!/usr/bin/env python3
"""
Claude Code Hooks Trace Handler
A comprehensive trace handler that records all Claude Code activities.
"""

import json
import sys
import os
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional, Dict, Any
import subprocess


class TraceHandler:
    VERSION = "0.1.0"
    
    def __init__(self):
        self.project_dir = Path(os.environ.get("CLAUDE_PROJECT_DIR", os.getcwd()))
        self.trace_dir = self.project_dir / ".agent-trace"
        self.trace_dir.mkdir(parents=True, exist_ok=True)
        
    def get_current_trace_file(self) -> Path:
        """Get the current trace file path."""
        trace_file_marker = self.trace_dir / ".current_trace_file"
        if trace_file_marker.exists():
            return Path(trace_file_marker.read_text().strip())
        return self.trace_dir / f"session-{datetime.now().strftime('%Y%m%d-%H%M%S')}.jsonl"
    
    def get_git_info(self) -> Dict[str, str]:
        """Get current git repository information."""
        git_info = {"type": "git", "commit": "", "branch": ""}
        git_dir = self.project_dir / ".git"
        if not git_dir.exists():
            return git_info
        try:
            result = subprocess.run(
                ["git", "rev-parse", "HEAD"],
                cwd=self.project_dir, capture_output=True, text=True, timeout=5
            )
            if result.returncode == 0:
                git_info["commit"] = result.stdout.strip()
            
            result = subprocess.run(
                ["git", "rev-parse", "--abbrev-ref", "HEAD"],
                cwd=self.project_dir, capture_output=True, text=True, timeout=5
            )
            if result.returncode == 0:
                git_info["branch"] = result.stdout.strip()
        except Exception:
            pass
        return git_info

    def write_trace(self, record: Dict[str, Any]):
        """Write a trace record to the trace file."""
        trace_file = self.get_current_trace_file()
        with open(trace_file, "a", encoding="utf-8") as f:
            f.write(json.dumps(record, ensure_ascii=False) + "\n")

    def create_base_record(self, event_type: str, input_data: Dict) -> Dict[str, Any]:
        """Create a base trace record with common fields."""
        return {
            "version": self.VERSION,
            "event_type": event_type,
            "session_id": input_data.get("session_id", ""),
            "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        }

    def handle_session_start(self, input_data: Dict) -> Dict[str, Any]:
        """Handle SessionStart event."""
        trace_file = self.trace_dir / f"session-{datetime.now().strftime('%Y%m%d-%H%M%S')}.jsonl"
        (self.trace_dir / ".current_trace_file").write_text(str(trace_file))
        (self.trace_dir / ".current_session").write_text(input_data.get("session_id", ""))
        
        record = self.create_base_record("session_start", input_data)
        record["context"] = {
            "project_dir": str(self.project_dir),
            "vcs": self.get_git_info()
        }
        record["trace_file"] = str(trace_file)
        self.write_trace(record)
        return {"systemMessage": "Trace session initialized"}

    def handle_pre_tool_use(self, input_data: Dict) -> Dict[str, Any]:
        """Handle PreToolUse event."""
        span_id = str(uuid.uuid4())
        (self.trace_dir / ".current_span_id").write_text(span_id)
        
        record = self.create_base_record("pre_tool_use", input_data)
        record["span_id"] = span_id
        record["tool"] = {
            "name": input_data.get("tool_name", "unknown"),
            "input": input_data.get("tool_input", {})
        }
        # Extract file path if applicable
        tool_input = input_data.get("tool_input", {})
        file_path = tool_input.get("file_path") or tool_input.get("path")
        if file_path:
            record["file_path"] = file_path
        self.write_trace(record)
        return {}  # Allow tool execution

    def handle_post_tool_use(self, input_data: Dict) -> Dict[str, Any]:
        """Handle PostToolUse event."""
        span_id_file = self.trace_dir / ".current_span_id"
        span_id = span_id_file.read_text().strip() if span_id_file.exists() else ""
        
        record = self.create_base_record("post_tool_use", input_data)
        record["span_id"] = span_id
        record["tool"] = {
            "name": input_data.get("tool_name", "unknown"),
            "output": input_data.get("tool_output", {})
        }
        # Get file info
        tool_input = input_data.get("tool_input", {})
        file_path = tool_input.get("file_path") or tool_input.get("path")
        if file_path:
            full_path = self.project_dir / file_path
            record["file"] = {
                "path": file_path,
                "exists": full_path.exists(),
                "line_count": len(full_path.read_text().splitlines()) if full_path.exists() else 0
            }
        span_id_file.unlink(missing_ok=True)
        self.write_trace(record)
        return {}

    def handle_stop(self, input_data: Dict) -> Dict[str, Any]:
        """Handle Stop event."""
        if input_data.get("stop_hook_active"):
            return {}  # Prevent infinite loop
        record = self.create_base_record("session_stop", input_data)
        trace_file = self.get_current_trace_file()
        if trace_file.exists():
            record["summary"] = {"event_count": len(trace_file.read_text().splitlines())}
        self.write_trace(record)
        return {}

    def handle_notification(self, input_data: Dict) -> Dict[str, Any]:
        """Handle Notification event."""
        record = self.create_base_record("notification", input_data)
        record["notification"] = {
            "type": input_data.get("type", "unknown"),
            "title": input_data.get("title"),
            "message": input_data.get("message")
        }
        self.write_trace(record)
        return {}


def main():
    if len(sys.argv) < 2:
        print("Usage: trace_handler.py <event_type>", file=sys.stderr)
        sys.exit(1)
    
    event_type = sys.argv[1]
    input_data = json.loads(sys.stdin.read())
    handler = TraceHandler()
    
    handlers = {
        "SessionStart": handler.handle_session_start,
        "PreToolUse": handler.handle_pre_tool_use,
        "PostToolUse": handler.handle_post_tool_use,
        "Stop": handler.handle_stop,
        "Notification": handler.handle_notification,
    }
    
    if event_type in handlers:
        result = handlers[event_type](input_data)
        if result:
            print(json.dumps(result))


if __name__ == "__main__":
    main()

