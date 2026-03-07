#!/usr/bin/env python3
"""
Trace Analyzer - Analyze Claude Code hook traces
Provides insights into AI coding sessions, file modifications, and patterns.
"""

import json
import sys
import argparse
from pathlib import Path
from datetime import datetime
from collections import defaultdict
from typing import List, Dict, Any, Optional


def load_traces(trace_file: Path) -> List[Dict[str, Any]]:
    """Load trace records from a JSONL file."""
    traces = []
    with open(trace_file, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                try:
                    traces.append(json.loads(line))
                except json.JSONDecodeError:
                    continue
    return traces


def analyze_session(traces: List[Dict[str, Any]]) -> Dict[str, Any]:
    """Analyze a session's traces and return summary statistics."""
    stats = {
        "total_events": len(traces),
        "event_types": defaultdict(int),
        "tools_used": defaultdict(int),
        "files_modified": set(),
        "session_start": None,
        "session_end": None,
        "duration_seconds": 0,
    }
    
    for trace in traces:
        event_type = trace.get("event_type", "unknown")
        stats["event_types"][event_type] += 1
        
        # Track timestamps
        timestamp = trace.get("timestamp")
        if timestamp:
            if stats["session_start"] is None:
                stats["session_start"] = timestamp
            stats["session_end"] = timestamp
        
        # Track tool usage
        tool = trace.get("tool", {})
        if tool.get("name"):
            stats["tools_used"][tool["name"]] += 1
        
        # Track file modifications
        file_info = trace.get("file", {})
        file_path = file_info.get("path") or trace.get("file_path")
        if file_path and event_type in ("post_tool_use", "pre_tool_use"):
            stats["files_modified"].add(file_path)
    
    # Calculate duration
    if stats["session_start"] and stats["session_end"]:
        try:
            start = datetime.fromisoformat(stats["session_start"].replace("Z", "+00:00"))
            end = datetime.fromisoformat(stats["session_end"].replace("Z", "+00:00"))
            stats["duration_seconds"] = (end - start).total_seconds()
        except Exception:
            pass
    
    # Convert sets to lists for JSON serialization
    stats["files_modified"] = list(stats["files_modified"])
    stats["event_types"] = dict(stats["event_types"])
    stats["tools_used"] = dict(stats["tools_used"])
    
    return stats


def print_summary(stats: Dict[str, Any]):
    """Print a human-readable summary."""
    print("\n" + "=" * 60)
    print("CLAUDE CODE SESSION TRACE SUMMARY")
    print("=" * 60)
    
    print(f"\nTotal Events: {stats['total_events']}")
    print(f"Duration: {stats['duration_seconds']:.0f} seconds ({stats['duration_seconds']/60:.1f} minutes)")
    
    print("\nEvent Types:")
    for event_type, count in sorted(stats["event_types"].items()):
        print(f"  - {event_type}: {count}")
    
    print("\nTools Used:")
    for tool, count in sorted(stats["tools_used"].items(), key=lambda x: -x[1]):
        print(f"  - {tool}: {count} times")
    
    print(f"\nFiles Modified ({len(stats['files_modified'])}):")
    for f in sorted(stats["files_modified"]):
        print(f"  - {f}")
    
    print("\n" + "=" * 60)


def export_to_json(stats: Dict[str, Any], output_file: Path):
    """Export analysis results to JSON."""
    with open(output_file, "w", encoding="utf-8") as f:
        json.dump(stats, f, indent=2, ensure_ascii=False)
    print(f"Analysis exported to: {output_file}")


def main():
    parser = argparse.ArgumentParser(description="Analyze Claude Code hook traces")
    parser.add_argument("trace_file", type=Path, help="Path to trace JSONL file")
    parser.add_argument("-o", "--output", type=Path, help="Export analysis to JSON file")
    parser.add_argument("-j", "--json", action="store_true", help="Output as JSON")
    args = parser.parse_args()
    
    if not args.trace_file.exists():
        print(f"Error: Trace file not found: {args.trace_file}", file=sys.stderr)
        sys.exit(1)
    
    traces = load_traces(args.trace_file)
    if not traces:
        print("No traces found in file.", file=sys.stderr)
        sys.exit(1)
    
    stats = analyze_session(traces)
    
    if args.json:
        print(json.dumps(stats, indent=2))
    else:
        print_summary(stats)
    
    if args.output:
        export_to_json(stats, args.output)


if __name__ == "__main__":
    main()

