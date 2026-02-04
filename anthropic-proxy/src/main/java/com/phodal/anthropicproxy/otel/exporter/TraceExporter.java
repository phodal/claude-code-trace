package com.phodal.anthropicproxy.otel.exporter;

import com.phodal.anthropicproxy.otel.model.Trace;

/**
 * Interface for OTEL trace exporters
 */
public interface TraceExporter {
    
    /**
     * Export a trace to the target system
     */
    void export(Trace trace);
    
    /**
     * Get exporter name
     */
    String getName();
    
    /**
     * Check if exporter is enabled
     */
    boolean isEnabled();
}
