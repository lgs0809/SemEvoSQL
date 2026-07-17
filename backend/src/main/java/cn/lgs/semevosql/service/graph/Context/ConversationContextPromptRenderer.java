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
package cn.lgs.semevosql.service.graph.Context;

import cn.lgs.semevosql.properties.ConversationContextProperties;
import cn.lgs.semevosql.service.graph.Context.ConversationContextEnvelope.CompactedHistory;
import cn.lgs.semevosql.service.graph.Context.ConversationContextEnvelope.ConversationState;
import cn.lgs.semevosql.service.graph.Context.ConversationContextEnvelope.TurnView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Renders node-specific context views while dropping only complete semantic units. */
@Component
public class ConversationContextPromptRenderer {

	public enum Stage {

		QUERY_ENHANCE, FEASIBILITY, GENERAL

	}

	private final ConversationContextProperties properties;

	private final ApproximateTokenCounter tokenCounter;

	public ConversationContextPromptRenderer(ConversationContextProperties properties,
			ApproximateTokenCounter tokenCounter) {
		this.properties = properties;
		this.tokenCounter = tokenCounter;
	}

	public String render(ConversationContextEnvelope envelope, Stage stage) {
		if (envelope == null || envelope.recentTurns().isEmpty() && envelope.retrievedTurns().isEmpty()
				&& envelope.compactedHistory().isEmpty() && empty(envelope.state())) {
			return "(无)";
		}
		String constraint = "以下是历史会话事实；只有当前消息明确承接时才适用，不能覆盖当前消息或本次确认；独立新问题不得继承旧条件。\n" + "压缩摘要仅用于理解长期背景，不得据此创建或恢复指标、维度、过滤条件、时间范围或分组。\n";
		int remaining = budget(stage);
		String state = renderState(envelope.state());
		if (tokenCounter.estimate(state) > remaining) {
			state = renderCompactState(envelope.state(), remaining);
		}
		remaining = Math.max(0, remaining - tokenCounter.estimate(state));
		List<TurnView> recent = selectRecent(envelope.recentTurns(), remaining, stage);
		remaining = Math.max(0,
				remaining - recent.stream().mapToInt(turn -> tokenCounter.estimate(renderTurn(turn, stage))).sum());
		List<TurnView> retrieved = supportsRetrieved(stage)
				? selectRetrieved(envelope.retrievedTurns(), remaining, stage) : List.of();
		remaining = Math.max(0,
				remaining - retrieved.stream().mapToInt(turn -> tokenCounter.estimate(renderTurn(turn, stage))).sum());
		String compacted = renderCompactedHistory(envelope.compactedHistory(), stage, remaining);
		StringBuilder content = new StringBuilder();
		if (!state.isBlank()) {
			content.append("# 已解析的历史会话状态（按当前需求决定适用性）\n").append(state.trim()).append('\n');
		}
		appendTurns(content, "# 最近轮次（较新内容优先）", recent, stage);
		appendTurns(content, "# 按当前问题召回的较早轮次（精确历史事实）", retrieved, stage);
		if (!compacted.isBlank()) {
			content.append("# 更早会话压缩摘要（仅作背景，不得恢复执行条件）\n").append(compacted);
		}
		if (content.isEmpty()) {
			return "(无)";
		}
		return (constraint + content).trim();
	}

	private String renderTurn(TurnView turn, Stage stage) {
		ConversationTurnSummary summary = turn.summary();
		StringBuilder value = new StringBuilder("<turn sequence=\"").append(turn.sequence())
			.append("\">\n用户问题: ")
			.append(Objects.toString(turn.userQuestion(), ""))
			.append('\n');
		if (turn.canonicalQuery() != null && !turn.canonicalQuery().isBlank()
				&& !turn.canonicalQuery().equals(turn.userQuestion())) {
			value.append("规范化问题: ").append(turn.canonicalQuery()).append('\n');
		}
		appendAssets(value, "指标", summary.metrics());
		appendAssets(value, "维度", summary.dimensions());
		appendFilters(value, summary.filters());
		appendTimeRange(value, summary.timeRange());
		appendList(value, "分组", summary.groupBy());
		appendClarifications(value, summary.clarifications());
		if (stage == Stage.QUERY_ENHANCE || stage == Stage.GENERAL) {
			appendResult(value, summary.result());
		}
		if (summary.metrics().isEmpty() && summary.dimensions().isEmpty() && summary.filters().isEmpty()
				&& summary.plannerSummary() != null && !summary.plannerSummary().isBlank()) {
			if (tokenCounter.estimate(summary.plannerSummary()) <= properties.getFallbackPlannerMaxTokens()) {
				value.append("历史计划: ").append(summary.plannerSummary()).append('\n');
			}
			else {
				value.append("历史计划: [超出完整语义单元预算，已省略]\n");
			}
		}
		return value.append("</turn>").toString();
	}

	private String renderState(ConversationState state) {
		StringBuilder value = new StringBuilder();
		appendAssets(value, "当前模型", state.models());
		appendAssets(value, "当前指标", state.metrics());
		appendAssets(value, "当前维度", state.dimensions());
		appendFilters(value, state.filters());
		appendTimeRange(value, state.timeRange());
		appendList(value, "当前分组", state.groupBy());
		appendClarifications(value, state.clarifications());
		appendResult(value, state.lastResult());
		return value.toString();
	}

	private String renderCompactState(ConversationState state, int limit) {
		List<String> units = new ArrayList<>();
		state.metrics().forEach(asset -> units.add(assetLine("当前指标", asset)));
		state.dimensions().forEach(asset -> units.add(assetLine("当前维度", asset)));
		if (state.timeRange() != null) {
			StringBuilder line = new StringBuilder();
			appendTimeRange(line, state.timeRange());
			units.add(line.toString());
		}
		state.filters().forEach(filter -> {
			StringBuilder line = new StringBuilder();
			appendFilters(line, List.of(filter));
			units.add(line.toString());
		});
		state.clarifications().forEach(clarification -> {
			StringBuilder line = new StringBuilder();
			appendClarifications(line, List.of(clarification));
			units.add(line.toString());
		});
		state.models().forEach(asset -> units.add(assetLine("当前模型", asset)));
		state.groupBy().forEach(group -> units.add("当前分组: " + group + "\n"));
		if (state.lastResult() != null) {
			units.add("上一结果: " + state.lastResult().artifactId() + " rows=" + state.lastResult().rowCount() + "\n");
		}
		StringBuilder selected = new StringBuilder();
		for (String unit : units) {
			if (tokenCounter.estimate(selected + unit) > limit) {
				continue;
			}
			selected.append(unit);
		}
		return selected.toString();
	}

	private String renderCompactedHistory(CompactedHistory history, Stage stage, int remaining) {
		if (history == null || history.isEmpty() || remaining <= 0) {
			return "";
		}
		List<String> units = new ArrayList<>();
		units.add("摘要: " + history.summary() + "\n");
		history.importantCorrections().forEach(value -> units.add("历史修正: " + value + "\n"));
		history.unresolvedQuestions().forEach(value -> units.add("未解决问题: " + value + "\n"));
		StringBuilder selected = new StringBuilder();
		for (String unit : units) {
			if (tokenCounter.estimate(selected + unit) > remaining) {
				continue;
			}
			selected.append(unit);
		}
		return selected.toString();
	}

	private String assetLine(String label, ConversationTurnSummary.AssetFact asset) {
		return label + ": " + asset.code() + (asset.businessName() == null || asset.businessName().isBlank() ? ""
				: "(" + asset.businessName() + ")") + "\n";
	}

	private List<TurnView> selectRecent(List<TurnView> turns, int remaining, Stage stage) {
		List<TurnView> selected = new ArrayList<>();
		for (int index = turns.size() - 1; index >= 0; index--) {
			TurnView turn = turns.get(index);
			int unitSize = tokenCounter.estimate(renderTurn(turn, stage));
			if (unitSize > remaining) {
				break;
			}
			selected.add(turn);
			remaining -= unitSize;
		}
		selected.sort(Comparator.comparingLong(TurnView::sequence));
		return selected;
	}

	private List<TurnView> selectRetrieved(List<TurnView> turns, int remaining, Stage stage) {
		List<TurnView> selected = new ArrayList<>();
		for (TurnView turn : turns) {
			int unitSize = tokenCounter.estimate(renderTurn(turn, stage));
			if (unitSize <= remaining) {
				selected.add(turn);
				remaining -= unitSize;
			}
		}
		return selected;
	}

	private void appendTurns(StringBuilder target, String title, List<TurnView> turns, Stage stage) {
		if (turns.isEmpty()) {
			return;
		}
		target.append(title).append('\n');
		for (TurnView turn : turns) {
			target.append(renderTurn(turn, stage)).append('\n');
		}
	}

	private void appendAssets(StringBuilder value, String label, List<ConversationTurnSummary.AssetFact> assets) {
		if (assets == null || assets.isEmpty()) {
			return;
		}
		value.append(label)
			.append(": ")
			.append(assets.stream()
				.map(asset -> asset.code() + (asset.businessName() == null || asset.businessName().isBlank() ? ""
						: "(" + asset.businessName() + ")"))
				.collect(Collectors.joining(", ")))
			.append('\n');
	}

	private void appendFilters(StringBuilder value, List<ConversationTurnSummary.FilterFact> filters) {
		if (filters == null || filters.isEmpty()) {
			return;
		}
		value.append("过滤条件: ")
			.append(filters.stream()
				.map(filter -> Objects.toString(filter.modelCode(), "") + "."
						+ Objects.toString(filter.columnName(), "") + " " + Objects.toString(filter.operator(), "")
						+ " " + Objects.toString(filter.value(), ""))
				.collect(Collectors.joining("; ")))
			.append('\n');
	}

	private void appendTimeRange(StringBuilder value, ConversationTurnSummary.TimeRangeFact timeRange) {
		if (timeRange == null) {
			return;
		}
		value.append("时间范围: ")
			.append(Objects.toString(timeRange.relativeExpression(), ""))
			.append(" [")
			.append(Objects.toString(timeRange.startInclusive(), ""))
			.append(", ")
			.append(Objects.toString(timeRange.endExclusive(), ""))
			.append(") granularity=")
			.append(Objects.toString(timeRange.granularity(), ""))
			.append('\n');
	}

	private void appendList(StringBuilder value, String label, List<String> items) {
		if (items != null && !items.isEmpty()) {
			value.append(label).append(": ").append(String.join(", ", items)).append('\n');
		}
	}

	private void appendClarifications(StringBuilder value,
			List<ConversationTurnSummary.ClarificationFact> clarifications) {
		if (clarifications == null || clarifications.isEmpty()) {
			return;
		}
		value.append("已确认口径: ")
			.append(clarifications.stream()
				.map(item -> Objects.toString(item.rawExpression(), item.assetKey()) + " => "
						+ Objects.toString(item.resolvedValue(), ""))
				.collect(Collectors.joining("; ")))
			.append('\n');
	}

	private void appendResult(StringBuilder value, ConversationTurnSummary.ResultFact result) {
		if (result == null) {
			return;
		}
		value.append("上一结果: id=")
			.append(result.artifactId())
			.append(" type=")
			.append(result.artifactType())
			.append(" rows=")
			.append(result.rowCount());
		if (!result.columns().isEmpty()) {
			value.append(" columns=").append(result.columns());
		}
		if (!result.scalarValues().isEmpty()) {
			value.append(" values=").append(result.scalarValues());
		}
		value.append('\n');
	}

	private boolean supportsRetrieved(Stage stage) {
		return stage == Stage.QUERY_ENHANCE || stage == Stage.GENERAL;
	}

	private int budget(Stage stage) {
		return switch (stage) {
			case QUERY_ENHANCE -> properties.getQueryEnhanceMaxTokens();
			case FEASIBILITY -> properties.getFeasibilityMaxTokens();
			case GENERAL -> properties.getGeneralMaxTokens();
		};
	}

	private boolean empty(ConversationState state) {
		return state.models().isEmpty() && state.metrics().isEmpty() && state.dimensions().isEmpty()
				&& state.filters().isEmpty() && state.timeRange() == null && state.groupBy().isEmpty()
				&& state.clarifications().isEmpty() && state.lastResult() == null;
	}

}
