package com.altmemory.memorymcp;

import com.altmemory.memorymcp.embedding.EmbeddingService;
import com.altmemory.memorymcp.embedding.GeminiEmbeddingService;
import com.altmemory.memorymcp.store.MetadataStore;
import com.altmemory.memorymcp.store.VectorIndex;
import com.altmemory.memorymcp.tools.MemoryTools;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class MemoryServerApplication {

    public static void main(String[] args) throws Exception {
        String home = System.getenv().getOrDefault(
            "SHARED_MEMORY_HOME",
            System.getProperty("user.home") + "/.shared-memory-mcp");
        Files.createDirectories(Path.of(home));

        String dbPath = home + "/memory.db";
        String indexPath = home + "/vector-index";
        Files.createDirectories(Path.of(indexPath));

        String geminiApiKey = System.getenv("GEMINI_API_KEY");
        EmbeddingService embeddingService = new GeminiEmbeddingService(geminiApiKey);

        MetadataStore metadataStore = new MetadataStore(dbPath);
        VectorIndex vectorIndex = new VectorIndex(indexPath, embeddingService.dimensions());

        MemoryTools memoryTools = new MemoryTools(metadataStore, vectorIndex, embeddingService);
        List<McpServerFeatures.SyncToolSpecification> tools = memoryTools.buildAll();

        StdioServerTransportProvider transportProvider =
            new StdioServerTransportProvider(new ObjectMapper());

        McpSyncServer server = McpServer.sync(transportProvider)
            .serverInfo("shared-memory-mcp", "0.1.0")
            .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
            .tools(tools)
            .build();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                vectorIndex.close();
                metadataStore.close();
            } catch (Exception ignored) {
                // best-effort cleanup on shutdown
            }
        }));

        // McpServer.sync(...).build() starts listening on stdio; block here
        // so the JVM doesn't exit immediately.
        Thread.currentThread().join();
    }
}
