package com.phodal.anthropicproxy.otel.service;

import com.phodal.anthropicproxy.otel.exporter.TraceExporter;
import com.phodal.anthropicproxy.otel.model.Trace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service to manage and coordinate trace exporters
 */
@Slf4j
@Service
public class ExporterService {
    
    private final List<TraceExporter> exporters;
    
    public ExporterService(List<TraceExporter> exporters) {
        this.exporters = exporters;
        log.info("Initialized ExporterService with {} exporters", exporters.size());
        exporters.forEach(exporter -> 
            log.info("  - {}: {}", exporter.getName(), 
                    exporter.isEnabled() ? "enabled" : "disabled")
        );
    }
    
    /**
     * Export trace to all enabled exporters
     */
    public void exportTrace(Trace trace) {
        exporters.stream()
                .filter(TraceExporter::isEnabled)
                .forEach(exporter -> {
                    try {
                        exporter.export(trace);
                    } catch (Exception e) {
                        log.error("Error exporting trace to {}: {}", 
                                exporter.getName(), e.getMessage(), e);
                    }
                });
    }
    
    /**
     * Get list of all exporters
     */
    public List<TraceExporter> getExporters() {
        return List.copyOf(exporters);
    }
}
