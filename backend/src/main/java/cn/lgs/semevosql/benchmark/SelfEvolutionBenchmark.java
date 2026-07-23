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
package cn.lgs.semevosql.benchmark;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight, deterministic evaluation harness for measuring the value of accumulated
 * SemEvoSQL experience. Controlled benchmark runners can feed identical held-out cases at
 * each stage instead of changing production behavior merely to collect benchmark metrics.
 */
public final class SelfEvolutionBenchmark {

	private SelfEvolutionBenchmark() {
	}

	public static Summary evaluate(Stage stage, List<Observation> observations) {
		List<Observation> values = observations == null ? List.of() : List.copyOf(observations);
		int total = values.size();
		long successes = values.stream().filter(Observation::succeeded).count();
		long semanticCorrect = values.stream().filter(Observation::semanticResolutionCorrect).count();
		long clarifications = sum(values, Observation::clarificationCount);
		long retries = sum(values, Observation::retryCount);
		long wrongRecalls = sum(values, Observation::wrongRecallCount);
		long contamination = sum(values, Observation::crossUserContaminationCount);
		long usefulRecalls = sum(values, Observation::usefulRecallCount);
		long patternReuse = sum(values, Observation::patternReuseCount);
		long llmSql = sum(values, Observation::llmSqlGenerationCount);
		long latency = sum(values, Observation::latencyMs);
		long tokens = sum(values, Observation::tokenCount);
		return new Summary(stage, total, rate(successes, total), rate(semanticCorrect, total), rate(clarifications, total),
				rate(retries, total), rate(wrongRecalls, total), rate(contamination, total), rate(usefulRecalls, total),
				rate(patternReuse, total), rate(llmSql, total), average(latency, total), average(tokens, total), contamination == 0);
	}

	public static Map<Stage, Summary> evaluate(Map<Stage, List<Observation>> stages) {
		EnumMap<Stage, Summary> summaries = new EnumMap<>(Stage.class);
		for (Stage stage : Stage.values()) {
			summaries.put(stage, evaluate(stage, stages == null ? List.of() : stages.getOrDefault(stage, List.of())));
		}
		return Map.copyOf(summaries);
	}

	public static Delta compare(Summary baseline, Summary evolved) {
		if (baseline == null || evolved == null) {
			throw new IllegalArgumentException("baseline and evolved benchmark summaries are required");
		}
		return new Delta(baseline.stage(), evolved.stage(), evolved.taskSuccessRate() - baseline.taskSuccessRate(),
				evolved.semanticResolutionAccuracy() - baseline.semanticResolutionAccuracy(),
				evolved.clarificationRate() - baseline.clarificationRate(), evolved.retryRate() - baseline.retryRate(),
				evolved.wrongRecallRate() - baseline.wrongRecallRate(),
				evolved.crossUserContaminationRate() - baseline.crossUserContaminationRate(),
				evolved.usefulRecallRate() - baseline.usefulRecallRate(), evolved.patternReuseRate() - baseline.patternReuseRate(),
				evolved.llmSqlGenerationRate() - baseline.llmSqlGenerationRate(), evolved.averageLatencyMs() - baseline.averageLatencyMs(),
				evolved.averageTokenCount() - baseline.averageTokenCount(), evolved.scopeSafe());
	}

	private static long sum(List<Observation> values, java.util.function.ToLongFunction<Observation> extractor) {
		return values.stream().mapToLong(extractor).sum();
	}

	private static double rate(long value, int total) {
		return total == 0 ? 0d : value / (double) total;
	}

	private static double average(long value, int total) {
		return total == 0 ? 0d : value / (double) total;
	}

	public enum Stage {
		COLD,
		QUERY_CASE,
		SCOPED_BINDING,
		PATTERN,
		FULL_EVOLUTION
	}

	public record Observation(boolean succeeded, boolean semanticResolutionCorrect, int clarificationCount,
			int retryCount, int wrongRecallCount, int crossUserContaminationCount, int usefulRecallCount,
			int patternReuseCount, int llmSqlGenerationCount, long latencyMs, long tokenCount) {
		public Observation {
			clarificationCount = nonNegative(clarificationCount);
			retryCount = nonNegative(retryCount);
			wrongRecallCount = nonNegative(wrongRecallCount);
			crossUserContaminationCount = nonNegative(crossUserContaminationCount);
			usefulRecallCount = nonNegative(usefulRecallCount);
			patternReuseCount = nonNegative(patternReuseCount);
			llmSqlGenerationCount = nonNegative(llmSqlGenerationCount);
			latencyMs = Math.max(0L, latencyMs);
			tokenCount = Math.max(0L, tokenCount);
		}
	}

	public record Summary(Stage stage, int total, double taskSuccessRate, double semanticResolutionAccuracy,
			double clarificationRate, double retryRate, double wrongRecallRate, double crossUserContaminationRate,
			double usefulRecallRate, double patternReuseRate, double llmSqlGenerationRate, double averageLatencyMs,
			double averageTokenCount, boolean scopeSafe) {
	}

	public record Delta(Stage baseline, Stage evolved, double taskSuccessRateDelta,
			double semanticResolutionAccuracyDelta, double clarificationRateDelta, double retryRateDelta,
			double wrongRecallRateDelta, double crossUserContaminationRateDelta, double usefulRecallRateDelta,
			double patternReuseRateDelta, double llmSqlGenerationRateDelta, double averageLatencyMsDelta,
			double averageTokenCountDelta, boolean scopeSafe) {
	}

	private static int nonNegative(int value) {
		return Math.max(0, value);
	}
}
