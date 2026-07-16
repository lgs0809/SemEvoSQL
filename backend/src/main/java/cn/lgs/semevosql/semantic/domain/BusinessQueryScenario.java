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

/** A DB-independent business query/use-case mined from project materials. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BusinessQueryScenario {

	private Long id;

	private Long projectId;

	private Long projectVersionId;

	private String scenarioCode;

	private String businessName;

	private String description;

	private String requirementJson;

	private Importance importance;

	private Status status;

	private Long sourceMaterialId;

	private Long sourceAttemptId;

	private String sourceLocation;

	private BigDecimal confidence;

	private String scenarioFingerprint;

	private LocalDateTime createTime;

	private LocalDateTime updateTime;

	public enum Importance {

		CORE, IMPORTANT, OPTIONAL, DISCOVERED

	}

	public enum Status {

		ACTIVE, HISTORICAL, DEPRECATED

	}

}
