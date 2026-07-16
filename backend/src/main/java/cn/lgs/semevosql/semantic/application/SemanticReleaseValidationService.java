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

import cn.lgs.semevosql.project.domain.ProjectVersionCatalogReadiness;
import cn.lgs.semevosql.project.domain.ProjectVersionReleaseGate;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SemanticReleaseValidationService implements ProjectVersionReleaseGate {

	private final SemanticCatalogRepository catalogRepository;

	private final ProjectVersionCatalogReadiness catalogReadiness;

	private final ScenarioPreflightService scenarioPreflightService;

	@Override
	public ReleaseReport validate(Long projectId, Long sourceVersionId, Long targetVersionId) {
		SemanticCatalogSnapshot target = catalogRepository.loadCatalog(projectId, targetVersionId);
		ProjectVersionCatalogReadiness.CatalogReadiness readiness = catalogReadiness.assess(projectId, targetVersionId);
		ScenarioPreflightService.PreflightReport scenarioPreflight = scenarioPreflightService.preflightCore(projectId,
				targetVersionId);
		List<String> breakingChanges = sourceVersionId == null ? List.of()
				: diff(catalogRepository.loadCatalog(projectId, sourceVersionId), target);
		List<String> fanOutRisks = target.getRelationships()
			.stream()
			.filter(value -> value.getStatus() == SemanticAssetStatus.ENABLED)
			.filter(value -> value.getCardinality() != null && value.getCardinality().name().contains("MANY"))
			.map(value -> "fan-out review required: " + value.getRelationshipCode() + " " + value.getCardinality())
			.toList();
		List<String> warnings = new ArrayList<>();
		if (sourceVersionId == null) {
			warnings.add("initial release has no previous version for differential replay");
		}
		warnings.add("no Golden Query replay cases are registered for this version");
		warnings.add("CORE scenario preflight: " + scenarioPreflight.passed() + "/" + scenarioPreflight.total()
				+ " compiled successfully");
		boolean passed = readiness.ready() && scenarioPreflight.passedAll();
		return new ReleaseReport(projectId, sourceVersionId, targetVersionId,
				SemanticCatalogFingerprint.fingerprint(target), passed, breakingChanges, List.copyOf(warnings),
				List.of(), fanOutRisks, 0, 0, 0, passed, scenarioPreflight.total(), scenarioPreflight.passed(),
				scenarioPreflight.failed(), scenarioPreflight.failures(), LocalDateTime.now());
	}

	private List<String> diff(SemanticCatalogSnapshot source, SemanticCatalogSnapshot target) {
		Set<String> changes = new LinkedHashSet<>();
		Map<String, SemanticCatalogSnapshot.Model> sourceModels = index(source.getModels(),
				SemanticCatalogSnapshot.Model::getModelCode);
		Map<String, SemanticCatalogSnapshot.Model> targetModels = index(target.getModels(),
				SemanticCatalogSnapshot.Model::getModelCode);
		sourceModels.forEach((code, model) -> {
			SemanticCatalogSnapshot.Model next = targetModels.get(code);
			if (next == null || next.getStatus() != SemanticAssetStatus.ENABLED) {
				changes.add("model removed or disabled: " + code);
			}
		});
		Map<String, SemanticCatalogSnapshot.Metric> sourceMetrics = index(source.getMetrics(),
				SemanticCatalogSnapshot.Metric::getMetricCode);
		Map<String, SemanticCatalogSnapshot.Metric> targetMetrics = index(target.getMetrics(),
				SemanticCatalogSnapshot.Metric::getMetricCode);
		sourceMetrics.forEach((code, metric) -> {
			SemanticCatalogSnapshot.Metric next = targetMetrics.get(code);
			if (next == null) {
				changes.add("metric removed: " + code);
			}
			else if (!java.util.Objects.equals(metric.getExpression(), next.getExpression())) {
				changes.add("metric expression changed: " + code);
			}
		});
		Map<String, SemanticCatalogSnapshot.Relationship> sourceRelationships = index(source.getRelationships(),
				SemanticCatalogSnapshot.Relationship::getRelationshipCode);
		Map<String, SemanticCatalogSnapshot.Relationship> targetRelationships = index(target.getRelationships(),
				SemanticCatalogSnapshot.Relationship::getRelationshipCode);
		sourceRelationships.forEach((code, relationship) -> {
			SemanticCatalogSnapshot.Relationship next = targetRelationships.get(code);
			if (next == null) {
				changes.add("relationship removed: " + code);
			}
			else if (!java.util.Objects.equals(relationship.getCardinality(), next.getCardinality())
					|| !java.util.Objects.equals(relationship.getJoinCondition(), next.getJoinCondition())) {
				changes.add("relationship contract changed: " + code);
			}
		});
		return List.copyOf(changes);
	}

	private <T> Map<String, T> index(List<T> values, Function<T, String> key) {
		return values.stream().collect(Collectors.toMap(key, Function.identity(), (left, right) -> right));
	}

}
