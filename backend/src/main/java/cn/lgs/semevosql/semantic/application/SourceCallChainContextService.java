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

import cn.lgs.semevosql.semantic.application.SourceCodeMaterialAnalyzer.Analysis;
import cn.lgs.semevosql.semantic.application.SourceCodeMaterialAnalyzer.Observation;
import cn.lgs.semevosql.semantic.domain.ProjectEvidence;
import cn.lgs.semevosql.semantic.domain.ProjectEvidence.EvidenceType;
import cn.lgs.semevosql.semantic.domain.ProjectEvidenceRepository;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Builds a bounded project-level static call-chain context from active source evidence.
 * The result contains only symbolic call edges and SQL summaries; it never concatenates
 * source files. This gives later source materials enough cross-file context for
 * semantic/scenario extraction without turning the project into runtime RAG.
 */
@Service
public class SourceCallChainContextService {

	private static final int MAX_DEPTH = 4;

	private static final int MAX_LINES = 40;

	private static final int MAX_SQL_CHARS = 800;

	private final ProjectEvidenceRepository evidenceRepository;

	private final ObjectMapper mapper = JsonUtil.getObjectMapper();

	public SourceCallChainContextService(ProjectEvidenceRepository evidenceRepository) {
		this.evidenceRepository = evidenceRepository;
	}

	public String render(Long projectVersionId, Long currentMaterialId, Analysis current) {
		if (projectVersionId == null || current == null || current.observations().isEmpty()) {
			return "";
		}
		List<CallEdge> calls = new ArrayList<>();
		Map<String, List<String>> sqlByClass = new LinkedHashMap<>();
		for (ProjectEvidence evidence : evidenceRepository.findActiveEvidenceByVersion(projectVersionId)) {
			if (currentMaterialId != null && java.util.Objects.equals(currentMaterialId, evidence.getMaterialId())) {
				continue;
			}
			acceptEvidence(evidence, calls, sqlByClass);
		}
		Set<String> roots = new LinkedHashSet<>();
		for (Observation observation : current.observations()) {
			acceptObservation(observation, calls, sqlByClass, roots);
		}
		if (roots.isEmpty() || calls.isEmpty()) {
			return "";
		}
		Map<String, List<CallEdge>> byClass = new LinkedHashMap<>();
		for (CallEdge call : calls) {
			byClass.computeIfAbsent(normalizeClass(call.sourceClass()), ignored -> new ArrayList<>()).add(call);
		}
		LinkedHashSet<String> lines = new LinkedHashSet<>();
		for (String root : roots) {
			walk(root, null, byClass, sqlByClass, new LinkedHashSet<>(), new ArrayList<>(), lines, 0);
			if (lines.size() >= MAX_LINES) {
				break;
			}
		}
		if (lines.isEmpty()) {
			return "";
		}
		return "\n\n[Project static call-chain context; active evidence only]\n" + String.join("\n", lines);
	}

	private void walk(String className, String methodName, Map<String, List<CallEdge>> byClass,
			Map<String, List<String>> sqlByClass, Set<String> visiting, List<String> path, Set<String> output,
			int depth) {
		if (depth > MAX_DEPTH || output.size() >= MAX_LINES) {
			return;
		}
		String classKey = normalizeClass(className);
		String visitKey = classKey + "#" + normalizeMethod(methodName);
		if (!visiting.add(visitKey)) {
			return;
		}
		appendSql(className, methodName, sqlByClass.getOrDefault(classKey, List.of()), output);
		List<CallEdge> candidates = byClass.getOrDefault(classKey, List.of())
			.stream()
			.filter(edge -> !StringUtils.hasText(methodName) || methodName.equals(edge.sourceMethod()))
			.toList();
		if (candidates.isEmpty()) {
			appendTerminal(path, className, methodName, sqlByClass.getOrDefault(classKey, List.of()), output);
			visiting.remove(visitKey);
			return;
		}
		for (CallEdge edge : candidates) {
			List<String> nextPath = new ArrayList<>(path);
			nextPath.add(edge.sourceClass() + "#" + edge.sourceMethod() + " -> " + edge.receiver() + "."
					+ edge.targetMethod());
			String targetClass = edge.receiver();
			String targetKey = normalizeClass(targetClass);
			if (byClass.containsKey(targetKey)) {
				walk(targetClass, edge.targetMethod(), byClass, sqlByClass, visiting, nextPath, output, depth + 1);
			}
			else {
				appendTerminal(nextPath, targetClass, edge.targetMethod(),
						sqlByClass.getOrDefault(targetKey, List.of()), output);
			}
			if (output.size() >= MAX_LINES) {
				break;
			}
		}
		visiting.remove(visitKey);
	}

	private void appendTerminal(List<String> path, String className, String methodName, List<String> sql,
			Set<String> output) {
		if (!path.isEmpty()) {
			output.add("CALL " + String.join(" | ", path));
		}
		appendSql(className, methodName, sql, output);
	}

	private void appendSql(String className, String methodName, List<String> sql, Set<String> output) {
		for (String statement : sql) {
			if (output.size() >= MAX_LINES) {
				return;
			}
			output.add("SQL " + className + (StringUtils.hasText(methodName) ? "#" + methodName : "") + ": "
					+ trim(statement, MAX_SQL_CHARS));
		}
	}

	private void acceptEvidence(ProjectEvidence evidence, List<CallEdge> calls, Map<String, List<String>> sqlByClass) {
		if (evidence == null || !StringUtils.hasText(evidence.getPayloadJson())) {
			return;
		}
		try {
			JsonNode payload = mapper.readTree(evidence.getPayloadJson());
			if (evidence.getEvidenceType() == EvidenceType.CODE_CALL_CHAIN) {
				acceptCallPayload(payload, calls, null);
			}
			else if (evidence.getEvidenceType() == EvidenceType.SQL_PATTERN) {
				acceptSqlPayload(payload, sqlByClass);
			}
		}
		catch (Exception ignored) {
			// Evidence is append-only audit data. One malformed historical payload must
			// not
			// block current source parsing.
		}
	}

	private void acceptObservation(Observation observation, List<CallEdge> calls, Map<String, List<String>> sqlByClass,
			Set<String> roots) {
		if (observation == null) {
			return;
		}
		JsonNode payload = mapper.valueToTree(observation.payload());
		if (observation.evidenceType() == EvidenceType.CODE_CALL_CHAIN) {
			acceptCallPayload(payload, calls, roots);
		}
		else if (observation.evidenceType() == EvidenceType.SQL_PATTERN) {
			acceptSqlPayload(payload, sqlByClass);
		}
	}

	private void acceptCallPayload(JsonNode payload, List<CallEdge> calls, Set<String> roots) {
		String className = text(payload, "className");
		String method = text(payload, "method");
		if (!StringUtils.hasText(className) || !StringUtils.hasText(method)) {
			return;
		}
		if (roots != null) {
			roots.add(className);
		}
		JsonNode callValues = payload.path("calls");
		if (!callValues.isArray()) {
			return;
		}
		for (JsonNode value : callValues) {
			String call = value.asText("");
			int dot = call.lastIndexOf('.');
			if (dot <= 0 || dot >= call.length() - 1) {
				continue;
			}
			calls.add(new CallEdge(className, method, call.substring(0, dot), call.substring(dot + 1)));
		}
	}

	private void acceptSqlPayload(JsonNode payload, Map<String, List<String>> sqlByClass) {
		String className = text(payload, "className");
		String sql = text(payload, "sql");
		if (!StringUtils.hasText(className) || !StringUtils.hasText(sql)) {
			return;
		}
		sqlByClass.computeIfAbsent(normalizeClass(className), ignored -> new ArrayList<>()).add(sql);
	}

	private String normalizeClass(String value) {
		if (!StringUtils.hasText(value)) {
			return "";
		}
		String normalized = value.replaceAll("[^A-Za-z0-9_$]", "").toLowerCase(Locale.ROOT);
		for (String suffix : List.of("impl", "interface")) {
			if (normalized.endsWith(suffix) && normalized.length() > suffix.length()) {
				normalized = normalized.substring(0, normalized.length() - suffix.length());
			}
		}
		return normalized;
	}

	private String normalizeMethod(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT);
	}

	private String text(JsonNode node, String field) {
		return node == null ? "" : node.path(field).asText("");
	}

	private String trim(String value, int max) {
		String compact = value == null ? "" : value.replaceAll("\\s+", " ").trim();
		return compact.length() <= max ? compact : compact.substring(0, max) + "...";
	}

	private record CallEdge(String sourceClass, String sourceMethod, String receiver, String targetMethod) {
	}

}
