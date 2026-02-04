package com.phodal.anthropicproxy.otel.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OTEL Span Status
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpanStatus {
    
    private StatusCode code;
    private String message;
    
    /**
     * Status codes as per OTEL specification
     */
    public enum StatusCode {
        /**
         * The operation completed successfully
         */
        OK,
        
        /**
         * The operation contains an error
         */
        ERROR,
        
        /**
         * The default status (unset)
         */
        UNSET
    }
    
    public static SpanStatus ok() {
        return SpanStatus.builder()
                .code(StatusCode.OK)
                .build();
    }
    
    public static SpanStatus error(String message) {
        return SpanStatus.builder()
                .code(StatusCode.ERROR)
                .message(message)
                .build();
    }
    
    public static SpanStatus unset() {
        return SpanStatus.builder()
                .code(StatusCode.UNSET)
                .build();
    }
}
