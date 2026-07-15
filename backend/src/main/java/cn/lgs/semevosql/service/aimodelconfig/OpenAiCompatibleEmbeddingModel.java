/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.lgs.semevosql.service.aimodelconfig;

import cn.lgs.semevosql.util.JsonUtil;
import cn.lgs.semevosql.semantic.retrieval.AtomicEmbeddingIdentity;
import cn.lgs.semevosql.semantic.retrieval.CertifiedEmbeddingModel;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Minimal OpenAI-compatible embedding adapter.
 *
 * <p>The Spring AI 1.1.0 OpenAI embedding request DTO is a generic record. In the packaged SemEvoSQL runtime we observed
 * that request serializing to an empty JSON object even though the same client behaved correctly in an isolated unit
 * test. SemEvoSQL only needs the stable OpenAI-compatible contract ({@code input}, {@code model}, optional
 * {@code dimensions}), so this adapter sends that contract explicitly as a map and parses the standard
 * {@code data[].embedding} response. This also keeps provider compatibility independent of Spring AI's internal DTO
 * representation.
 */
final class OpenAiCompatibleEmbeddingModel implements CertifiedEmbeddingModel {

	private static final String DEFAULT_EMBEDDINGS_PATH = "/v1/embeddings";

	private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingModel.class);

	private final WebClient restClient;
    private final java.time.Duration requestBudget;

	private final String embeddingsPath;

	private final String defaultModel;

	private final Integer defaultDimensions;

	OpenAiCompatibleEmbeddingModel(WebClient.Builder builder, String baseUrl, String apiKey, String embeddingsPath,
			String defaultModel, Integer defaultDimensions, java.time.Duration requestBudget) {
        this.requestBudget=requestBudget;
		ModelEndpointResolver.Endpoint endpoint = ModelEndpointResolver.resolve(baseUrl, embeddingsPath,
				DEFAULT_EMBEDDINGS_PATH);
		WebClient.Builder configured = builder.baseUrl(endpoint.baseUrl())
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
		if (StringUtils.hasText(apiKey)) {
			configured.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
		}
		this.restClient = configured.build();
		this.embeddingsPath = endpoint.path();
		this.defaultModel = defaultModel;
		if (defaultDimensions != null && (defaultDimensions < 1 || defaultDimensions > 65536)) {
			throw new IllegalArgumentException("Invalid embedding dimensions");
		}
		this.defaultDimensions = defaultDimensions;
	}

	@Override
	public float[] embed(Document document) {
		EmbeddingResponse response = call(new EmbeddingRequest(List.of(document.getText()), EmbeddingOptions.builder().build()));
		if (response.getResults().isEmpty()) {
			return new float[0];
		}
		return response.getResults().get(0).getOutput();
	}

	@Override
	public EmbeddingResponse call(EmbeddingRequest request) {
		if (request == null || request.getInstructions() == null || request.getInstructions().isEmpty()) {
			return new EmbeddingResponse(List.of());
		}
		// Embedding is an optional retrieval channel. Do not apply the generic model retry policy here: a stalled
		// embedding provider must yield quickly so SemanticRetrievalIndexService can fall back to authorized PostgreSQL FTS and keep
		// MCP search responsive.
		return invoke(request);
	}

	private EmbeddingResponse invoke(EmbeddingRequest request) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("input", List.copyOf(request.getInstructions()));
		String model = request.getOptions() != null && StringUtils.hasText(request.getOptions().getModel())
				? request.getOptions().getModel() : defaultModel;
		body.put("model", model);
		Integer dimensions = request.getOptions() != null && request.getOptions().getDimensions() != null
                ? request.getOptions().getDimensions() : defaultDimensions;
        if (defaultDimensions != null && !defaultDimensions.equals(dimensions)) {
            throw new IllegalArgumentException("Embedding dimensions differ from the configured index identity");
        }
        if (dimensions != null) body.put("dimensions", dimensions);
		String payload;
		try {
			payload = JsonUtil.getObjectMapper().writeValueAsString(body);
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to serialize OpenAI-compatible embedding request", ex);
		}
		log.debug("OpenAI-compatible embedding request: inputs={}, keys={}", request.getInstructions().size(),
				body.keySet());
        long started=System.nanoTime();
        String response;
        try {
            response=restClient.post().uri(embeddingsPath).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload).retrieve().bodyToMono(String.class).timeout(requestBudget)
                .block(requestBudget.plusMillis(250));
        }finally {
            log.info("Retrieval model HTTP finished kind=EMBEDDING model={} budgetMs={} elapsedMs={}",defaultModel,
                requestBudget.toMillis(),(System.nanoTime()-started)/1_000_000);
        }
		EmbeddingResponse parsed = parse(response, request.getInstructions(), model, dimensions);
        if (parsed.getResults().size() != request.getInstructions().size()) {
            throw new IllegalStateException("Embedding response count differs from request");
        }
        for (int i = 0; i < parsed.getResults().size(); i++) {
            Embedding item = parsed.getResults().get(i);
            if (item.getIndex() != i || (dimensions != null && item.getOutput().length != dimensions)) {
                throw new IllegalStateException("Embedding response index or dimension mismatch");
            }
        }
		log.debug("OpenAI-compatible embedding response: vectors={}", parsed.getResults().size());
		return parsed;
	}

	private EmbeddingResponse parse(String response, List<String> inputs, String model, Integer dimensions) {
		try {
			JsonNode root = JsonUtil.getObjectMapper().readTree(response);
			JsonNode data = root.path("data");
			if (!data.isArray()) {
				throw new IllegalStateException("OpenAI-compatible embedding response has no data array");
			}
			List<Embedding> embeddings = new ArrayList<>();
			for (JsonNode item : data) {
				JsonNode vector = item.path("embedding");
				if (!vector.isArray() || vector.isEmpty()) {
					throw new IllegalStateException("OpenAI-compatible embedding response contains an empty vector");
				}
				float[] output = new float[vector.size()];
				for (int index = 0; index < vector.size(); index++) {
					if (!vector.get(index).isNumber() || !Double.isFinite(vector.get(index).asDouble())) {
                        throw new IllegalStateException("Embedding response contains a non-numeric value");
                    }
                    output[index] = (float) vector.get(index).asDouble();
                    if (!Float.isFinite(output[index])) throw new IllegalStateException("Embedding value out of range");
				}
				embeddings.add(new Embedding(output, item.path("index").asInt(embeddings.size())));
			}
			embeddings.sort(Comparator.comparingInt(Embedding::getIndex));
            var metadata = new LinkedHashMap<String, Object>();
            if (root.hasNonNull("encoding_identity") && dimensions != null) {
                try {
                    var identity = AtomicEmbeddingIdentity.response(root.get("encoding_identity"), inputs,
                        embeddings.stream().map(Embedding::getOutput).toList(), model, dimensions);
                    metadata.put(AtomicEmbeddingIdentity.METADATA_KEY, identity);
                } catch (IllegalArgumentException uncertified) {
                    log.warn("Embedding response certification rejected; exact reuse disabled for these vectors");
                }
            }
			return new EmbeddingResponse(List.copyOf(embeddings),
                new org.springframework.ai.embedding.EmbeddingResponseMetadata(model, null, metadata));
		}
		catch (RuntimeException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to parse OpenAI-compatible embedding response", ex);
		}
	}

    @Override
    public java.util.Optional<AtomicEmbeddingIdentity.Profile> currentDocumentProfile() {
        if (defaultDimensions == null || !embeddingsPath.endsWith("/embeddings")) return java.util.Optional.empty();
        var budget = requestBudget.compareTo(java.time.Duration.ofSeconds(1)) > 0
            ? java.time.Duration.ofSeconds(1) : requestBudget;
        String path = embeddingsPath.substring(0, embeddingsPath.length() - "embeddings".length()) + "embedding-identity";
        try {
            String body = restClient.get().uri(builder -> builder.path(path).queryParam("input_type", "document")
                .queryParam("dimensions", defaultDimensions).build()).retrieve().bodyToMono(String.class)
                .timeout(budget).block(budget.plusMillis(100));
            if (body == null) return java.util.Optional.empty();
            return java.util.Optional.of(AtomicEmbeddingIdentity.profile(JsonUtil.getObjectMapper().readTree(body),
                defaultModel, defaultDimensions));
        } catch (RuntimeException | java.io.IOException unavailable) {
            // Optional provider extension: 404/503, eviction, unknown revision and malformed profiles are cache misses.
            return java.util.Optional.empty();
        }
    }

}
