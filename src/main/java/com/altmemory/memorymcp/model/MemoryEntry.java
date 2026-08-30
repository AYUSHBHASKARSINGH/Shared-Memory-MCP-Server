package com.altmemory.memorymcp.model;

import java.time.Instant;
import java.util.List;

/**
 * A single unit of shared context: something a user told Claude Desktop or
 * Cursor that should be recallable later, from either tool.
 */
public class MemoryEntry {

    private final String id;
    private final String content;
    private final List<String> tags;
    private final String sessionId;
    private final String sourceTool;
    private final Instant createdAt;

    public MemoryEntry(String id, String content, List<String> tags,
                        String sessionId, String sourceTool, Instant createdAt) {
        this.id = id;
        this.content = content;
        this.tags = tags;
        this.sessionId = sessionId;
        this.sourceTool = sourceTool;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getContent() { return content; }
    public List<String> getTags() { return tags; }
    public String getSessionId() { return sessionId; }
    public String getSourceTool() { return sourceTool; }
    public Instant getCreatedAt() { return createdAt; }

    public String tagsAsCsv() {
        return tags == null || tags.isEmpty() ? "" : String.join(",", tags);
    }
}
