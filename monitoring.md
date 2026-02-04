# Monitoring with OpenTelemetry

This project supports OpenTelemetry (OTel) metrics and events for monitoring and observability.

## Quick Start

Configure OpenTelemetry using environment variables:

```bash
# 1. Enable telemetry
export CLAUDE_CODE_ENABLE_TELEMETRY=1

# 2. Choose exporters
export OTEL_METRICS_EXPORTER=otlp       # Options: otlp, prometheus, console
export OTEL_LOGS_EXPORTER=otlp         # Options: otlp, console

# 3. Configure OTLP endpoint
export OTEL_EXPORTER_OTLP_PROTOCOL=grpc
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:8080

# 4. Set authentication (if required)
export OTEL_EXPORTER_OTLP_HEADERS="Authorization=Bearer your-token"
```

## Configuration Variables

| Environment Variable           | Description                  | Example                         |
|--------------------------------|------------------------------|---------------------------------|
| `CLAUDE_CODE_ENABLE_TELEMETRY` | Enables telemetry (required) | `1`                             |
| `OTEL_METRICS_EXPORTER`        | Metrics exporter             | `otlp`, `prometheus`, `console` |
| `OTEL_LOGS_EXPORTER`           | Logs exporter                | `otlp`, `console`               |
| `OTEL_EXPORTER_OTLP_ENDPOINT`  | OTLP collector endpoint      | `http://localhost:4317`         |
| `OTEL_EXPORTER_OTLP_PROTOCOL`  | Protocol                     | `grpc`, `http/json`             |
| `OTEL_EXPORTER_OTLP_HEADERS`   | Authentication headers       | `Authorization=Bearer token`    |

## Example: OTLP/gRPC

```bash
export CLAUDE_CODE_ENABLE_TELEMETRY=1
export OTEL_METRICS_EXPORTER=otlp
export OTEL_LOGS_EXPORTER=otlp
export OTEL_EXPORTER_OTLP_PROTOCOL=grpc
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
```

## Console Debugging

```bash
export CLAUDE_CODE_ENABLE_TELEMETRY=1
export OTEL_METRICS_EXPORTER=console
export OTEL_METRIC_EXPORT_INTERVAL=1000
```

## Multi-team Attributes

```bash
export OTEL_RESOURCE_ATTRIBUTES="department=engineering,team.id=platform"
```

## Available Metrics

| Metric | Description |
|--------|-------------|
| `claude_code.session.count` | CLI sessions started |
| `claude_code.lines_of_code.count` | Code modifications |
| `claude_code.cost.usage` | Session cost (USD) |
| `claude_code.token.usage` | Tokens used |
| `claude_code.active_time.total` | Active time (seconds) |

## Prometheus

```bash
export CLAUDE_CODE_ENABLE_TELEMETRY=1
export OTEL_METRICS_EXPORTER=prometheus
```

## Security

- Telemetry is opt-in
- Prompt content is redacted by default
- Set `OTEL_LOG_USER_PROMPTS=1` to log prompt content (not recommended for production)
