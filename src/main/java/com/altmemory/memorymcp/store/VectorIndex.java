package com.altmemory.memorymcp.store;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.*;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Semantic index: one Lucene document per memory entry, holding the entry's
 * id and its embedding vector. Cosine similarity is used so that vector
 * magnitude (roughly, text length) doesn't skew relevance.
 *
 * This class only ever stores the id + vector -- the actual content lives in
 * MetadataStore (SQLite). Keeping them separate means the index can be
 * dropped and rebuilt from SQLite at any time without data loss.
 */
public class VectorIndex implements AutoCloseable {

    private static final String FIELD_ID = "id";
    private static final String FIELD_VECTOR = "vector";
    private static final int LOCK_RETRY_ATTEMPTS = 5;
    private static final long LOCK_RETRY_DELAY_MS = 200;

    private final Directory directory;
    private final int dimensions;

    public VectorIndex(String indexPath, int dimensions) throws IOException {
        this.dimensions = dimensions;
        this.directory = FSDirectory.open(Path.of(indexPath));
        // Touch the index once at startup so it exists on disk even before
        // the first write -- this open+close is itself transient.
        withWriter(writer -> null);
    }

    public void upsert(String id, float[] vector) throws IOException {
        if (vector.length != dimensions) {
            throw new IllegalArgumentException(
                "Vector has " + vector.length + " dims, index expects " + dimensions);
        }
        withWriter(writer -> {
            // Remove any prior vector for this id, then add the new one --
            // Lucene has no in-place update for a single field.
            writer.deleteDocuments(new Term(FIELD_ID, id));

            Document doc = new Document();
            doc.add(new StringField(FIELD_ID, id, Field.Store.YES));
            doc.add(new KnnFloatVectorField(FIELD_VECTOR, vector, VectorSimilarityFunction.COSINE));
            writer.addDocument(doc);
            return null;
        });
    }

    public void delete(String id) throws IOException {
        withWriter(writer -> {
            writer.deleteDocuments(new Term(FIELD_ID, id));
            return null;
        });
    }

    /**
     * @return ids of the topK nearest memory entries to the query vector,
     *         ordered most-similar first, paired with their similarity score.
     */
    public List<ScoredId> search(float[] queryVector, int topK) throws IOException {
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            if (reader.numDocs() == 0) {
                return List.of();
            }
            IndexSearcher searcher = new IndexSearcher(reader);
            int k = Math.min(topK, reader.numDocs());
            KnnFloatVectorQuery query = new KnnFloatVectorQuery(FIELD_VECTOR, queryVector, k);
            TopDocs topDocs = searcher.search(query, k);

            StoredFields storedFields = reader.storedFields();
            List<ScoredId> results = new ArrayList<>();
            for (ScoreDoc sd : topDocs.scoreDocs) {
                Document doc = storedFields.document(sd.doc);
                results.add(new ScoredId(doc.get(FIELD_ID), sd.score));
            }
            return results;
        }
    }

    /**
     * Opens an IndexWriter just long enough to run the given mutation, then
     * commits and closes it -- releasing write.lock immediately. If another
     * process holds the lock at this exact instant (a brief, genuine race
     * between two writes, not the permanent standoff this replaces), retry
     * a few times with a short backoff before giving up.
     */
    private void withWriter(WriterAction action) throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        int attempt = 0;
        while (true) {
            try (IndexWriter writer = new IndexWriter(directory, config)) {
                action.run(writer);
                writer.commit();
                return;
            } catch (LockObtainFailedException e) {
                attempt++;
                if (attempt >= LOCK_RETRY_ATTEMPTS) {
                    throw new IOException(
                        "Could not obtain Lucene write lock after " + attempt +
                        " attempts -- another process may be mid-write. Try again.", e);
                }
                try {
                    Thread.sleep(LOCK_RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for Lucene write lock", ie);
                }
            }
        }
    }

    @FunctionalInterface
    private interface WriterAction {
        Void run(IndexWriter writer) throws IOException;
    }

    @Override
    public void close() throws IOException {
        directory.close();
    }

    public record ScoredId(String id, float score) {}
}
