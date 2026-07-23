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
package cn.lgs.semevosql.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import cn.lgs.semevosql.trajectory.TrajectoryAnalysisService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class FrozenEvaluationPolicyTest {

    @Test
    void defaultPreservesLearningAndConfigurationOnlyExcludesNamedProjects() {
        var policy = new FrozenEvaluationPolicy();
        assertThat(policy.allowsLearning(5L)).isTrue();
        policy.setProjectIds("5, 7,5");
        assertThat(policy.allowsLearning(5L)).isFalse();
        assertThat(policy.allowsLearning(7L)).isFalse();
        assertThat(policy.allowsLearning(4L)).isTrue();
        assertThat(policy.allowsLearning(null)).isTrue();
        assertThatThrownBy(() -> policy.setProjectIds("0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.setProjectIds("all")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completedFrozenRequestDoesNotEnterQueryCaseAdmissionOrIndexing() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq("episode"))).thenReturn(List.of(Map.of(
            "project_id", 5L, "sql_text", "SELECT 1", "normalized_question", "合成问题")));
        var capture = new QueryCaseCaptureService(jdbc, null, null, null, null, null, null, null, null);
        var policy = new FrozenEvaluationPolicy();
        policy.setProjectIds("5");
        capture.setEvaluationPolicy(policy);

        assertThat(capture.captureEligibleCandidate("episode")).isEmpty();

        verify(jdbc).queryForList(anyString(), eq("episode"));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void frozenTrajectoryCannotGeneratePatternsTemplatesOrEvolutionCandidates() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq("episode"))).thenReturn(List.of(Map.of("project_id", 5L)));
        var trajectory = mock(TrajectoryAnalysisService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(trajectory, "jdbc", jdbc);
        var policy = new FrozenEvaluationPolicy();
        policy.setProjectIds("5");
        trajectory.setEvaluationPolicy(policy);

        assertThat(trajectory.analyzeEpisode("episode")).isEmpty();

        verify(jdbc).queryForList(anyString(), eq("episode"));
        verifyNoMoreInteractions(jdbc);
    }
}
