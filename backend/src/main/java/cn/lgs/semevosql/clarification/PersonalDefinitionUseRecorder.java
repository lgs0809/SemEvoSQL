/*
 * Copyright 2026 the original author or authors.
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
package cn.lgs.semevosql.clarification;

import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.sql.application.SqlExecutionAttemptService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Use evidence is attached to an actual query receipt, never to an unexecuted plan. */
@Service
public class PersonalDefinitionUseRecorder {
    private final JdbcTemplate jdbc;
    private final UserSemanticPreferenceService preferences;
    public PersonalDefinitionUseRecorder(JdbcTemplate jdbc,UserSemanticPreferenceService preferences){this.jdbc=jdbc;this.preferences=preferences;}
    @Transactional
    public void record(SemanticBlueprint plan,SqlExecutionAttemptService.Context context) {
        if(plan==null||context==null)return;
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM qw_sql_execution_attempt WHERE run_id=? AND scope_key=? AND phase='QUERY')",
            Boolean.class,context.runId(),context.scopeKey())))return;
        for(var binding:plan.getBindingDependencies())if("USER".equals(binding.getSource())&&binding.getSourceRecordId()!=null&&binding.getSourceRevision()!=null)
            preferences.recordApplied(binding.getSourceRecordId(),binding.getSourceRevision(),context.runId());
    }
    public void sweep() {
        var runs=jdbc.query("""
            SELECT DISTINCT u.run_id FROM qw_user_semantic_preference_usage u JOIN qw_query_run r ON r.run_id=u.run_id
            WHERE u.valid AND u.event_type='APPLIED' AND r.status IN ('SUCCEEDED','FAILED','CANCELLED')
            ORDER BY u.run_id LIMIT 100
            """,(rs,n)->rs.getString(1));
        runs.forEach(preferences::finalizeSuccessfulRun);
    }
}
