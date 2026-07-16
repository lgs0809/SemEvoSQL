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

import cn.lgs.semevosql.semantic.domain.SemanticAssetProvenance;
import cn.lgs.semevosql.semantic.domain.SemanticMaterial;
import cn.lgs.semevosql.semantic.domain.SemanticMaterialAttempt;
import cn.lgs.semevosql.semantic.domain.SemanticMaterialRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisSemanticMaterialRepository implements SemanticMaterialRepository {

	private final SemEvoSQLSemanticMaterialMapper mapper;

	@Override
	public void insert(SemanticMaterial material) {
		mapper.insert(material);
	}

	@Override
	public void update(SemanticMaterial material) {
		mapper.update(material);
	}

	@Override
	public void delete(Long materialId) {
		mapper.delete(materialId);
	}

	@Override
	public Optional<SemanticMaterial> findByHash(Long projectVersionId, String contentHash) {
		return Optional.ofNullable(mapper.findByHash(projectVersionId, contentHash));
	}

	@Override
	public Optional<SemanticMaterial> findById(Long materialId) {
		return Optional.ofNullable(mapper.findById(materialId));
	}

	@Override
	public List<SemanticMaterial> findByVersion(Long projectVersionId) {
		return mapper.findByVersion(projectVersionId);
	}

	@Override
	public List<SemanticMaterial> findByVersionWithContent(Long projectVersionId) {
		return mapper.findByVersionWithContent(projectVersionId);
	}

	@Override
	public void insertAttempt(SemanticMaterialAttempt attempt) {
		mapper.insertAttempt(attempt);
	}

	@Override
	public void updateAttempt(SemanticMaterialAttempt attempt) {
		mapper.updateAttempt(attempt);
	}

	@Override
	public Optional<SemanticMaterialAttempt> findAttemptById(Long attemptId) {
		return Optional.ofNullable(mapper.findAttemptById(attemptId));
	}

	@Override
	public int findNextAttemptNo(Long materialId) {
		return mapper.findNextAttemptNo(materialId);
	}

	@Override
	public List<SemanticMaterialAttempt> findAttempts(Long materialId) {
		return mapper.findAttempts(materialId);
	}

	@Override
	public void insertProvenance(SemanticAssetProvenance provenance) {
		mapper.insertProvenance(provenance);
	}

	@Override
	public List<SemanticAssetProvenance> findProvenanceByMaterial(Long materialId) {
		return mapper.findProvenanceByMaterial(materialId);
	}

	@Override
	public Set<String> findActiveConflictGapKeys(Long projectVersionId) {
		return new LinkedHashSet<>(mapper.findActiveConflictGapKeys(projectVersionId));
	}

	@Override
	public void cloneProvenance(Long sourceAttemptId, Long targetAttemptId, Long targetMaterialId, Long projectId,
			Long projectVersionId) {
		mapper.cloneProvenance(sourceAttemptId, targetAttemptId, targetMaterialId, projectId, projectVersionId);
	}

}
