package com.altmemory.memorymcp.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;


public class GeminiEmbeddingService implements EmbeddingService {

    private static final String ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:embedContent";
    private static final int DIMENSIONS = 768;

    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public GeminiEmbeddingService(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException(
                "GEMINI_API_KEY is required for GeminiEmbeddingService");
        }
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @Override
    public float[] embed(String text) throws Exception {
        Map<String, Object> body = Map.of(
                "model", "models/gemini-embedding-001",
                "content", Map.of("parts", new Object[]{ Map.of("text", text) }),
                "outputDimensionality", DIMENSIONS
        );
        String json = mapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(ENDPOINT))
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", apiKey)
            .timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();

        HttpResponse<String> response =
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException(
                "Gemini embedding call failed (" + response.statusCode() + "): " + response.body());
        }

        JsonNode root = mapper.readTree(response.body());
        JsonNode values = root.path("embedding").path("values");
        if (!values.isArray()) {
            throw new RuntimeException("Unexpected embedding response shape: " + response.body());
        }

        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            vector[i] = (float) values.get(i).asDouble();
        }
        return vector;
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }
}
