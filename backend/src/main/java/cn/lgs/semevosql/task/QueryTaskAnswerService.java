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

import cn.lgs.semevosql.run.QueryExecutionExplanation;
import cn.lgs.semevosql.run.QueryExecutionExplanationService;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Public Todo results come from the same accepted row as their pinned plan. No latest-Run inference. */
@Service
@RequiredArgsConstructor
public class QueryTaskAnswerService {
    private final JdbcTemplate jdbc;
    private final QueryExecutionExplanationService explanations;

    public List<TaskAnswer> acceptedAnswers(String runId) {
        return jdbc.query("""
            SELECT task_id, ordinal_no, question, semantic_plan_json::text AS plan,
                   result_summary_json::text AS result
            FROM qw_query_task
            WHERE run_id = ? AND status = 'DONE' AND review_json->>'decision' = 'PASS'
            ORDER BY ordinal_no, task_id
            """, (row, ignored) -> {
                String taskId = row.getString("task_id"), question = row.getString("question");
                int ordinal = row.getInt("ordinal_no");
                try {
                    var mapper = JsonUtil.getObjectMapper();
                    var plan = mapper.readValue(row.getString("plan"), SemanticBlueprint.class);
                    var summary = mapper.readTree(row.getString("result"));
                    String payload = summary.path("resultPayload").asText("");
                    var root = payload.isBlank() ? summary : mapper.readTree(payload);
                    var table = root.has("resultSet") ? root.path("resultSet") : root;
                    String report = summary.path("report").asText("");
                    if ((!table.path("column").isArray() || !table.path("data").isArray()) && report.isBlank()) {
                        throw new IllegalStateException("Accepted result has no readable table or report");
                    }
                    List<String> columns = table.path("column").isArray()
                        ? mapper.convertValue(table.path("column"), new TypeReference<List<String>>() {}) : List.of();
                    List<Map<String, Object>> rows = table.path("data").isArray()
                        ? mapper.convertValue(table.path("data"), new TypeReference<List<Map<String, Object>>>() {}) : List.of();
                    return new TaskAnswer(taskId, ordinal, question, columns, rows, report,
                        explanations.explainTask(plan, question, columns), null);
                } catch (Exception unreadable) {
                    // Keep this target visible; never substitute a sibling's successful data or business definition.
                    return new TaskAnswer(taskId, ordinal, question, List.of(), List.of(), "", null,
                        "这项结果暂时无法读取，请查看执行详情。");
                }
            }, runId);
    }

    public record TaskAnswer(String taskId, int ordinal, String question, List<String> columns,
            List<Map<String, Object>> rows, String report, QueryExecutionExplanation explanation, String error) {}
}
