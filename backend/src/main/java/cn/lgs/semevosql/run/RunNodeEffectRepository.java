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
package cn.lgs.semevosql.run;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RunNodeEffectRepository {

	private final JdbcTemplate jdbc;

	public Optional<RunNodeEffect> findCompleted(String runId, String nodeKey, String inputHash) {
		List<RunNodeEffect> values = jdbc.query("""
				SELECT * FROM qw_run_node_effect
				WHERE run_id = ? AND node_key = ? AND input_hash = ? AND status = 'COMPLETED'
				""", this::map, runId, nodeKey, inputHash);
		return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
	}

	public Optional<RunNodeEffect> find(String runId, String nodeKey) {
		List<RunNodeEffect> values = jdbc.query("""
				SELECT * FROM qw_run_node_effect WHERE run_id = ? AND node_key = ?
				""", this::map, runId, nodeKey);
		return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
	}

	public void lockRun(String runId) {
		List<String> values = jdbc.query("SELECT run_id FROM qw_query_run WHERE run_id = ? FOR UPDATE",
				(rs, rowNum) -> rs.getString(1), runId);
		if (values.isEmpty()) {
			throw new IllegalArgumentException("Query run not found: " + runId);
		}
	}

	public void insertCompleted(RunNodeEffect effect) {
		jdbc.update("""
				INSERT INTO qw_run_node_effect
				(effect_id, run_id, node_key, input_hash, status, result_json, create_time, update_time, complete_time)
				VALUES (?, ?, ?, ?, 'COMPLETED', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", effect.effectId(), effect.runId(), effect.nodeKey(), effect.inputHash(), effect.resultJson());
	}

	public int replaceCompleted(String runId, String nodeKey, String inputHash, String resultJson) {
		return jdbc.update("""
				UPDATE qw_run_node_effect
				SET input_hash = ?, status = 'COMPLETED', result_json = ?, update_time = CURRENT_TIMESTAMP,
					complete_time = CURRENT_TIMESTAMP
				WHERE run_id = ? AND node_key = ?
				""", inputHash, resultJson, runId, nodeKey);
	}

	private RunNodeEffect map(ResultSet rs, int rowNum) throws SQLException {
		return new RunNodeEffect(rs.getString("effect_id"), rs.getString("run_id"), rs.getString("node_key"),
				rs.getString("input_hash"), rs.getString("status"), rs.getString("result_json"),
				time(rs.getTimestamp("create_time")), time(rs.getTimestamp("update_time")),
				time(rs.getTimestamp("complete_time")));
	}

	private static LocalDateTime time(Timestamp value) {
		return value == null ? null : value.toLocalDateTime();
	}

	public record RunNodeEffect(String effectId, String runId, String nodeKey, String inputHash, String status,
			String resultJson, LocalDateTime createTime, LocalDateTime updateTime, LocalDateTime completeTime) {
	}

}
