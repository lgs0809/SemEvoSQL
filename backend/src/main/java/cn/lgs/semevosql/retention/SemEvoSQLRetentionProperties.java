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
package cn.lgs.semevosql.retention;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "semevosql.retention")
public class SemEvoSQLRetentionProperties {

	private boolean enabled;

	private boolean dryRun = true;

	@Min(1)
	private int terminalRunDays = 90;

	@Min(1)
	@Max(1000)
	private int batchSize = 100;

	@Min(1)
	private int batchAuditDays = 365;

	@Min(60_000)
	private long scanDelayMs = 3_600_000;

	@Min(60)
	@Max(3600)
	private long leaseDurationSeconds = 600;

}
