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
package cn.lgs.semevosql.workflow.node;

import cn.lgs.semevosql.dto.prompt.QueryEnhanceOutputDTO;
import cn.lgs.semevosql.enums.TextType;
import cn.lgs.semevosql.service.graph.Context.ConversationContextPromptRenderer;
import cn.lgs.semevosql.service.graph.Context.ConversationContextPromptRenderer.Stage;
import cn.lgs.semevosql.service.graph.Context.ConversationContextStateView;
import cn.lgs.semevosql.util.*;
import cn.lgs.semevosql.run.RunDeadlineUtil;
import com.alibaba.cloud.ai.graph.GraphResponse;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import cn.lgs.semevosql.prompt.PromptHelper;
import cn.lgs.semevosql.service.llm.LlmService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

import java.util.Map;

import static cn.lgs.semevosql.constant.Constant.*;

/**
 * 查询丰富节点，用于根据evidence信息把业务翻译。查询改写，扩展。 此节点不需要提取关键词，如果混合检索，如es等库会自行分词并计算相关性。
 */
@Slf4j
@Component
@AllArgsConstructor
public class QueryEnhanceNode implements NodeAction {

	private final LlmService llmService;

	private final JsonParseUtil jsonParseUtil;

	private final ConversationContextPromptRenderer contextRenderer;

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {

		// 获取用户输入
		String userInput = StateUtil.getStringValue(state, INPUT_KEY);
		log.info("User input for query enhance: {}", userInput);

		String evidence = StateUtil.getStringValue(state, EVIDENCE);
		String multiTurn = ConversationContextStateView.render(state, contextRenderer, Stage.QUERY_ENHANCE);

		// 构建查询处理提示
		String prompt = PromptHelper.buildQueryEnhancePrompt(multiTurn, userInput, evidence);
		log.debug("Built query enhance prompt as follows \n {} \n", prompt);

		// 调用LLM进行查询处理
		Flux<ChatResponse> responseFlux = llmService.callUserWithin(prompt, RunDeadlineUtil.remaining(state));

		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGenerator(this.getClass(), state,
				responseFlux,
				Flux.just(ChatResponseUtil.createResponse("正在进行问题增强..."),
						ChatResponseUtil.createPureResponse(TextType.JSON.getStartSign())),
				Flux.just(ChatResponseUtil.createPureResponse(TextType.JSON.getEndSign()),
						ChatResponseUtil.createResponse("\n问题增强完成！")),
				output -> handleQueryEnhance(userInput, output));

		return Map.of(QUERY_ENHANCE_NODE_OUTPUT, generator);
	}

	private Map<String, Object> handleQueryEnhance(String userInput, String llmOutput) {
		// 获取处理结果
		String enhanceResult = MarkdownParserUtil.extractRawText(llmOutput.trim());
		log.info("Query enhance result: {}", enhanceResult);
		if (enhanceResult.isBlank()) {
			log.warn("Query enhance returned no content; retaining the original query as the canonical query");
			return Map.of(QUERY_ENHANCE_NODE_OUTPUT, fallbackResult(userInput));
		}

		// 解析处理结果，转成 QueryProcessOutputDTO
		QueryEnhanceOutputDTO queryEnhanceOutputDTO = null;
		try {
			queryEnhanceOutputDTO = jsonParseUtil.tryConvertToObject(enhanceResult, QueryEnhanceOutputDTO.class);
			log.info("Successfully parsed query enhance result: {}", queryEnhanceOutputDTO);
		}
		catch (Exception e) {
			log.error("Failed to parse query enhance result: {}", enhanceResult, e);
		}

		if (queryEnhanceOutputDTO == null || queryEnhanceOutputDTO.getCanonicalQuery() == null
				|| queryEnhanceOutputDTO.getCanonicalQuery().isBlank()
				|| queryEnhanceOutputDTO.getExpandedQueries() == null
				|| queryEnhanceOutputDTO.getExpandedQueries().isEmpty()) {
			log.warn("Query enhance output is incomplete; retaining the original query as the canonical query");
			return Map.of(QUERY_ENHANCE_NODE_OUTPUT, fallbackResult(userInput));
		}
		// 返回处理结果
		return Map.of(QUERY_ENHANCE_NODE_OUTPUT, queryEnhanceOutputDTO);
	}

	/**
	 * Query enhancement is an optional retrieval aid, not a second semantic planner. If a provider returns an empty
	 * or unusable response, retain the user's query so the already governed Semantic Blueprint can continue through
	 * schema recall and the bounded advanced SQL path. This keeps provider hiccups from turning into a missing graph
	 * state while never inventing business meaning locally.
	 */
	static QueryEnhanceOutputDTO fallbackResult(String userInput) {
		String canonical = userInput == null ? "" : userInput.trim();
		QueryEnhanceOutputDTO fallback = new QueryEnhanceOutputDTO();
		fallback.setCanonicalQuery(canonical);
		fallback.setExpandedQueries(canonical.isBlank() ? List.of() : List.of(canonical));
		return fallback;
	}

}
