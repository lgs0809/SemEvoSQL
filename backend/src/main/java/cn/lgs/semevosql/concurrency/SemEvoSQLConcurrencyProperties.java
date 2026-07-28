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
package cn.lgs.semevosql.concurrency;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "semevosql.concurrency")
public class SemEvoSQLConcurrencyProperties {

	// One absolute deadline covers the whole governed Graph. Semantic planning consumes a shared
	// sub-budget and terminal attempt fencing rejects any result that returns after this deadline.
	private Pool interactiveQuery = Pool.of(20, 100, 5000, 300000);

	private Pool initialization = Pool.of(4, 20, 10000, 600000);

	private Pool evaluation = Pool.of(2, 20, 10000, 600000);

	private Pool sqlExecution = Pool.of(30, 100, 5000, 120000);

	private SqlLimits sqlLimits = new SqlLimits();

	@Data
	public static class Pool {

		private int maxConcurrent;

		private int queueCapacity;

		private long queueTimeoutMs;

		private long taskTimeoutMs;

		static Pool of(int maxConcurrent, int queueCapacity, long queueTimeoutMs, long taskTimeoutMs) {
			Pool pool = new Pool();
			pool.setMaxConcurrent(maxConcurrent);
			pool.setQueueCapacity(queueCapacity);
			pool.setQueueTimeoutMs(queueTimeoutMs);
			pool.setTaskTimeoutMs(taskTimeoutMs);
			return pool;
		}

	}

	@Data
	public static class SqlLimits {

		private int globalMaxConcurrent = 30;

		private int defaultPerDatasource = 5;

		private int defaultPerProject = 10;

		private int defaultPerUser = 3;

		private long acquireTimeoutMs = 5000;

		private int failureThreshold = 5;

		private long circuitOpenMs = 30000;

	}

}
