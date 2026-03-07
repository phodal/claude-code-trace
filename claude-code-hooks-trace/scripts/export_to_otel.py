#!/usr/bin/env python3
"""
Export Claude Code hook traces to OpenTelemetry format.
Converts JSONL traces to OTEL-compatible JSON for visualization in Jaeger/Zipkin.
"""

import json
import sys
import argparse
from pathlib import Path
from datetime import datetime
from typing import List, Dict, Any
import hashlib


def generate_trace_id(session_id: str) -> str:
    """Generate a 32-char hex trace ID from session ID."""
    return hashlib.sha256(session_id.encode()).hexdigest()[:32]


def generate_span_id(span_id: str) -> str:
    """Generate a 16-char hex span ID."""
    if span_id:
        return hashlib.sha256(span_id.encode()).hexdigest()[:16]
    return hashlib.sha256(str(datetime.now()).encode()).hexdigest()[:16]


def timestamp_to_nanos(iso_timestamp: str) -> int:
    """Convert ISO timestamp to nanoseconds since epoch."""
    try:
        dt = datetime.fromisoformat(iso_timestamp.replace("Z", "+00:00"))
        return int(dt.timestamp() * 1_000_000_000)
    except Exception:
        return int(datetime.now().timestamp() * 1_000_000_000)


def convert_to_otel_span(trace: Dict[str, Any], trace_id: str, parent_span_id: str = None) -> Dict[str, Any]:
    """Convert a hook trace to an OTEL span."""
    span_id = generate_span_id(trace.get("span_id", str(datetime.now())))
    event_type = trace.get("event_type", "unknown")
    timestamp = trace.get("timestamp", datetime.now().isoformat())
    
    # Determine span name
    tool = trace.get("tool", {})
    tool_name = tool.get("name", "")
    span_name = f"{event_type}"
    if tool_name:
        span_name = f"{event_type}:{tool_name}"
    
    # Build attributes
    attributes = []
    for key, value in trace.items():
        if key in ("version", "event_type", "timestamp", "span_id", "session_id"):
            continue
        if isinstance(value, dict):
            for sub_key, sub_value in value.items():
                if sub_value is not None:
                    attributes.append({
                        "key": f"{key}.{sub_key}",
                        "value": {"stringValue": str(sub_value) if not isinstance(sub_value, bool) else str(sub_value).lower()}
                    })
        elif value is not None:
            attributes.append({
                "key": key,
                "value": {"stringValue": str(value)}
            })
    
    span = {
        "traceId": trace_id,
        "spanId": span_id,
        "name": span_name,
        "kind": 1,  # SPAN_KIND_INTERNAL
        "startTimeUnixNano": timestamp_to_nanos(timestamp),
        "endTimeUnixNano": timestamp_to_nanos(timestamp) + 1_000_000,  # +1ms
        "attributes": attributes,
        "status": {"code": 1}  # STATUS_CODE_OK
    }
    
    if parent_span_id:
        span["parentSpanId"] = parent_span_id
    
    return span


def convert_traces_to_otel(traces: List[Dict[str, Any]]) -> Dict[str, Any]:
    """Convert all traces to OTEL format."""
    if not traces:
        return {"resourceSpans": []}
    
    session_id = traces[0].get("session_id", "unknown")
    trace_id = generate_trace_id(session_id)
    
    spans = []
    root_span_id = None
    
    for i, trace in enumerate(traces):
        parent_id = root_span_id if i > 0 else None
        span = convert_to_otel_span(trace, trace_id, parent_id)
        spans.append(span)
        if i == 0:
            root_span_id = span["spanId"]
    
    return {
        "resourceSpans": [{
            "resource": {
                "attributes": [
                    {"key": "service.name", "value": {"stringValue": "claude-code"}},
                    {"key": "session.id", "value": {"stringValue": session_id}}
                ]
            },
            "scopeSpans": [{
                "scope": {"name": "claude-code-hooks", "version": "0.1.0"},
                "spans": spans
            }]
        }]
    }


def main():
    parser = argparse.ArgumentParser(description="Export traces to OpenTelemetry format")
    parser.add_argument("trace_file", type=Path, help="Path to trace JSONL file")
    parser.add_argument("-o", "--output", type=Path, help="Output file (default: stdout)")
    args = parser.parse_args()
    
    if not args.trace_file.exists():
        print(f"Error: Trace file not found: {args.trace_file}", file=sys.stderr)
        sys.exit(1)
    
    traces = []
    with open(args.trace_file, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                try:
                    traces.append(json.loads(line))
                except json.JSONDecodeError:
                    continue
    
    otel_data = convert_traces_to_otel(traces)
    output = json.dumps(otel_data, indent=2)
    
    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(output)
        print(f"Exported to: {args.output}", file=sys.stderr)
    else:
        print(output)


if __name__ == "__main__":
    main()

