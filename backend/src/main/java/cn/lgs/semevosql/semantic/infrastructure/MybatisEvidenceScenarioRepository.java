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
package cn.lgs.semevosql.semantic.infrastructure;

import cn.lgs.semevosql.semantic.domain.BusinessQueryScenario;
import cn.lgs.semevosql.semantic.domain.BusinessQueryScenarioRepository;
import cn.lgs.semevosql.semantic.domain.ProjectEvidence;
import cn.lgs.semevosql.semantic.domain.ProjectEvidenceRepository;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisEvidenceScenarioRepository implements ProjectEvidenceRepository, BusinessQueryScenarioRepository {

	private final SemEvoSQLEvidenceScenarioMapper mapper;

	@Override
	public void insert(ProjectEvidence evidence) {
		mapper.insertEvidence(evidence);
	}

	@Override
	public List<ProjectEvidence> findByAttempt(Long attemptId) {
		return mapper.findEvidenceByAttempt(attemptId);
	}

	@Override
	public List<ProjectEvidence> findEvidenceByVersion(Long projectVersionId) {
		return mapper.findEvidenceByVersion(projectVersionId);
	}

	@Override
	public List<ProjectEvidence> findActiveEvidenceByVersion(Long projectVersionId) {
		return mapper.findActiveEvidenceByVersion(projectVersionId);
	}

	@Override
	public void cloneAttemptEvidence(Long sourceAttemptId, Long targetAttemptId, Long targetMaterialId, Long projectId,
			Long projectVersionId) {
		mapper.cloneAttemptEvidence(sourceAttemptId, targetAttemptId, targetMaterialId, projectId, projectVersionId);
	}

	@Override
	public void insert(BusinessQueryScenario scenario) {
		mapper.insertScenario(scenario);
	}

	@Override
	public void update(BusinessQueryScenario scenario) {
		mapper.updateScenario(scenario);
	}

	@Override
	public Optional<BusinessQueryScenario> findById(Long scenarioId) {
		return Optional.ofNullable(mapper.findScenarioById(scenarioId));
	}

	@Override
	public Optional<BusinessQueryScenario> findByFingerprint(Long projectVersionId, String scenarioFingerprint) {
		return Optional.ofNullable(mapper.findScenarioByFingerprint(projectVersionId, scenarioFingerprint));
	}

	@Override
	public List<BusinessQueryScenario> findScenariosByVersion(Long projectVersionId) {
		return mapper.findScenariosByVersion(projectVersionId);
	}

	@Override
	public List<BusinessQueryScenario> findActiveByVersion(Long projectVersionId) {
		return mapper.findActiveScenariosByVersion(projectVersionId);
	}

	@Override
	public void reconcileStatuses(Long projectVersionId) {
		mapper.reconcileScenarioStatuses(projectVersionId);
	}

	@Override
	public void cloneAttemptScenarios(Long sourceAttemptId, Long targetAttemptId, Long targetMaterialId, Long projectId,
			Long projectVersionId) {
		mapper.cloneAttemptScenarios(sourceAttemptId, targetAttemptId, targetMaterialId, projectId, projectVersionId);
	}

}
