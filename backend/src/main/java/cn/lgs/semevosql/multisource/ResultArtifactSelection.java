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
package cn.lgs.semevosql.multisource;

/** One durable final-result selection rule for presentation, conversation and learning. */
public final class ResultArtifactSelection {

    private ResultArtifactSelection() {}

    public static boolean hasReviewReceipt(org.springframework.jdbc.core.JdbcTemplate jdbc, String runId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM qw_run_event WHERE run_id = ?
                AND event_type IN ('RESULT_ARTIFACT_ACCEPTED', 'RESULT_ARTIFACT_READY'))
            """, Boolean.class, runId));
    }

    // Explicit review receipts outrank physical merge completion. Without receipts, preserve legacy result reads.
    // If an accepted artifact is missing or no longer ready, fail closed instead of returning an abandoned result.
    public static final String FINAL_RESULT_SQL = """
        WITH accepted AS (
            SELECT payload::jsonb->>'artifactId' AS artifact_id
            FROM qw_run_event WHERE run_id = ?
              AND event_type IN ('RESULT_ARTIFACT_ACCEPTED', 'RESULT_ARTIFACT_READY')
            ORDER BY sequence DESC LIMIT 1
        )
        SELECT a.* FROM qw_result_artifact a
        WHERE a.run_id = ? AND a.source_sub_run_id IS NULL AND a.status = 'READY'
          AND a.artifact_type IN ('MERGED_RESULT', 'DIRECT_RESULT')
          AND (NOT EXISTS (SELECT 1 FROM accepted)
               OR a.artifact_id = (SELECT artifact_id FROM accepted))
        ORDER BY CASE WHEN a.artifact_type = 'MERGED_RESULT' THEN 0 ELSE 1 END,
                 a.update_time DESC, a.artifact_id DESC LIMIT 1
        """;
}
