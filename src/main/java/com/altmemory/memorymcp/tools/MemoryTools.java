package com.altmemory.memorymcp.tools;

import com.altmemory.memorymcp.embedding.EmbeddingService;
import com.altmemory.memorymcp.model.MemoryEntry;
import com.altmemory.memorymcp.store.MetadataStore;
import com.altmemory.memorymcp.store.VectorIndex;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class MemoryTools {

    private final MetadataStore metadataStore;
    private final VectorIndex vectorIndex;
    private final EmbeddingService embeddingService;

    public MemoryTools(MetadataStore metadataStore, VectorIndex vectorIndex,
                        EmbeddingService embeddingService) {
        this.metadataStore = metadataStore;
        this.vectorIndex = vectorIndex;
        this.embeddingService = embeddingService;
    }

    public List<McpServerFeatures.SyncToolSpecification> buildAll() {
        List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();
        specs.add(storeMemoryTool());
        specs.add(semanticSearchTool());
        specs.add(getRecentTool());
        specs.add(deleteMemoryTool());
        return specs;
    }

    // ---- store_memory ----------------------------------------------------

    private McpServerFeatures.SyncToolSpecification storeMemoryTool() {
        String schema = """
            {
              "type": "object",
              "properties": {
                "content": { "type": "string", "description": "The note, plan, or context to remember." },
                "tags": { "type": "array", "items": { "type": "string" }, "description": "Optional labels for filtering later." },
                "session_id": { "type": "string", "description": "Optional identifier grouping related memories, e.g. a project name." },
                "source_tool": { "type": "string", "description": "Which agent is writing this, e.g. 'claude-desktop' or 'cursor'." }
              },
              "required": ["content"]
            }
            """;

        McpSchema.Tool tool = new McpSchema.Tool(
            "store_memory",
            "Persist a note, plan, or piece of context so it can be recalled later by name or by meaning, from any connected tool.",
            schema);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, arguments) -> {
            try {
                String content = (String) arguments.get("content");
                if (content == null || content.isBlank()) {
                    return errorResult("content is required");
                }
                @SuppressWarnings("unchecked")
                List<String> tags = arguments.get("tags") != null
                    ? (List<String>) arguments.get("tags") : List.of();
                String sessionId = (String) arguments.getOrDefault("session_id", null);
                String sourceTool = (String) arguments.getOrDefault("source_tool", "unknown");

                String id = UUID.randomUUID().toString();
                MemoryEntry entry = new MemoryEntry(id, content, tags, sessionId, sourceTool, Instant.now());

                metadataStore.insert(entry);
                float[] vector = embeddingService.embed(content);
                vectorIndex.upsert(id, vector);

                return textResult("Stored memory " + id);
            } catch (Exception e) {
                return errorResult("Failed to store memory: " + e.getMessage());
            }
        });
    }

    // ---- semantic_search ---------------------------------------------------

    private McpServerFeatures.SyncToolSpecification semanticSearchTool() {
        String schema = """
            {
              "type": "object",
              "properties": {
                "query": { "type": "string", "description": "What you're trying to recall, in natural language." },
                "top_k": { "type": "integer", "description": "Max number of results (default 5)." }
              },
              "required": ["query"]
            }
            """;

        McpSchema.Tool tool = new McpSchema.Tool(
            "semantic_search",
            "Find previously stored memories whose meaning is closest to the query, even if the wording differs.",
            schema);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, arguments) -> {
            try {
                String query = (String) arguments.get("query");
                if (query == null || query.isBlank()) {
                    return errorResult("query is required");
                }
                int topK = arguments.get("top_k") != null
                    ? ((Number) arguments.get("top_k")).intValue() : 5;

                float[] queryVector = embeddingService.embed(query);
                List<VectorIndex.ScoredId> hits = vectorIndex.search(queryVector, topK);

                if (hits.isEmpty()) {
                    return textResult("No matching memories found.");
                }

                StringBuilder sb = new StringBuilder();
                for (VectorIndex.ScoredId hit : hits) {
                    MemoryEntry entry = metadataStore.getById(hit.id());
                    if (entry == null) continue;
                    sb.append(String.format("[%.3f] (%s, %s) %s%n",
                        hit.score(), entry.getSourceTool(), entry.getCreatedAt(), entry.getContent()));
                }
                return textResult(sb.toString());
            } catch (Exception e) {
                return errorResult("Search failed: " + e.getMessage());
            }
        });
    }

    // ---- get_recent ---------------------------------------------------

    private McpServerFeatures.SyncToolSpecification getRecentTool() {
        String schema = """
            {
              "type": "object",
              "properties": {
                "limit": { "type": "integer", "description": "Max number of results (default 10)." },
                "session_id": { "type": "string", "description": "Optional: only return memories from this session." }
              }
            }
            """;

        McpSchema.Tool tool = new McpSchema.Tool(
            "get_recent",
            "Retrieve the most recently stored memories in chronological order, optionally scoped to a session.",
            schema);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, arguments) -> {
            try {
                int limit = arguments.get("limit") != null
                    ? ((Number) arguments.get("limit")).intValue() : 10;
                String sessionId = (String) arguments.getOrDefault("session_id", null);

                List<MemoryEntry> entries = metadataStore.getRecent(limit, sessionId);
                if (entries.isEmpty()) {
                    return textResult("No memories stored yet.");
                }
                String text = entries.stream()
                    .map(e -> String.format("(%s, %s) %s", e.getSourceTool(), e.getCreatedAt(), e.getContent()))
                    .collect(Collectors.joining("\n"));
                return textResult(text);
            } catch (Exception e) {
                return errorResult("Failed to fetch recent memories: " + e.getMessage());
            }
        });
    }

    // ---- delete_memory ---------------------------------------------------

    private McpServerFeatures.SyncToolSpecification deleteMemoryTool() {
        String schema = """
            {
              "type": "object",
              "properties": {
                "id": { "type": "string", "description": "The id of the memory to delete." }
              },
              "required": ["id"]
            }
            """;

        McpSchema.Tool tool = new McpSchema.Tool(
            "delete_memory",
            "Permanently remove a stored memory by id.",
            schema);

        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, arguments) -> {
            try {
                String id = (String) arguments.get("id");
                if (id == null || id.isBlank()) {
                    return errorResult("id is required");
                }
                boolean deleted = metadataStore.delete(id);
                vectorIndex.delete(id);
                return textResult(deleted ? "Deleted " + id : "No memory found with id " + id);
            } catch (Exception e) {
                return errorResult("Delete failed: " + e.getMessage());
            }
        });
    }

    // ---- helpers ---------------------------------------------------

    private McpSchema.CallToolResult textResult(String text) {
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(text)), false);
    }

    private McpSchema.CallToolResult errorResult(String text) {
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(text)), true);
    }
}
