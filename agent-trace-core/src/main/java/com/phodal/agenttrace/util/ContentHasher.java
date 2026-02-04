package com.phodal.agenttrace.util;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Utility for computing content hashes for position-independent tracking.
 * Uses a lightweight hash suitable for content identification.
 */
public final class ContentHasher {
    
    private ContentHasher() {
        // Utility class
    }

    /**
     * Compute a content hash for the given content.
     * Returns a hash in format "crc32:hexvalue"
     * 
     * @param content The content to hash
     * @return Hash string in format "crc32:hexvalue"
     */
    public static String hash(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        
        CRC32 crc32 = new CRC32();
        crc32.update(content.getBytes(StandardCharsets.UTF_8));
        return String.format("crc32:%08x", crc32.getValue());
    }

    /**
     * Compute a hash for a range of lines.
     * 
     * @param content Full file content
     * @param startLine Start line (1-indexed)
     * @param endLine End line (1-indexed, inclusive)
     * @return Hash of the specified line range
     */
    public static String hashRange(String content, int startLine, int endLine) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        
        String[] lines = content.split("\n", -1);
        if (startLine < 1 || endLine > lines.length || startLine > endLine) {
            return null;
        }
        
        StringBuilder rangeContent = new StringBuilder();
        for (int i = startLine - 1; i < endLine; i++) {
            if (i > startLine - 1) {
                rangeContent.append("\n");
            }
            rangeContent.append(lines[i]);
        }
        
        return hash(rangeContent.toString());
    }
}
