package com.altmemory.memorymcp.embedding;

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
