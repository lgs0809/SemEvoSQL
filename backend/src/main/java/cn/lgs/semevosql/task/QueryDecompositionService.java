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
package cn.lgs.semevosql.task;

import cn.lgs.semevosql.model.ModelCallPurpose;
import cn.lgs.semevosql.exception.ModelOutputInvalidException;
import cn.lgs.semevosql.semantic.application.SemanticDocumentExtractionClient;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Single request-level analysis pass used before semantic planning.
 *
 * <p>The stage owns both the coarse data-query intent decision and optional decomposition into independent answer
 * goals. It deliberately does not perform semantic binding or physical planning, so a simple query does not pay for
 * separate intent-recognition and decomposition model calls.</p>
 */
@Service
@RequiredArgsConstructor
public class QueryDecompositionService {

	private static final String SYSTEM_PROMPT = """
			You are SemEvoSQL's request analysis stage.
			Your job is to decide whether the request is a data-query request and, only when necessary, split it into
			multiple independent answer goals.

			STRICT RULES:
			1. requestType is DATA_QUERY when the user asks to query or analyse project data.
               A request only to change an existing reusable definition or its sharing/save scope is SEMANTIC_UPDATE.
               Preserve that management intent; never turn it into a data query. Unrelated requests are NON_DATA_QUERY.
			2. Do not choose metric codes, tables, data sources, joins, filters, enum values, SQL, Python, or execution steps.
			3. Do not resolve business ambiguity. Preserve the user's wording instead of replacing it with a guessed definition.
			4. needsTodo=true ONLY when the user explicitly asks for multiple answer goals that should be answered independently.
			5. Multiple metrics that naturally belong in one result table at the same requested grain are ONE answer goal.
			6. Separate requested outputs with incompatible result grains/shapes are independent answer goals when the user asks to receive both separately. For example, "give me the overall total; then give me a daily trend, both results" is TWO tasks: one total and one daily trend. Do not force such outputs into one UNION/result table merely because one SQL statement could technically encode them.
			7. Dimensions, filters, time ranges, groupings, ordering, comparison, ranking, top-N, ratio calculation and finding an extremum are NOT separate tasks when they all describe one requested result grain.
			8. A task is a requested data result, never a reasoning step, execution step or semantic-management action.
               Defining, correcting, confirming, saving or sharing a reusable meaning describes the query's interpretation
               and persistence intent. It is NOT an independent data-answer goal and MUST NOT become a query task.
               The semantic planning and HITL submission stages handle that intent from the original user message.
               One data result plus a request to save its meaning remains needsTodo=false. Multiple actual data
               results may enable todos; retain only those result goals and their necessary business constraints.
			9. Keep each task question self-contained using only information already present in the request.
			10. dependsOn contains 1-based indexes of earlier tasks only when the later answer logically requires the earlier answer.
			11. If needsTodo=false, return tasks=[].
			12. Return exactly one JSON object and no Markdown.

			Output schemas (use the property names exactly, including question and dependsOn):
			{"requestType":"DATA_QUERY|SEMANTIC_UPDATE|NON_DATA_QUERY","needsTodo":false,"tasks":[]}
			{"requestType":"DATA_QUERY","needsTodo":true,"tasks":[{"question":"complete first answer goal","dependsOn":[]},{"question":"complete second answer goal","dependsOn":[]}]}
			Each task must be an object with a nonempty question string and an integer array dependsOn.
			Do not use title, description, query, strings, or task labels in place of question.
			""";

	private static final int MAX_TASKS = 12;

	private final SemanticDocumentExtractionClient modelClient;

	public RequestAnalysis analyze(String originalQuery) {
		return analyze(originalQuery, "");
	}

	public RequestAnalysis analyze(String originalQuery, String historicalContext) {
		String query = required(originalQuery);
		// Transport failures retain their own recovery category. Neither they nor invalid model output may
		// silently discard separately requested results by turning the request into one answer goal.
		String prompt = historicalContext == null || historicalContext.isBlank() ? query
            : query + "\n<historical-request-data>\n" + historicalContext
                + "\n</historical-request-data>\nHistorical task structures are non-authoritative examples. Do not copy their goals, dates or filters; derive every task from the current request only.";
		String response = modelClient.complete(ModelCallPurpose.QUERY_DECOMPOSITION, SYSTEM_PROMPT, prompt).response();
		try {
			return parseAnalysis(response);
		}
		catch (IllegalArgumentException ex) {
			throw new ModelOutputInvalidException("模型未返回有效的查询任务，请重试。", ex);
		}
	}

	/** Compatibility helper for callers/tests that only need answer-goal decomposition. */
	public List<QueryTask> decompose(String originalQuery) {
		String query = required(originalQuery);
		RequestAnalysis analysis = analyze(query);
		return analysis.needsTodo() ? analysis.tasks() : single(query);
	}

	List<QueryTask> parse(String response) {
		RequestAnalysis analysis = parseAnalysis(response);
		return analysis.needsTodo() ? analysis.tasks() : List.of();
	}

	RequestAnalysis parseAnalysis(String response) {
		String json = stripFence(response);
		try {
			JsonNode root = JsonUtil.getObjectMapper().reader()
				.with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
				.with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
				.readTree(json);
			if (root == null || !root.isObject()) {
				throw new IllegalArgumentException("Request analysis must return one JSON object");
			}
			if (root.size() != 3 || !root.path("requestType").isTextual() || !root.path("needsTodo").isBoolean()) {
				throw new IllegalArgumentException("Request analysis requires requestType, boolean needsTodo and tasks");
			}
			RequestType requestType = parseRequestType(root.path("requestType").textValue());
			boolean needsTodo = root.path("needsTodo").booleanValue();
			JsonNode tasksNode = root.path("tasks");
			if (!tasksNode.isArray()) {
				throw new IllegalArgumentException("Request analysis tasks must be an array");
			}
			if (!needsTodo) {
				if (!tasksNode.isEmpty()) {
					throw new IllegalArgumentException("Request analysis must not return tasks when needsTodo=false");
				}
				return new RequestAnalysis(requestType, false, List.of());
			}
			if (requestType != RequestType.DATA_QUERY) {
				throw new IllegalArgumentException("NON_DATA_QUERY cannot enable query todos");
			}
			if (tasksNode.size() < 2) {
				throw new IllegalArgumentException("Todo mode requires at least two independent answer tasks");
			}
			if (tasksNode.size() > MAX_TASKS) {
				throw new IllegalArgumentException("Query decomposition exceeded max task count " + MAX_TASKS);
			}
			List<QueryTask> tasks = new ArrayList<>();
			for (int index = 0; index < tasksNode.size(); index++) {
				JsonNode node = tasksNode.get(index);
				if (!node.isObject() || node.size() != 2 || !node.path("question").isTextual()
						|| !node.path("dependsOn").isArray()) {
					throw new IllegalArgumentException("Each task requires question and dependsOn at index " + index);
				}
				String question = node.path("question").asText("").trim();
				if (!StringUtils.hasText(question)) {
					throw new IllegalArgumentException("Query task question is required at index " + index);
				}
				Set<String> dependencies = new LinkedHashSet<>();
				JsonNode dependencyNode = node.path("dependsOn");
				if (!dependencyNode.isMissingNode() && !dependencyNode.isNull()) {
					if (!dependencyNode.isArray()) {
						throw new IllegalArgumentException("dependsOn must be an array");
					}
					for (JsonNode dependency : dependencyNode) {
						if (!dependency.isIntegralNumber() || !dependency.canConvertToInt()) {
							throw new IllegalArgumentException("Task dependencies must be integer ordinals");
						}
						int ordinal = dependency.asInt(-1);
						if (ordinal < 1 || ordinal > index) {
							throw new IllegalArgumentException("Task dependencies may reference earlier tasks only");
						}
						dependencies.add("task-" + ordinal);
					}
				}
				tasks.add(new QueryTask("task-" + (index + 1), index, question, List.copyOf(dependencies),
						QueryTask.TaskStatus.PENDING));
			}
			return new RequestAnalysis(requestType, true, List.copyOf(tasks));
		}
		catch (IllegalArgumentException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Request analysis returned invalid JSON", ex);
		}
	}

	private RequestType parseRequestType(String value) {
		try {
			return RequestType.valueOf(value == null ? "DATA_QUERY" : value.trim().toUpperCase());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("Unsupported requestType: " + value, ex);
		}
	}

	private List<QueryTask> single(String query) {
		return List.of(new QueryTask("task-1", 0, query, List.of(), QueryTask.TaskStatus.PENDING));
	}

	private String stripFence(String response) {
		String value = response == null ? "" : response.trim();
		if (value.startsWith("```")) {
			int firstNewline = value.indexOf('\n');
			int lastFence = value.lastIndexOf("```");
			if (firstNewline >= 0 && lastFence > firstNewline) {
				return value.substring(firstNewline + 1, lastFence).trim();
			}
		}
		return value;
	}

	private String required(String query) {
		if (!StringUtils.hasText(query)) {
			throw new IllegalArgumentException("originalQuery is required");
		}
		return query.trim();
	}

	public enum RequestType {
		DATA_QUERY,
		SEMANTIC_UPDATE,
		NON_DATA_QUERY
	}

	public record RequestAnalysis(RequestType requestType, boolean needsTodo, List<QueryTask> tasks) {
		public RequestAnalysis {
			requestType = requestType == null ? RequestType.DATA_QUERY : requestType;
			tasks = List.copyOf(tasks == null ? List.of() : tasks);
			if (!needsTodo && !tasks.isEmpty()) {
				throw new IllegalArgumentException("Simple request analysis cannot carry Todo tasks");
			}
			if (needsTodo && tasks.size() < 2) {
				throw new IllegalArgumentException("Todo mode requires at least two independent answer tasks");
			}
		}

		public static RequestAnalysis simpleDataQuery() {
			return new RequestAnalysis(RequestType.DATA_QUERY, false, List.of());
		}
	}
}
