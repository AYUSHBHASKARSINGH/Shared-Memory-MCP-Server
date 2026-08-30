package com.altmemory.memorymcp.embedding;

/**
 * Abstraction over whatever produces embedding vectors. Kept separate from
 * the vector index so the provider (Gemini today, a local ONNX model via DJL
 * tomorrow) can be swapped without touching Lucene or SQLite code.
 */
public interface EmbeddingService {

    /**
     * @return a fixed-length embedding vector for the given text.
     */
    float[] embed(String text) throws Exception;

    /**
     * Dimensionality of vectors this service produces. Must match the
     * dimension the Lucene vector field was created with.
     */
    int dimensions();
}
