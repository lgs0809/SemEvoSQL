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
package cn.lgs.semevosql.semantic.application;

import cn.lgs.semevosql.semantic.domain.MaterialCategory;
import cn.lgs.semevosql.semantic.domain.ProjectEvidence.EvidenceType;
import cn.lgs.semevosql.semantic.domain.SemanticMaterial;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Lightweight deterministic source-code analysis used during onboarding. It extracts
 * observable call/query facts and prepares a bounded, secret-redacted relevant slice for
 * semantic/scenario LLM extraction. It intentionally does not promote these observations
 * into Catalog assets.
 */
@Component
public class SourceCodeMaterialAnalyzer {

	private static final int MAX_RELEVANT_CHARACTERS = 32_000;

	private static final Pattern CLASS = Pattern.compile("\\b(?:class|interface|record)\\s+([A-Za-z_$][\\w$]*)");

	private static final Pattern METHOD = Pattern.compile(
			"(?m)^\\s*(?:public|protected|private)?\\s*(?:static\\s+)?(?:final\\s+)?[\\w<>?, .\\[\\]]+\\s+([A-Za-z_$][\\w$]*)\\s*\\(([^)]*)\\)\\s*(?:throws[^\\{]+)?\\{");

	private static final Pattern CALL = Pattern.compile("\\b([a-zA-Z_$][\\w$]*)\\.([a-zA-Z_$][\\w$]*)\\s*\\(");

	private static final Pattern HTTP_MAPPING = Pattern.compile(
			"@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping)\\s*(?:\\(([^)]*)\\))?");

	private static final Pattern SQL = Pattern.compile(
			"(?is)(?:@(?:Select|Insert|Update|Delete)\\s*\\(\\s*\"([^\"]{4,})\"\\s*\\)|<(?:select|insert|update|delete)\\b[^>]*>(.*?)</(?:select|insert|update|delete)>|\"((?:SELECT|INSERT|UPDATE|DELETE)\\b[^\"]{4,})\")");

	private static final Pattern TEST_ASSERTION = Pattern
		.compile("(?m)^.*(?:assertThat|assertEquals|assertTrue|assertFalse|verify\\s*\\(|expect\\s*\\().*$");

	private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
			"(?im)(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key)\\s*[:=]\\s*([^\\s,;]+|\"[^\"]*\"|'[^']*')");

	private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/-]{8,}={0,2}");

	private static final Pattern PRIVATE_KEY = Pattern.compile(
			"(?s)-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----.*?-----END (?:RSA |EC |OPENSSH )?PRIVATE KEY-----");

	private static final Set<String> INFRA_RECEIVERS = Set.of("log", "logger", "objects", "collections", "stream",
			"optional", "string", "strings", "math", "system", "assertthat");

	public Analysis analyze(SemanticMaterial material) {
		if (material == null || !supported(material.getMaterialCategory())
				|| !StringUtils.hasText(material.getContent())) {
			return Analysis.empty(material == null ? null : material.getContent());
		}
		String redacted = redactSecrets(material.getContent());
		String className = firstGroup(CLASS, redacted, "unknown");
		List<Observation> observations = new ArrayList<>();
		List<MethodSlice> methods = methods(redacted);
		for (MethodSlice method : methods) {
			Set<String> calls = new LinkedHashSet<>();
			Matcher callMatcher = CALL.matcher(method.body());
			while (callMatcher.find()) {
				String receiver = callMatcher.group(1);
				if (!INFRA_RECEIVERS.contains(receiver.toLowerCase(Locale.ROOT))) {
					calls.add(receiver + "." + callMatcher.group(2));
				}
			}
			if (!calls.isEmpty()) {
				observations.add(new Observation(EvidenceType.CODE_CALL_CHAIN, className + "#" + method.name(),
						Map.of("className", className, "method", method.name(), "parameters", method.parameters(),
								"calls", List.copyOf(calls)),
						95));
			}
		}
		Matcher mappingMatcher = HTTP_MAPPING.matcher(redacted);
		while (mappingMatcher.find()) {
			String mapping = mappingMatcher.group();
			observations.add(new Observation(EvidenceType.API_BEHAVIOR,
					className + ":" + Integer.toHexString(mapping.hashCode()),
					Map.of("className", className, "mapping", mapping), 90));
		}
		Matcher sqlMatcher = SQL.matcher(redacted);
		while (sqlMatcher.find()) {
			String sql = firstText(sqlMatcher.group(1), sqlMatcher.group(2), sqlMatcher.group(3));
			if (StringUtils.hasText(sql)) {
				String normalized = sql.replaceAll("\\s+", " ").trim();
				observations.add(new Observation(EvidenceType.SQL_PATTERN,
						className + ":sql:" + Integer.toHexString(normalized.toLowerCase(Locale.ROOT).hashCode()),
						Map.of("className", className, "sql", trim(normalized, 8000)), 100));
			}
		}
		if (material.getMaterialCategory() == MaterialCategory.TEST_MATERIAL) {
			Matcher assertionMatcher = TEST_ASSERTION.matcher(redacted);
			while (assertionMatcher.find()) {
				String assertion = assertionMatcher.group().trim();
				observations.add(new Observation(EvidenceType.TEST_ASSERTION,
						className + ":assert:" + Integer.toHexString(assertion.hashCode()),
						Map.of("assertion", assertion), 90));
			}
		}
		String relevant = relevantSlice(redacted, methods);
		return new Analysis(relevant, List.copyOf(observations), !redacted.equals(material.getContent()));
	}

	public boolean supported(MaterialCategory category) {
		return category == MaterialCategory.BACKEND_SOURCE || category == MaterialCategory.DATA_ACCESS_CODE
				|| category == MaterialCategory.TEST_MATERIAL || category == MaterialCategory.DATABASE_MIGRATION;
	}

	public String redactSecrets(String content) {
		if (!StringUtils.hasText(content)) {
			return content;
		}
		String redacted = PRIVATE_KEY.matcher(content).replaceAll("[REDACTED_PRIVATE_KEY]");
		redacted = BEARER.matcher(redacted).replaceAll("Bearer [REDACTED]");
		Matcher matcher = SECRET_ASSIGNMENT.matcher(redacted);
		StringBuffer buffer = new StringBuffer();
		while (matcher.find()) {
			matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(1) + "=[REDACTED]"));
		}
		matcher.appendTail(buffer);
		return buffer.toString();
	}

	private List<MethodSlice> methods(String content) {
		List<MethodSlice> result = new ArrayList<>();
		Matcher matcher = METHOD.matcher(content);
		while (matcher.find()) {
			int open = content.indexOf('{', matcher.start());
			int close = matchingBrace(content, open);
			if (open < 0 || close <= open) {
				continue;
			}
			result.add(new MethodSlice(matcher.group(1), matcher.group(2).trim(),
					content.substring(matcher.start(), close + 1)));
		}
		return result;
	}

	private int matchingBrace(String content, int open) {
		if (open < 0) {
			return -1;
		}
		int depth = 0;
		boolean inString = false;
		char quote = 0;
		for (int i = open; i < content.length(); i++) {
			char current = content.charAt(i);
			if (inString) {
				if (current == '\\') {
					i++;
				}
				else if (current == quote) {
					inString = false;
				}
				continue;
			}
			if (current == '\'' || current == '"') {
				inString = true;
				quote = current;
			}
			else if (current == '{') {
				depth++;
			}
			else if (current == '}' && --depth == 0) {
				return i;
			}
		}
		return -1;
	}

	private String relevantSlice(String content, List<MethodSlice> methods) {
		LinkedHashSet<String> slices = new LinkedHashSet<>();
		for (MethodSlice method : methods) {
			String lower = method.body().toLowerCase(Locale.ROOT);
			if (lower.contains("repository.") || lower.contains("mapper.") || lower.contains("dao.")
					|| lower.contains("service.") || lower.contains("select ") || lower.contains("update ")
					|| lower.contains("insert ") || lower.contains("delete ") || lower.contains("@getmapping")
					|| lower.contains("@postmapping") || lower.contains("@requestmapping")) {
				slices.add(method.body());
			}
		}
		Matcher sql = SQL.matcher(content);
		while (sql.find()) {
			slices.add(sql.group());
		}
		if (slices.isEmpty()) {
			return trim(content, MAX_RELEVANT_CHARACTERS);
		}
		String joined = String.join("\n\n--- relevant source slice ---\n", slices);
		return trim(joined, MAX_RELEVANT_CHARACTERS);
	}

	private String firstGroup(Pattern pattern, String content, String fallback) {
		Matcher matcher = pattern.matcher(content);
		return matcher.find() ? matcher.group(1) : fallback;
	}

	private String firstText(String... values) {
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value;
			}
		}
		return null;
	}

	private String trim(String value, int max) {
		if (value == null || value.length() <= max) {
			return value;
		}
		return value.substring(0, max) + "\n...[truncated]";
	}

	public record Analysis(String relevantContent, List<Observation> observations, boolean secretsRedacted) {

		static Analysis empty(String content) {
			return new Analysis(content, List.of(), false);
		}
	}

	public record Observation(EvidenceType evidenceType, String subjectKey, Map<String, Object> payload,
			int confidence) {
	}

	private record MethodSlice(String name, String parameters, String body) {
	}

}
