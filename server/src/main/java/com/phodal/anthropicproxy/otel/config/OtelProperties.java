package com.phodal.anthropicproxy.otel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration properties for OpenTelemetry.
 * Maps to otel.sdk.* in application.yml
 */
@Data
@Component
@ConfigurationProperties(prefix = "otel.sdk")
public class OtelProperties {
    
    /**
     * Whether to enable OpenTelemetry SDK tracing (default: false).
     * When disabled, a no-op tracer is used.
     */
    private boolean enabled = false;
    
    /**
     * Service name reported in traces.
     */
    private String serviceName = "anthropic-proxy";
    
    /**
     * Service version reported in traces.
     */
    private String serviceVersion = "1.0.0";
    
    /**
     * Environment (e.g., dev, staging, prod).
     */
    private String environment = "dev";
    
    /**
     * OTLP exporter configuration.
     */
    private OtlpConfig otlp = new OtlpConfig();
    
    @Data
    public static class OtlpConfig {
        /**
         * Whether to enable OTLP export (default: true when SDK is enabled).
         */
        private boolean enabled = true;
        
        /**
         * OTLP endpoint URL.
         * For gRPC: http://localhost:4317
         * For HTTP/protobuf: http://localhost:4318/v1/traces
         */
        private String endpoint = "http://localhost:4317";
        
        /**
         * Protocol: grpc or http/protobuf.
         */
        private String protocol = "grpc";
        
        /**
         * Headers to send with OTLP requests (e.g., for authentication).
         */
        private Map<String, String> headers = new HashMap<>();
        
        /**
         * Timeout for OTLP requests in milliseconds.
         */
        private long timeoutMs = 10000;
    }
}
