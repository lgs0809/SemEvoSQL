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
package cn.lgs.semevosql.properties;

import cn.lgs.semevosql.constant.Constant;
import cn.lgs.semevosql.service.llm.LlmServiceEnum;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = Constant.PROJECT_PROPERTIES_PREFIX)
public class SemEvoSQLProperties {

	private LlmServiceEnum llmServiceType = LlmServiceEnum.STREAM;

	private SqlExecutionPolicy sqlExecution = new SqlExecutionPolicy();

	/**
	 * sql执行失败重试次数
	 */
	private int maxSqlRetryCount = 10;

	/**
	 * sql优化最多次数
	 */
	private int maxSqlOptimizeCount = 10;

	/**
	 * sql优化分数阈值
	 */
	private double sqlScoreThreshold = 0.95;

	/**
	 * 最多保留的对话轮数
	 */
	private int maxturnhistory = 5;

	/**
	 * 单次规划最大长度限制
	 */
	private int maxplanlength = 2000;

	// 每张表的最大预估列数
	private int maxColumnsPerTable = 50;

	/**
	 * 是否启用SQL执行结果图表判断，默认启用
	 */
	private boolean enableSqlResultChart = true;

	/**
	 * 执行SQL结果图表化超时时间，默认3000ms
	 */
	private Long enrichSqlResultTimeout = 3000L;

	@Getter
	@Setter
	public static class SqlExecutionPolicy {

		/** Maximum rows returned by a single SQL execution. */
		private int maxRows = 1000;

		/** JDBC query timeout in seconds. */
		private int queryTimeoutSeconds = 30;

		/** Maximum generated SQL text length before execution. */
		private int maxSqlLength = 100000;

		/** Whether supported dialects must pass EXPLAIN before execution. */
		private boolean explainEnabled = true;

		/** Whether a limited preview execution is required before full execution. */
		private boolean previewEnabled = true;

		/** Maximum rows returned by preview execution. */
		private int previewRows = 50;

		/** Query timeout for EXPLAIN and preview execution. */
		private int preflightTimeoutSeconds = 10;

		/** Maximum number of physical tables referenced by one query. */
		private int maxJoinTables = 8;

		/** Maximum estimated rows scanned across physical scan operators. */
		private long maxEstimatedRows = 10000000L;

		/** Maximum estimated rows materialized by any intermediate plan operator. */
		private long maxEstimatedIntermediateRows = 20000000L;

		/** Maximum estimated rows produced by a join operator before execution is rejected. */
		private long maxEstimatedJoinRows = 20000000L;

		/** Maximum estimated rows entering a sort/filesort operator. */
		private long maxEstimatedSortRows = 10000000L;

		/** Maximum estimated rows produced/handled by aggregation/grouping operators. */
		private long maxEstimatedAggregateRows = 20000000L;

		/** Maximum dialect-reported optimizer cost. Disabled by default because cost units are dialect-specific. */
		private double maxEstimatedCost = 0D;

		/** Reject a full-table scan once its estimated scan rows exceed this threshold. */
		private long maxFullScanRows = 5000000L;

		/** Whether the SQL must constrain one of the semantic time columns. */
		private boolean requireTimeFilter = false;

		/** Maximum literal date range accepted when both range endpoints are present. */
		private int maxTimeRangeDays = 366;

		/** Whether a full table scan reported by EXPLAIN must be rejected. */
		private boolean rejectFullTableScan = false;

	}

}
