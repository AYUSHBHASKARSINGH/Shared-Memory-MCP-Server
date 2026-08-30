package com.altmemory.memorymcp.store;

import com.altmemory.memorymcp.model.MemoryEntry;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Source of truth for raw memory content + metadata. Lucene holds the
 * vector index for semantic search, but this SQLite DB is what get_recent /
 * delete_memory ultimately read and write. Rebuilding the Lucene index from
 * this table is always possible if the index gets corrupted or its schema
 * changes.
 */
public class MetadataStore implements AutoCloseable {

    private final Connection connection;

    public MetadataStore(String dbPath) throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        init();
    }

    private void init() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS memory_entries (
                    id TEXT PRIMARY KEY,
                    content TEXT NOT NULL,
                    tags TEXT,
                    session_id TEXT,
                    source_tool TEXT,
                    created_at TEXT NOT NULL
                )
                """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_session ON memory_entries(session_id)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_created ON memory_entries(created_at)");
        }
    }

    public void insert(MemoryEntry entry) throws SQLException {
        String sql = """
            INSERT INTO memory_entries (id, content, tags, session_id, source_tool, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, entry.getId());
            ps.setString(2, entry.getContent());
            ps.setString(3, entry.tagsAsCsv());
            ps.setString(4, entry.getSessionId());
            ps.setString(5, entry.getSourceTool());
            ps.setString(6, entry.getCreatedAt().toString());
            ps.executeUpdate();
        }
    }

    public MemoryEntry getById(String id) throws SQLException {
        String sql = "SELECT * FROM memory_entries WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public List<MemoryEntry> getRecent(int limit, String sessionId) throws SQLException {
        String sql = sessionId == null
            ? "SELECT * FROM memory_entries ORDER BY created_at DESC LIMIT ?"
            : "SELECT * FROM memory_entries WHERE session_id = ? ORDER BY created_at DESC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int idx = 1;
            if (sessionId != null) ps.setString(idx++, sessionId);
            ps.setInt(idx, limit);
            try (ResultSet rs = ps.executeQuery()) {
                List<MemoryEntry> results = new ArrayList<>();
                while (rs.next()) results.add(map(rs));
                return results;
            }
        }
    }

    public List<MemoryEntry> getAll() throws SQLException {
        String sql = "SELECT * FROM memory_entries ORDER BY created_at ASC";
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            List<MemoryEntry> results = new ArrayList<>();
            while (rs.next()) results.add(map(rs));
            return results;
        }
    }

    public boolean delete(String id) throws SQLException {
        String sql = "DELETE FROM memory_entries WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    private MemoryEntry map(ResultSet rs) throws SQLException {
        String tagsCsv = rs.getString("tags");
        List<String> tags = (tagsCsv == null || tagsCsv.isBlank())
            ? List.of() : Arrays.asList(tagsCsv.split(","));
        return new MemoryEntry(
            rs.getString("id"),
            rs.getString("content"),
            tags,
            rs.getString("session_id"),
            rs.getString("source_tool"),
            Instant.parse(rs.getString("created_at"))
        );
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
