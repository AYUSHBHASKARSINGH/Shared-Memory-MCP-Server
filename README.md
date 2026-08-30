# shared-memory-mcp

A persistent, shared context layer for AI coding tools. Both Claude Desktop
and Cursor can be pointed at this MCP server, so a note stored from one is
recallable — by meaning, not just by keyword — `from the other. Think of it
as a RAM module for your agents: fast, structured, and always available,
except that unlike RAM it doesn't forget when the session ends.

## Why

Switching between Claude Desktop and Cursor resets context. Ideas get lost,
scratch notes get forgotten, and you end up repeating yourself. This server
gives both tools a shared place to write down what they're doing and pull it
back up later — including recall by meaning ("what did I decide about the
retry logic?") rather than requiring the exact original wording.

## Architecture

```
Claude Desktop  ─┐
                  ├─ (stdio, MCP) ─▶  shared-memory-mcp server
Cursor          ─┘                         │
                                            ├─ SQLite   (raw content + metadata: tags, session, source, timestamp)
                                            └─ Lucene   (KNN vector index, cosine similarity, for semantic recall)
                                            │
                                            └─ Embedding provider (Gemini text-embedding-004, pluggable)
```

- **MCP layer**: Java MCP SDK, stdio transport. Each client (Claude Desktop,
  Cursor) launches this as a subprocess; both processes point at the same
  on-disk DB and index, which is what makes memory "shared" rather than
  per-tool.
- **Metadata store (SQLite)**: source of truth for content and metadata.
  Always sufficient to fully rebuild the vector index if needed.
- **Vector index (Lucene)**: `KnnFloatVectorField` with cosine similarity for
  semantic search. Stores only `id -> vector`; content lookups go through
  SQLite.
- **Embedding service**: an interface (`EmbeddingService`) with a Gemini
  implementation. Swappable for a local model (e.g. via DJL + an ONNX
  sentence-transformer) if you want the server to run fully offline.

## Tools exposed

| Tool | Purpose |
|---|---|
| `store_memory` | Save a note/plan/context snippet, with optional tags, session id, source tool |
| `semantic_search` | Find memories closest in meaning to a query, even with different wording |
| `get_recent` | Chronological retrieval, optionally scoped to a session |
| `delete_memory` | Remove a memory by id |

## Setup

**1. Get a Gemini API key** (or swap in your own `EmbeddingService`
implementation — see `embedding/EmbeddingService.java`).

**2. Build:**
```bash
mvn clean package
```
This produces `target/shared-memory-mcp.jar` (a shaded/fat jar with all
dependencies bundled).

**3. Wire it into Claude Desktop and Cursor.** Both use the same
`mcpServers` config shape — see `mcp-config-example.json`. Point `command`
at `java`, `args` at the built jar, and set `GEMINI_API_KEY` (and optionally
`SHARED_MEMORY_HOME` to control where the DB/index live — defaults to
`~/.shared-memory-mcp`).

**4. Restart both tools.** They should now be able to call `store_memory`
and `semantic_search` — try storing something from one and recalling it from
the other.

## A note on stdio transport

MCP over stdio uses stdout as the JSON-RPC message channel. Any stray
`System.out.println` (from this code, a dependency, or a misconfigured
logger) will corrupt the protocol stream and silently break the connection.
This project routes all logging to stderr via `slf4j-simple`
(`src/main/resources/simplelogger.properties`) — keep it that way if you add
new dependencies that log by default.

## Known gaps / roadmap

- **No hybrid search yet.** `semantic_search` is pure vector similarity;
  combining it with tag/keyword filtering (Lucene supports this natively —
  a `BooleanQuery` wrapping the `KnnFloatVectorQuery`) would improve
  precision for narrow queries.
- **No eviction/TTL.** Memories accumulate indefinitely. A staleness policy
  (e.g. decay old, low-relevance entries) would keep recall quality high as
  the store grows.
- **Single embedding provider wired in.** `EmbeddingService` is an
  interface specifically so a local/offline model can replace Gemini
  without touching the rest of the codebase.
- **No auth/multi-user isolation.** Fine for a single developer's local
  setup; would need per-user namespacing to be shared across a team.
- **Index rebuild tooling.** `MetadataStore.getAll()` returns everything
  needed to rebuild the Lucene index from scratch, but there's no CLI
  command wired up for it yet.

## Project layout

```
src/main/java/com/altmemory/memorymcp/
├── MemoryServerApplication.java   # entry point, wiring
├── model/MemoryEntry.java
├── store/
│   ├── MetadataStore.java         # SQLite: content + metadata
│   └── VectorIndex.java           # Lucene: KNN vector search
├── embedding/
│   ├── EmbeddingService.java      # interface
│   └── GeminiEmbeddingService.java
└── tools/
    └── MemoryTools.java           # MCP tool definitions/handlers
```
