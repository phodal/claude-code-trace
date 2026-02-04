package com.phodal.anthropicproxy.otel.model;

/**
 * OTEL Span Kind
 */
public enum SpanKind {
    /**
     * Server span (processing incoming request)
     */
    SERVER,
    
    /**
     * Client span (outgoing request to external service)
     */
    CLIENT,
    
    /**
     * Producer span (message/event producer)
     */
    PRODUCER,
    
    /**
     * Consumer span (message/event consumer)
     */
    CONSUMER,
    
    /**
     * Internal span (internal processing)
     */
    INTERNAL
}
