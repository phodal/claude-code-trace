# OpenTelemetry Integration Guide

## Overview

The Anthropic Proxy now includes OpenTelemetry (OTEL) compatible tracing for comprehensive observability of Claude cODE requests. This integration provides detailed insights into request lifecycle, API interactions, and performance metrics.

## Features

- **OTEL-Compatible Traces**: Full support for distributed tracing with trace IDs and span hierarchies
- **Multiple Exporters**: Export traces to Jaeger, Zipkin, or console logs
- **Automatic Instrumentation**: Proxy requests are automatically instrumented with spans
- **RESTful API**: Query and export traces via HTTP endpoints
- **Span Attributes**: Rich metadata including model, tokens, latency, and custom attributes

## Configuration

Configure OTEL exporters in `application.yml`:

```yaml
otel:
  exporter:
    # Console exporter (enabled by default for debugging)
    console:
      enabled: true
    
    # Jaeger exporter
    jaeger:
      enabled: false
      endpoint: http://localhost:14250
    
    # Zipkin exporter
    zipkin:
      enabled: false
      endpoint: http://localhost:9411
```

### Environment Variables

You can also configure exporters using environment variables:

```bash
# Enable Jaeger
export OTEL_JAEGER_ENABLED=true
export OTEL_JAEGER_ENDPOINT=http://jaeger:14250

# Enable Zipkin
export OTEL_ZIPKIN_ENABLED=true
export OTEL_ZIPKIN_ENDPOINT=http://zipkin:9411
```

## API Endpoints

### Get Recent Traces

```bash
curl http://localhost:8080/otel/traces?limit=50
```

Returns a list of recent traces with summary information.

### Get Specific Trace

```bash
curl http://localhost:8080/otel/traces/{traceId}
```

Returns detailed information about a specific trace including all spans.

### Export Trace

```bash
curl -X POST http://localhost:8080/otel/traces/{traceId}/export
```

Manually export a specific trace to all enabled exporters.

### Get Exporter Status

```bash
curl http://localhost:8080/otel/exporters
```

Returns the status of all configured exporters.

### Get OTEL Metrics

```bash
curl http://localhost:8080/otel/metrics
```

Returns summary metrics about traces and spans.

### Clear All Traces

```bash
curl -X DELETE http://localhost:8080/otel/traces
```

Clears all stored traces (useful for testing).

## Trace Structure

Each request to the proxy generates a trace with the following span hierarchy:

```
anthropic.messages (SERVER)
└── anthropic.api.call (CLIENT)
    or
└── anthropic.api.stream (CLIENT)
```

### Span Attributes

Each span includes attributes following OTEL semantic conventions:

**Root Span (anthropic.messages)**:
- `http.method`: HTTP method (POST)
- `http.route`: Request route
- `model`: Claude model used
- `stream`: Whether streaming is enabled
- `user.id`: User identifier
- `turn.id`: Internal turn/message ID

**API Call Span (anthropic.api.call/stream)**:
- `api.endpoint`: API endpoint name
- `api.model`: Model name
- `streaming`: Whether streaming is used
- `response.id`: Response ID (for non-streaming)
- `tokens.input`: Input token count
- `tokens.output`: Output token count

## Integration with Monitoring Tools

### Jaeger

1. Start Jaeger:
```bash
docker run -d --name jaeger \
  -p 14250:14250 \
  -p 16686:16686 \
  jaegertracing/all-in-one:latest
```

2. Enable Jaeger exporter:
```bash
export OTEL_JAEGER_ENABLED=true
export OTEL_JAEGER_ENDPOINT=http://localhost:14250
```

3. View traces at http://localhost:16686

### Zipkin

1. Start Zipkin:
```bash
docker run -d --name zipkin \
  -p 9411:9411 \
  openzipkin/zipkin:latest
```

2. Enable Zipkin exporter:
```bash
export OTEL_ZIPKIN_ENABLED=true
export OTEL_ZIPKIN_ENDPOINT=http://localhost:9411
```

3. View traces at http://localhost:9411

## Example Usage

### Send a Request

```bash
curl -X POST http://localhost:8080/anthropic/v1/messages \
  -H "Content-Type: application/json" \
  -H "x-api-key: your_api_key" \
  -d '{
    "model": "claude-sonnet-4-20250514",
    "max_tokens": 1024,
    "messages": [
      {"role": "user", "content": "Hello, Claude!"}
    ]
  }'
```

### View the Generated Trace

```bash
# Get recent traces
curl http://localhost:8080/otel/traces?limit=1

# Get specific trace (use traceId from above)
curl http://localhost:8080/otel/traces/{traceId}
```

### Example Trace Output

```json
{
  "traceId": "a1b2c3d4e5f6g7h8",
  "spans": [
    {
      "traceId": "a1b2c3d4e5f6g7h8",
      "spanId": "span001",
      "parentSpanId": "",
      "name": "anthropic.messages",
      "kind": "SERVER",
      "startTimeUnixNano": 1612345678000000000,
      "endTimeUnixNano": 1612345679500000000,
      "attributes": {
        "http.method": "POST",
        "http.route": "/anthropic/v1/messages",
        "model": "claude-sonnet-4-20250514",
        "stream": false,
        "user.id": "user123",
        "turn.id": "turn456"
      },
      "status": {
        "code": "OK",
        "message": ""
      }
    },
    {
      "traceId": "a1b2c3d4e5f6g7h8",
      "spanId": "span002",
      "parentSpanId": "span001",
      "name": "anthropic.api.call",
      "kind": "CLIENT",
      "startTimeUnixNano": 1612345678100000000,
      "endTimeUnixNano": 1612345679400000000,
      "attributes": {
        "api.endpoint": "messages",
        "api.model": "claude-sonnet-4-20250514",
        "response.id": "msg_abc123",
        "tokens.input": 15,
        "tokens.output": 42
      },
      "status": {
        "code": "OK",
        "message": ""
      }
    }
  ]
}
```

## Architecture

The OTEL integration consists of the following components:

- **TraceService**: Manages trace and span lifecycle
- **ExporterService**: Coordinates multiple trace exporters
- **JaegerExporter**: Exports traces to Jaeger
- **ZipkinExporter**: Exports traces to Zipkin
- **ConsoleExporter**: Logs traces to console
- **OtelController**: Provides REST API for trace access

## Extensibility

### Adding Custom Exporters

To add a new exporter:

1. Implement the `TraceExporter` interface:

```java
@Component
public class CustomExporter implements TraceExporter {
    @Override
    public void export(Trace trace) {
        // Export logic here
    }
    
    @Override
    public String getName() {
        return "Custom";
    }
    
    @Override
    public boolean isEnabled() {
        return true;
    }
}
```

2. The exporter will be automatically registered with `ExporterService`

### Adding Custom Span Attributes

Add custom attributes to spans in your code:

```java
span.addAttribute("custom.key", "custom.value");
```

## Performance Considerations

- Traces are stored in memory with a limit of 1000 completed traces
- Exporters run asynchronously to avoid blocking request processing
- Console exporter is enabled by default but can be disabled in production
- Each trace includes all spans for the complete request lifecycle

## Troubleshooting

### Traces Not Appearing in Jaeger/Zipkin

1. Check exporter is enabled in configuration
2. Verify endpoint URL is correct
3. Check network connectivity to exporter
4. Review application logs for export errors

### High Memory Usage

Reduce the number of stored traces by modifying `MAX_COMPLETED_TRACES` in `TraceService`.

### Missing Span Attributes

Ensure attributes are added before calling `endSpan()`.

## References

- [OpenTelemetry Specification](https://opentelemetry.io/docs/specs/otel/)
- [Jaeger Documentation](https://www.jaegertracing.io/docs/)
- [Zipkin Documentation](https://zipkin.io/pages/quickstart)
