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
package cn.lgs.semevosql.semantic.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Immutable source evidence for one semantic asset produced by one parse attempt. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticAssetProvenance {

	private Long id;

	private Long projectId;

	private Long projectVersionId;

	private Long materialId;

	private Long attemptId;

	private AssetType assetType;

	private String assetKey;

	private String assetFingerprint;

	private Disposition disposition;

	private String conflictGapKey;

	private BigDecimal confidence;

	private String sourceLocation;

	private String extractionModel;

	private String evidence;

	private LocalDateTime createTime;

	public enum AssetType {

		MODEL, COLUMN, METRIC, DIMENSION, RELATIONSHIP, GRAIN, ENUM_VALUE, RULE

	}

	public enum Disposition {

		APPLIED, CONFLICT

	}

}
