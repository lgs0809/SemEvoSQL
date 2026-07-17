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

import cn.lgs.semevosql.service.graph.Context.ConversationContextEnvelope.ConversationState;
import cn.lgs.semevosql.service.graph.Context.ConversationTurnSummary.AssetFact;
import cn.lgs.semevosql.service.graph.Context.ConversationTurnSummary.ClarificationFact;
import cn.lgs.semevosql.service.graph.Context.ConversationTurnSummary.FilterFact;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Resolves a deterministic, context-complete semantic planning query before a durable Run
 * is created. The original user question remains the execution input; this expanded query
 * is used only for governed Catalog recall and Semantic Blueprint construction.
 */
@Component
public class ConversationContextPlanningQueryResolver {

	private static final Pattern ANALYTIC_MODIFIER = Pattern
		.compile("按|拆分|分组|分别|各|趋势|同比|环比|排序|前\\d+|最高|最低|最多|最少|只看|筛选|过滤");

	private static final Pattern GROUPING_REPLACEMENT = Pattern.compile("按|拆分|分组|分别|各|维度|趋势");

	private static final Pattern METRIC_REPLACEMENT = Pattern.compile(
			"金额|订单数|用户数|客户数|数量|销售额|收入|营收|成本|利润|毛利|gmv|流水|客单价|转化率|成功率|失败率|时长|延时|流量",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern TIME_REPLACEMENT = Pattern.compile("今天|今日|昨天|昨日|本周|上周|本月|上月|今年|去年|日|周|月|年");

	private static final Pattern FILTER_CLEAR = Pattern.compile(
			"全部|所有|不限|不过滤|取消.{0,12}(筛选|过滤|条件)|去掉.{0,12}(筛选|过滤|条件)|移除.{0,12}(筛选|过滤|条件)|不(再)?(按|添加|使用|保留|恢复)?.{0,10}(筛选|过滤|条件)");

	private static final Pattern HISTORICAL_BACKGROUND = Pattern.compile("历史|最早|最开始|更早|此前|曾经|旧口径|旧条件|回顾");

	private static final Pattern NON_EXECUTABLE_HISTORY = Pattern
		.compile("背景|说明即可|仅作说明|已取消|不得恢复|不能恢复|不要恢复|不再恢复|绝对不得|不添加|不保留");

	private static final Pattern CANCELLATION_CLAUSE = Pattern
		.compile("取消|去掉|移除|不保留|不添加|不使用|不按|不过滤|不筛选|不得恢复|不能恢复|不要恢复");

	private static final Pattern EXECUTION_REQUEST = Pattern.compile("查询|统计|汇总|计算|列出|返回|查看|分析|对比|比较|趋势|排名|前\\d+");

	private static final Pattern CONTEXTUAL_FOLLOW_UP = Pattern
		.compile("刚才|上次|前面|继续|再查|再看|相比|比较|那个|这些|它们|同样|基于上述|^那|那.{0,8}呢");

	private static final Map<String, String> RELATIVE_TIME_TEXT = Map.ofEntries(Map.entry("CURRENT_DAY", "今天"),
			Map.entry("PREVIOUS_DAY", "昨天"), Map.entry("CURRENT_WEEK", "本周"), Map.entry("PREVIOUS_WEEK", "上周"),
			Map.entry("CURRENT_MONTH", "本月"), Map.entry("PREVIOUS_MONTH", "上月"), Map.entry("CURRENT_YEAR", "今年"),
			Map.entry("PREVIOUS_YEAR", "去年"));

	public String resolve(String question, ConversationContextEnvelope envelope) {
		String original = Objects.toString(question, "").trim();
		String current = sanitizeForExecution(original);
		if (current.isEmpty() || envelope == null || empty(envelope.state()) || !shouldInherit(current, envelope)) {
			return current;
		}

		ConversationState state = envelope.state();
		List<String> facts = new ArrayList<>();
		if (!METRIC_REPLACEMENT.matcher(original).find()) {
			appendAssets(facts, "上一轮指标", state.metrics());
		}
		appendAssets(facts, "上一轮模型", state.models());
		if (!GROUPING_REPLACEMENT.matcher(original).find()) {
			appendAssets(facts, "上一轮维度", state.dimensions());
		}
		if (!TIME_REPLACEMENT.matcher(original).find() && state.timeRange() != null) {
			String relative = Objects.toString(state.timeRange().relativeExpression(), "");
			facts.add("上一轮时间=" + RELATIVE_TIME_TEXT.getOrDefault(relative, relative));
		}
		if (!FILTER_CLEAR.matcher(original).find()) {
			appendFilters(facts, state.filters());
		}
		appendClarifications(facts, state.clarifications());
		if (facts.isEmpty()) {
			return current;
		}
		return current + "；上下文补全：" + String.join("；", facts);
	}

	private boolean shouldInherit(String question, ConversationContextEnvelope envelope) {
		String compact = question.replaceAll("\\s+", "");
		if (envelope.retrieval().explicitHistoricalReference() && CONTEXTUAL_FOLLOW_UP.matcher(compact).find()) {
			return true;
		}
		if (compact.length() > 24 || !ANALYTIC_MODIFIER.matcher(compact).find()) {
			return false;
		}
		Set<String> priorTerms = new LinkedHashSet<>();
		collectTerms(priorTerms, envelope.state().metrics());
		collectTerms(priorTerms, envelope.state().models());
		String normalized = normalize(question);
		return priorTerms.stream().noneMatch(normalized::contains);
	}

	public static String sanitizeForExecution(String question) {
		if (question == null || question.isBlank()) {
			return "";
		}
		List<String> executableClauses = Pattern.compile("[；;。！？!?，,\\n]+")
			.splitAsStream(question)
			.map(String::trim)
			.filter(clause -> !clause.isBlank())
			.filter(clause -> !(HISTORICAL_BACKGROUND.matcher(clause).find()
					&& (!EXECUTION_REQUEST.matcher(clause).find() || NON_EXECUTABLE_HISTORY.matcher(clause).find())))
			.filter(clause -> !(CANCELLATION_CLAUSE.matcher(clause).find()
					&& !EXECUTION_REQUEST.matcher(clause).find()))
			.toList();
		return executableClauses.isEmpty() ? question.trim() : String.join("；", executableClauses);
	}

	private void appendAssets(List<String> target, String label, List<AssetFact> assets) {
		List<String> values = assets.stream()
			.map(this::assetText)
			.filter(value -> !value.isBlank())
			.distinct()
			.toList();
		if (!values.isEmpty()) {
			target.add(label + "=" + String.join(",", values));
		}
	}

	private String assetText(AssetFact asset) {
		String businessName = Objects.toString(asset.businessName(), "").trim();
		String code = Objects.toString(asset.code(), "").trim();
		if (businessName.isEmpty()) {
			return code;
		}
		return code.isEmpty() || businessName.equalsIgnoreCase(code) ? businessName : businessName + "(" + code + ")";
	}

	private void appendFilters(List<String> target, List<FilterFact> filters) {
		for (FilterFact filter : filters) {
			target.add("上一轮筛选=" + Objects.toString(filter.modelCode(), "") + "."
					+ Objects.toString(filter.columnName(), "") + " " + Objects.toString(filter.operator(), "") + " "
					+ Objects.toString(filter.value(), ""));
		}
	}

	private void appendClarifications(List<String> target, List<ClarificationFact> clarifications) {
		for (ClarificationFact clarification : clarifications) {
			target.add("上一轮已确认=" + Objects.toString(clarification.rawExpression(), clarification.assetKey()) + "->"
					+ Objects.toString(clarification.resolvedValue(), ""));
		}
	}

	private void collectTerms(Set<String> target, List<AssetFact> assets) {
		for (AssetFact asset : assets) {
			for (String value : List.of(Objects.toString(asset.code(), ""),
					Objects.toString(asset.businessName(), ""))) {
				String normalized = normalize(value);
				if (normalized.length() >= 2) {
					target.add(normalized);
				}
			}
		}
	}

	private String normalize(String value) {
		return Objects.toString(value, "").toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
	}

	private boolean empty(ConversationState state) {
		return state.metrics().isEmpty() && state.models().isEmpty() && state.dimensions().isEmpty()
				&& state.filters().isEmpty() && state.timeRange() == null && state.clarifications().isEmpty();
	}

}
