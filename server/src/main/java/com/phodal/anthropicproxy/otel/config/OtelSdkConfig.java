package com.phodal.anthropicproxy.otel.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.semconv.ServiceAttributes;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * OpenTelemetry SDK configuration.
 * Creates TracerProvider, SpanExporter, and Tracer beans when otel.sdk.enabled=true.
 */
@Slf4j
@Configuration
public class OtelSdkConfig {

    private SdkTracerProvider tracerProvider;

    @Bean
    public OpenTelemetry openTelemetry(OtelProperties properties) {
        if (!properties.isEnabled()) {
            log.info("OpenTelemetry SDK is disabled. Using no-op implementation.");
            return OpenTelemetry.noop();
        }

        log.info("Initializing OpenTelemetry SDK with service.name={}, environment={}",
                properties.getServiceName(), properties.getEnvironment());

        // Build resource with service info
        Resource resource = Resource.getDefault()
                .merge(Resource.create(Attributes.builder()
                        .put(ServiceAttributes.SERVICE_NAME, properties.getServiceName())
                        .put(ServiceAttributes.SERVICE_VERSION, properties.getServiceVersion())
                        .put("deployment.environment", properties.getEnvironment())
                        .build()));

        // Build span exporter
        SpanExporter spanExporter = buildSpanExporter(properties);

        // Build tracer provider with batch processor
        tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter)
                        .setScheduleDelay(Duration.ofSeconds(5))
                        .setMaxExportBatchSize(512)
                        .build())
                .build();

        // Build OpenTelemetry instance with W3C TraceContext propagation
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();

        log.info("OpenTelemetry SDK initialized successfully. OTLP endpoint: {}",
                properties.getOtlp().getEndpoint());

        return openTelemetry;
    }

    @Bean
    public Tracer tracer(OpenTelemetry openTelemetry, OtelProperties properties) {
        return openTelemetry.getTracer(properties.getServiceName(), properties.getServiceVersion());
    }

    private SpanExporter buildSpanExporter(OtelProperties properties) {
        OtelProperties.OtlpConfig otlp = properties.getOtlp();

        if (!otlp.isEnabled()) {
            log.info("OTLP exporter disabled, using logging exporter for debugging");
            return new LoggingSpanExporter();
        }

        String protocol = otlp.getProtocol().toLowerCase();
        Duration timeout = Duration.ofMillis(otlp.getTimeoutMs());

        if ("http/protobuf".equals(protocol) || "http".equals(protocol)) {
            log.info("Using OTLP HTTP exporter: {}", otlp.getEndpoint());
            var builder = OtlpHttpSpanExporter.builder()
                    .setEndpoint(otlp.getEndpoint())
                    .setTimeout(timeout);
            otlp.getHeaders().forEach(builder::addHeader);
            return builder.build();
        } else {
            // Default to gRPC
            log.info("Using OTLP gRPC exporter: {}", otlp.getEndpoint());
            var builder = OtlpGrpcSpanExporter.builder()
                    .setEndpoint(otlp.getEndpoint())
                    .setTimeout(timeout);
            otlp.getHeaders().forEach(builder::addHeader);
            return builder.build();
        }
    }

    @PreDestroy
    public void shutdown() {
        if (tracerProvider != null) {
            log.info("Shutting down OpenTelemetry TracerProvider...");
            tracerProvider.shutdown();
        }
    }

    /**
     * Simple logging exporter for debugging when OTLP is disabled.
     */
    private static class LoggingSpanExporter implements SpanExporter {
        @Override
        public CompletableResultCode export(java.util.Collection<SpanData> spans) {
            for (SpanData span : spans) {
                log.debug("OTEL Span: traceId={}, spanId={}, name={}, duration={}ms",
                        span.getTraceId(), span.getSpanId(), span.getName(),
                        (span.getEndEpochNanos() - span.getStartEpochNanos()) / 1_000_000);
            }
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
