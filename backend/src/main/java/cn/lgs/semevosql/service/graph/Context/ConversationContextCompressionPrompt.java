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
import cn.lgs.semevosql.common.json.CanonicalJson;
import cn.lgs.semevosql.service.aimodelconfig.AiModelRegistry;
import cn.lgs.semevosql.service.graph.Context.ConversationTurnRepository.ConversationTurn;
import java.util.List;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

/**
 * Dedicated SemEvoSQL prompt for compacting only older semantic conversation history.
 */
@Component
public class ConversationContextCompressionPrompt {

	static final String SYSTEM_PROMPT = """
			你是 SemEvoSQL 的历史会话压缩器。

			你的任务是把较早的 NL2SQL 对话压缩成简短的语义背景，
			用于帮助后续模型理解用户的长期分析目标、历史取消和未解决问题。

			严格遵守以下规则：
			1. 只能使用输入中明确提供的信息，不得推断或补充事实。
			2. 不生成 SQL，不回答用户问题。
			3. 不创建新的模型、指标、维度、过滤条件、时间范围或分组字段。
			4. 输入中的 code 必须原样保留，不得改写或发明新 code。
			5. 必须保留用户明确表达的取消、替换、否定和修正。
			6. 已被取消或替换的条件不得描述为当前仍然有效。
			7. 不保留原始 SQL、Schema、结果集、Result Memory 或推理过程。
			8. 不确定的问题放入 unresolvedQuestions，不得猜测。
			9. summary 只描述历史背景，不描述当前权威执行状态。
			10. 只能输出符合指定 Schema 的 JSON，不得输出 Markdown 或额外文字。
			11. coveredThroughSequence 必须等于输入的 expectedCoveredThroughSequence。
			""";

	private final AiModelRegistry modelRegistry;

	private final CanonicalJson canonicalJson;

	private final ConversationContextProperties properties;

	private final BeanOutputConverter<ConversationContextCompressionOutput> outputConverter = new BeanOutputConverter<>(
			ConversationContextCompressionOutput.class);

	public ConversationContextCompressionPrompt(AiModelRegistry modelRegistry, CanonicalJson canonicalJson,
			ConversationContextProperties properties) {
		this.modelRegistry = modelRegistry;
		this.canonicalJson = canonicalJson;
		this.properties = properties;
	}

	public CompressionPromptRequest build(ConversationContextCompressionOutput previous, List<ConversationTurn> turns,
			long expectedCoveredThroughSequence) {
		List<CompressionTurnInput> inputs = turns.stream().map(this::input).toList();
		String previousJson = previous == null ? "(无)" : canonicalJson.write(previous);
		String turnsJson = canonicalJson.write(inputs);
		String userPrompt = """
				请压缩以下较早会话。

				上一版摘要：
				%s

				待压缩轮次：
				%s

				必须覆盖到的轮次：
				%d

				严格长度和数量限制：
				- summary 最多 %d 个字符，只写一段高度压缩的历史背景，不重复逐轮复述。
				- importantCorrections 最多 %d 项，每项最多 120 个字符。
				- unresolvedQuestions 最多 %d 项，每项最多 120 个字符。
				- 没有内容时数组输出 []，不要为了填满字段而重复信息。

				输出必须符合：
				%s
				""".formatted(previousJson, turnsJson, expectedCoveredThroughSequence,
				properties.getCompressedSummaryMaxChars(), properties.getCompressedCorrectionMaxCount(),
				properties.getCompressedQuestionMaxCount(), outputConverter.getFormat());
		String validatorInput = previousJson + "\n" + turnsJson + "\n" + expectedCoveredThroughSequence;
		return new CompressionPromptRequest(SYSTEM_PROMPT, userPrompt, validatorInput);
	}

	public String call(CompressionPromptRequest request) {
		String content = modelRegistry.getChatClient()
			.prompt()
			.system(request.systemPrompt())
			.user(request.userPrompt())
			.stream()
			.content()
			.collectList()
			.map(parts -> String.join("", parts))
			.block();
		return content == null ? "" : content;
	}

	private CompressionTurnInput input(ConversationTurn turn) {
		ConversationTurnSummary summary = ConversationContextSummarySupport.read(turn);
		String canonicalQuery = turn.canonicalQuery() == null || turn.canonicalQuery().isBlank()
				? summary.canonicalQuery() : turn.canonicalQuery();
		return new CompressionTurnInput(turn.turnSequence(), turn.userQuestion(), canonicalQuery, summary.models(),
				summary.metrics(), summary.dimensions(), summary.filters(), summary.timeRange(), summary.groupBy(),
				summary.clarifications());
	}

	public record CompressionPromptRequest(String systemPrompt, String userPrompt, String validatorInput) {
	}

	private record CompressionTurnInput(long sequence, String userQuestion, String canonicalQuery,
			List<ConversationTurnSummary.AssetFact> models, List<ConversationTurnSummary.AssetFact> metrics,
			List<ConversationTurnSummary.AssetFact> dimensions, List<ConversationTurnSummary.FilterFact> filters,
			ConversationTurnSummary.TimeRangeFact timeRange, List<String> groupBy,
			List<ConversationTurnSummary.ClarificationFact> answeredClarifications) {
	}

}
