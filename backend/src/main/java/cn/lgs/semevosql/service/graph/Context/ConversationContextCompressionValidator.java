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
package cn.lgs.semevosql.service.graph.Context;

import cn.lgs.semevosql.properties.ConversationContextProperties;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/** Strict fail-closed validator for model-generated compaction output. */
@Component
public class ConversationContextCompressionValidator {

	private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z_])[-+]?\\d+(?:\\.\\d+)?");

	private static final Pattern BUSINESS_CODE = Pattern.compile("\\b[A-Za-z][A-Za-z0-9]*_[A-Za-z0-9_]+\\b");

	private static final Pattern SELECT_FROM = Pattern.compile("(?is)\\bselect\\b.+\\bfrom\\b");

	private final ConversationContextProperties properties;

	private final ObjectMapper mapper = JsonUtil.getObjectMapper()
		.copy()
		.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

	public ConversationContextCompressionValidator(ConversationContextProperties properties) {
		this.properties = properties;
	}

	public ConversationContextCompressionOutput validate(String rawJson, long expectedSequence, String inputText) {
		if (rawJson == null || rawJson.isBlank()) {
			throw new IllegalArgumentException("Compression output is blank");
		}
		if (rawJson.contains("```")) {
			throw new IllegalArgumentException("Compression output must not contain Markdown code fences");
		}
		ConversationContextCompressionOutput output;
		try {
			output = mapper.readValue(rawJson, ConversationContextCompressionOutput.class);
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Compression output is not strict JSON", ex);
		}
		if (output.schemaVersion() != ConversationContextCompressionOutput.CURRENT_SCHEMA_VERSION) {
			throw new IllegalArgumentException("Unsupported compression schema version");
		}
		if (output.coveredThroughSequence() != expectedSequence) {
			throw new IllegalArgumentException("Compression coverage sequence mismatch");
		}
		validateText(output.summary(), "summary", properties.getCompressedSummaryMaxChars(), true);
		validateItems(output.importantCorrections(), "importantCorrections",
				properties.getCompressedCorrectionMaxCount());
		validateItems(output.unresolvedQuestions(), "unresolvedQuestions", properties.getCompressedQuestionMaxCount());
		String semanticOutput = Stream
			.concat(Stream.of(output.summary()),
					Stream.concat(output.importantCorrections().stream(), output.unresolvedQuestions().stream()))
			.filter(value -> value != null && !value.isBlank())
			.collect(java.util.stream.Collectors.joining("\n"));
		validateNoSql(semanticOutput);
		validateNumbers(semanticOutput, inputText);
		validateBusinessCodes(semanticOutput, inputText);
		return output;
	}

	private void validateItems(List<String> values, String field, int maxCount) {
		if (values.size() > maxCount) {
			throw new IllegalArgumentException(field + " exceeds maximum count");
		}
		for (String value : values) {
			validateText(value, field, 120, false);
		}
	}

	private void validateText(String value, String field, int maxChars, boolean required) {
		if (value == null || value.isBlank()) {
			if (required) {
				throw new IllegalArgumentException(field + " must not be blank");
			}
			return;
		}
		if (value.length() > maxChars) {
			throw new IllegalArgumentException(field + " exceeds maximum length");
		}
		if (value.contains("```")) {
			throw new IllegalArgumentException(field + " must not contain Markdown code fences");
		}
	}

	private void validateNoSql(String value) {
		String normalized = value.stripLeading().toUpperCase(Locale.ROOT);
		if (normalized.startsWith("SELECT ") || normalized.startsWith("INSERT ") || normalized.startsWith("UPDATE ")
				|| normalized.startsWith("DELETE ") || normalized.startsWith("WITH ")
				|| SELECT_FROM.matcher(value).find()) {
			throw new IllegalArgumentException("Compression output must not contain SQL");
		}
	}

	private void validateNumbers(String output, String input) {
		Set<String> allowed = tokens(NUMBER, input);
		for (String value : tokens(NUMBER, output)) {
			if (!allowed.contains(value)) {
				throw new IllegalArgumentException(
						"Compression output contains a number absent from its input: " + value);
			}
		}
	}

	private void validateBusinessCodes(String output, String input) {
		Set<String> allowed = tokens(BUSINESS_CODE, input);
		for (String value : tokens(BUSINESS_CODE, output)) {
			if (!allowed.contains(value)) {
				throw new IllegalArgumentException("Compression output contains an unknown business code: " + value);
			}
		}
	}

	private Set<String> tokens(Pattern pattern, String value) {
		Set<String> result = new LinkedHashSet<>();
		Matcher matcher = pattern.matcher(value == null ? "" : value);
		while (matcher.find()) {
			result.add(matcher.group());
		}
		return result;
	}

}
