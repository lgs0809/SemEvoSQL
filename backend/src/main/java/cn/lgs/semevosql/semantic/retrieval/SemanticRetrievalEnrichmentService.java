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
package cn.lgs.semevosql.semantic.retrieval;

import cn.lgs.semevosql.common.json.CanonicalJson;
import cn.lgs.semevosql.model.ModelCallPurpose;
import cn.lgs.semevosql.semantic.application.SemanticDocumentExtractionClient;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Build-time LLM enrichment for retrieval text. Catalog facts remain authoritative. */
@Service
public class SemanticRetrievalEnrichmentService {

	private static final Logger log = LoggerFactory.getLogger(SemanticRetrievalEnrichmentService.class);

	private static final int MAX_ITEM_LENGTH = 1000;

	private static final int MAX_ITEMS = 12;

	private static final int MAX_TOTAL_LENGTH = 8000;

	private static final Pattern FORMAL_REFERENCE = Pattern
		.compile("(?i)\\b(model|metric|column|dimension|enum|enum_value|relationship|rule):([\\p{L}\\p{N}_.:-]+)");

	private static final Pattern SQL_OR_FORMULA = Pattern
		.compile("(?i)(\\bselect\\b|\\bfrom\\b|\\bjoin\\b|\\bwhere\\b|\\bgroup\\s+by\\b|\\border\\s+by\\b|"
				+ "\\b(sum|avg|count|min|max)\\s*\\(|[A-Za-z_][A-Za-z0-9_.]*\\s*[+*/=]\\s*[A-Za-z0-9_.(])");

	private static final String SYSTEM_PROMPT = """
			You create search-only natural-language retrieval enrichment for SemEvoSQL.
			The supplied Catalog facts are authoritative and immutable. You may paraphrase them, add business colloquialisms,
			synonymous natural-language expressions, and common query contexts. Never invent or alter a model, metric, column,
			dimension, enum value, relationship, formula, datasource, SQL expression, or business rule. Do not output SQL or
			formulas. Use only the allowed formal asset references supplied by SemEvoSQL.

			Return exactly one JSON object and no Markdown:
			{
			  "semanticDescription": "natural language description",
			  "queryExpressions": ["common user phrasing"],
			  "queryContexts": ["common business query context"],
			  "referencedAssetKeys": ["only keys from allowedAssetKeys"]
			}
			""";

	private final SemanticDocumentExtractionClient client;

	private final CanonicalJson canonicalJson = new CanonicalJson();

	public SemanticRetrievalEnrichmentService(SemanticDocumentExtractionClient client) {
		this.client = client;
	}

	public EnrichmentResult enrich(EnrichmentInput input) {
		Objects.requireNonNull(input, "input");
		String response;
		try {
			response = client.complete(ModelCallPurpose.RETRIEVAL_ENRICHMENT, SYSTEM_PROMPT, userPrompt(input)).response();
		}
		catch (RuntimeException ex) {
			if (isCancellation(ex)) {
				throw ex;
			}
			log.warn("Semantic retrieval enrichment unavailable for {} {}; retryable deterministic fallback is used: {}",
					input.assetType(), input.assetKey(), ex.getMessage());
			return fallback(input);
		}
		try {
			EnrichmentPayload payload = parse(response);
			validate(payload, input.allowedAssetKeys());
			String semanticText = compose(input.fallbackSemanticText(), payload);
			return new EnrichmentResult(semanticText, "build-time-llm", generatorVersion(), "ENRICHED");
		}
		catch (RuntimeException ex) {
			if (isCancellation(ex)) {
				throw ex;
			}
			log.warn("Semantic retrieval enrichment rejected for {} {}; stable deterministic fallback is used: {}",
					input.assetType(), input.assetKey(), ex.getMessage());
			return validationFallback(input);
		}
	}

	private boolean isCancellation(Throwable error) {
		if (Thread.currentThread().isInterrupted()) {
			return true;
		}
		Throwable current = error;
		while (current != null) {
			if (current instanceof InterruptedException || current instanceof CancellationException) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	public EnrichmentResult fallback(EnrichmentInput input) {
		return new EnrichmentResult(input.fallbackSemanticText(), "deterministic", generatorVersion(), "FALLBACK");
	}

	private EnrichmentResult validationFallback(EnrichmentInput input) {
		return new EnrichmentResult(input.fallbackSemanticText(), "deterministic", generatorVersion(),
				"FALLBACK_VALIDATION");
	}

	public String generatorVersion() {
		return canonicalJson.hash(SYSTEM_PROMPT).substring(0, 32);
	}

	private String userPrompt(EnrichmentInput input) {
		try {
			return JsonUtil.getObjectMapper()
				.writeValueAsString(Map.of("documentType", input.documentType(), "assetType", input.assetType(),
						"assetKey", input.assetKey(), "modelCode", input.modelCode(), "physicalTable",
						input.physicalTable(), "officialFacts", input.officialFacts(), "allowedAssetKeys",
						input.allowedAssetKeys(), "activeEvidence", input.activeEvidence(), "relevantBusinessScenarios",
						input.relevantBusinessScenarios()));
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to serialize semantic retrieval enrichment input", ex);
		}
	}

	private EnrichmentPayload parse(String response) {
		try {
			String trimmed = response == null ? "" : response.trim();
			if (trimmed.startsWith("```")) {
				int firstLine = trimmed.indexOf('\n');
				int closing = trimmed.lastIndexOf("```");
				if (firstLine >= 0 && closing > firstLine) {
					trimmed = trimmed.substring(firstLine + 1, closing).trim();
				}
			}
			int start = trimmed.indexOf('{');
			int end = trimmed.lastIndexOf('}');
			if (start < 0 || end < start) {
				throw new IllegalArgumentException("No JSON object found in retrieval enrichment response");
			}
			JsonNode root = JsonUtil.getObjectMapper().readTree(trimmed.substring(start, end + 1));
			return JsonUtil.getObjectMapper().treeToValue(root, EnrichmentPayload.class);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Invalid retrieval enrichment JSON", ex);
		}
	}

	private void validate(EnrichmentPayload payload, Set<String> allowedAssetKeys) {
		if (payload == null) {
			throw new IllegalArgumentException("Retrieval enrichment payload is empty");
		}
		List<String> expressions = clean(payload.queryExpressions());
		List<String> contexts = clean(payload.queryContexts());
		String description = trim(payload.semanticDescription());
		if (!StringUtils.hasText(description) && expressions.isEmpty() && contexts.isEmpty()) {
			throw new IllegalArgumentException("Retrieval enrichment contains no usable natural-language text");
		}
		if (expressions.size() > MAX_ITEMS || contexts.size() > MAX_ITEMS) {
			throw new IllegalArgumentException("Retrieval enrichment contains too many expressions or contexts");
		}
		Set<String> allowed = allowedAssetKeys == null ? Set.of()
				: allowedAssetKeys.stream()
					.filter(StringUtils::hasText)
					.map(this::normalize)
					.collect(java.util.stream.Collectors.toSet());
		for (String reference : clean(payload.referencedAssetKeys())) {
			if (!allowed.contains(normalize(reference))) {
				throw new IllegalArgumentException("LLM referenced an unknown governed semantic asset: " + reference);
			}
		}
		List<String> text = new ArrayList<>();
		if (StringUtils.hasText(description)) {
			text.add(description);
		}
		text.addAll(expressions);
		text.addAll(contexts);
		int total = 0;
		for (String value : text) {
			if (value.length() > MAX_ITEM_LENGTH) {
				throw new IllegalArgumentException("Retrieval enrichment item is too long");
			}
			total += value.length();
			if (SQL_OR_FORMULA.matcher(value).find()) {
				throw new IllegalArgumentException("LLM attempted to add SQL or a formula to retrieval text");
			}
			Matcher matcher = FORMAL_REFERENCE.matcher(value);
			while (matcher.find()) {
				String reference = normalize(matcher.group(1) + ":" + matcher.group(2));
				if (!allowed.contains(reference)) {
					throw new IllegalArgumentException(
							"LLM embedded an unknown formal semantic reference: " + matcher.group());
				}
			}
		}
		if (total > MAX_TOTAL_LENGTH) {
			throw new IllegalArgumentException("Retrieval enrichment is too large");
		}
	}

	private String compose(String fallback, EnrichmentPayload payload) {
		LinkedHashSet<String> parts = new LinkedHashSet<>();
		if (StringUtils.hasText(fallback)) {
			parts.add(fallback.trim());
		}
		if (StringUtils.hasText(payload.semanticDescription())) {
			parts.add(payload.semanticDescription().trim());
		}
		parts.addAll(clean(payload.queryExpressions()));
		parts.addAll(clean(payload.queryContexts()));
		return String.join("\n", parts);
	}

	private List<String> clean(List<String> values) {
		if (values == null) {
			return List.of();
		}
		return values.stream().map(this::trim).filter(StringUtils::hasText).distinct().toList();
	}

	private String trim(String value) {
		return value == null ? "" : value.trim();
	}

	private String normalize(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}

	public record EnrichmentInput(String documentType, String assetType, String assetKey, String modelCode,
			String physicalTable, Map<String, String> officialFacts, Set<String> allowedAssetKeys,
			List<String> activeEvidence, List<String> relevantBusinessScenarios, String fallbackSemanticText) {
	}

	public record EnrichmentResult(String semanticText, String generatorModel, String generatorVersion,
			String generationStatus) {
	}

	public record EnrichmentPayload(String semanticDescription, List<String> queryExpressions,
			List<String> queryContexts, List<String> referencedAssetKeys) {
	}

}
