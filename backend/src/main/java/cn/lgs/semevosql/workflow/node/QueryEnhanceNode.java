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
import cn.lgs.semevosql.dto.prompt.RequestQueryEnhancement;
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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.lgs.semevosql.exception.ModelOutputInvalidException;
import java.util.ArrayList;

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

	private final ConversationContextPromptRenderer contextRenderer;

	private static final ObjectMapper STRICT_JSON = new ObjectMapper()
		.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

	@Override
	public Map<String, Object> apply(OverAllState state) throws Exception {

		// 获取用户输入
		String userInput = StateUtil.getStringValue(state, INPUT_KEY);
		log.info("User input for query enhance: {}", userInput);

		String evidence = StateUtil.getStringValue(state, EVIDENCE, "");
		String multiTurn = ConversationContextStateView.render(state, contextRenderer, Stage.QUERY_ENHANCE);

        if (requestEntry(state)) {
            return requestEnhancement(state, userInput, multiTurn);
        }

		// 构建查询处理提示
		String prompt = PromptHelper.buildQueryEnhancePrompt(multiTurn, userInput, evidence);
		log.debug("Built query enhance prompt as follows \n {} \n", prompt);

		// 调用LLM进行查询处理
		Flux<ChatResponse> responseFlux = llmService.callUserWithin(prompt, RunDeadlineUtil.remaining(state));

		Flux<GraphResponse<StreamingOutput>> generator = FluxUtil.createStreamingGenerator(this.getClass(), state,
				responseFlux,
				Flux.just(ChatResponseUtil.createResponse("正在进行问题增强..."),
						ChatResponseUtil.createPureResponse(TextType.JSON.getStartSign())),
				Flux.just(ChatResponseUtil.createPureResponse(TextType.JSON.getEndSign())),
				output -> Map.of(QUERY_ENHANCE_NODE_OUTPUT, parseResult(output)));

		return Map.of(QUERY_ENHANCE_NODE_OUTPUT, generator);
	}

    public static boolean requestEntry(OverAllState state) {
        return !state.value(SQL_GENERATION_ONLY, false) && !state.value(APPROVED_PLAN_RECOVERY, false)
            && state.value(REQUEST_ANALYSIS).isEmpty();
    }

    private Map<String, Object> requestEnhancement(OverAllState state, String input, String context) {
        String prompt = """
            你负责补全当前用户问题，然后才会进入请求分析和语义检索。只理解本轮需求，不选择 SQL 或语义资产。
            当前消息及本次已确认的答案优先。历史只在明确承接时用于补全；独立新问题不得继承旧指标、过滤或时间。
            先解析当前消息内部的承接关系，再考虑历史或系统日期。后句的这个、该、同一、上述等指代，应绑定前句已明确的对象或范围；多个输出共享的条件须传递到各输出。
            系统日期仅用于确实指向现实当前时间的相对表达，不是默认业务查询范围。不能因出现“这个月”就覆盖同一消息先前指定的月份。
            如果无法确定相对表达指现实当前时间还是前文范围，返回 NEEDS_CONTEXT 问清楚，不能悄悄选一个。不得凭系统日期另造用户没有要求的第二个时间范围。
            保留已确认的指标含义、地区角色、时间依据。只改变用户要求改变的部分。
            不得凭常识猜业务定义。历史摘要仅作背景，不能用它恢复执行条件。
            业务定义是否缺失或存在多个口径，必须在后续读取语义目录和本人确认后判断；本节点不要追问指标公式、分摊规则、单位或业务门槛。
            当前问题明确指向某个业务指标但其定义未知时，保留该问题并返回READY，让语义规划依据真实目录问询；NEEDS_CONTEXT仅用于无法确定本轮指代或需求对象。
            如果存在多个合理的指代对象，或缺少指代所需事实，返回 NEEDS_CONTEXT，提出一个简短问题。
            不要让用户填写 JSON。options 是自然语言的完整候选问题（2到4条），没有足够依据时为空数组。
            若用户明确纠正此前已完成查询的需求（例如说错了指标、范围或时间），而非独立新查询或技术修复，返回 CONFIRM_CORRECTION。
            修改长期语义的定义、保存范围或共享授权属于语义管理请求，不等于否定以前的查询。
            对已保存口径或其历史使用的管理，目标记录在后续按当前用户和项目读取真实历史后定位。本节点的会话上下文不代表这些记录是否存在。
            用户已指明口径名称及管理动作时，保留历史选择条件并返回READY；不能仅因当前会话看不到记录就返回NEEDS_CONTEXT、要求用户重述历史查询或判断没有记录。
            计算方法不变、允许项目分享、以后仅本人使用等请求必须返回READY，保留完整管理意图交由后续语义问询。
            不得将其改写为数据查询或CONFIRM_CORRECTION；不能虚构用户否定了历史结果。仅当用户明确否定某次数据查询的要求才使用CONFIRM_CORRECTION。
            CONFIRM_CORRECTION 的 canonical_query 是修正后的完整问题，context_turns 必须且只能引用要被纠正的一轮；系统将先展示该历史问题请用户确认，再执行。
            如果无法唯一确定被纠正轮次，返回 NEEDS_CONTEXT；不能把普通“那上个月呢”当作纠正。
            READY 必须是可独立理解的完整问题；独立问题允许原样返回。expanded_queries 提供1到3种等义表达。
            context_turns 只列本次使用的上下文中明确显示的 turn sequence 整数；无引用时为空。
            仅返回一个 JSON 对象，严格包含这六个字段，无附加解释：
            {"status":"READY|NEEDS_CONTEXT|CONFIRM_CORRECTION","canonical_query":"","expanded_queries":[],"context_turns":[],"question":"","options":[]}
            READY 或 CONFIRM_CORRECTION 时 canonical_query 非空、expanded_queries 非空、question 为空、options 为空。
            NEEDS_CONTEXT 时 canonical_query 为空、expanded_queries 为空、question 非空。
            """ + "\n当前日期: " + java.time.LocalDate.now()
            + "\n本次冻结的会话上下文:\n" + context + "\n当前消息及本次确认:\n" + input;
        var responses = llmService.callUserWithin(prompt, RunDeadlineUtil.remaining(state));
        var generator = FluxUtil.createStreamingGenerator(this.getClass(), state, responses,
            Flux.just(ChatResponseUtil.createResponse("正在理解本次问题..."),
                ChatResponseUtil.createPureResponse(TextType.JSON.getStartSign())),
            Flux.just(ChatResponseUtil.createPureResponse(TextType.JSON.getEndSign())),
            output -> Map.of(REQUEST_ENHANCEMENT_OUTPUT, parseRequestResult(output, context)));
        return Map.of(REQUEST_ENHANCEMENT_OUTPUT, generator);
    }

    static RequestQueryEnhancement parseRequestResult(String output, String renderedContext) {
        try {
            String json = output == null ? "" : output.trim();
            if (json.startsWith("```json\n") && json.endsWith("\n```")) json = json.substring(8, json.length()-4).trim();
            var root = STRICT_JSON.readTree(json);
            if (root == null || !root.isObject() || root.size() != 6
                    || !root.path("status").isTextual() || !root.path("canonical_query").isTextual()
                    || !root.path("question").isTextual() || !root.path("context_turns").isArray())
                throw new IllegalArgumentException("Invalid request enhancement fields");
            String status = root.path("status").asText();
            String canonical = root.path("canonical_query").asText().trim();
            String question = root.path("question").asText().trim();
            var expanded = textArray(root.path("expanded_queries"), 3);
            var options = textArray(root.path("options"), 4);
            if (!(("READY".equals(status) || "CONFIRM_CORRECTION".equals(status)) && !canonical.isBlank() && !expanded.isEmpty() && question.isEmpty() && options.isEmpty())
                    && !("NEEDS_CONTEXT".equals(status) && canonical.isEmpty() && expanded.isEmpty()
                        && !question.isBlank() && options.size() != 1))
                throw new IllegalArgumentException("Enhancement status and body disagree");
            var allowed = new java.util.HashSet<Long>();
            var matcher = java.util.regex.Pattern.compile("<turn sequence=\"(\\d+)\">").matcher(renderedContext);
            while (matcher.find()) allowed.add(Long.parseLong(matcher.group(1)));
            var references = new java.util.LinkedHashSet<Long>();
            for (var item : root.path("context_turns")) {
                if (!item.isIntegralNumber() || !item.canConvertToLong() || !allowed.contains(item.longValue())
                        || !references.add(item.longValue())) throw new IllegalArgumentException("Unknown or repeated context source");
            }
            if ("CONFIRM_CORRECTION".equals(status) && references.size()!=1)
                throw new IllegalArgumentException("A correction must target exactly one frozen context turn");
            return new RequestQueryEnhancement(status, canonical, expanded, java.util.List.copyOf(references), question, options);
        } catch (Exception invalid) {
            throw new ModelOutputInvalidException("模型返回的问题补全结果无效，请重试。", invalid);
        }
    }

    private static java.util.List<String> textArray(com.fasterxml.jackson.databind.JsonNode node, int max) {
        if (!node.isArray() || node.size() > max) throw new IllegalArgumentException("Expected bounded text array");
        var result = new ArrayList<String>();
        for (var item : node) {
            if (!item.isTextual() || item.asText().isBlank()) throw new IllegalArgumentException("Expected nonblank text");
            result.add(item.asText().trim());
        }
        if (new java.util.HashSet<>(result).size() != result.size()) throw new IllegalArgumentException("Duplicate choices");
        return java.util.List.copyOf(result);
    }

	/** A malformed enhancement is a failed model response, never a resolved business question. */
	static QueryEnhanceOutputDTO parseResult(String output) {
		String json = output == null ? "" : output.trim();
		if (json.startsWith("```json\n") && json.endsWith("\n```")) {
			json = json.substring(8, json.length() - 4).trim();
		}
		try {
			var root = STRICT_JSON.readTree(json);
			if (root == null || !root.isObject() || root.size() != 2
					|| !root.path("canonical_query").isTextual() || root.path("canonical_query").asText().isBlank()
					|| !root.path("expanded_queries").isArray() || root.path("expanded_queries").isEmpty()) {
				throw new IllegalArgumentException("Required query enhancement fields are missing or invalid");
			}
			var expanded = new ArrayList<String>();
			for (var query : root.path("expanded_queries")) {
				if (!query.isTextual() || query.asText().isBlank()) {
					throw new IllegalArgumentException("Expanded queries must be nonblank text");
				}
				expanded.add(query.asText().trim());
			}
			var result = new QueryEnhanceOutputDTO();
			result.setCanonicalQuery(root.path("canonical_query").asText().trim());
			result.setExpandedQueries(expanded);
			return result;
		}
		catch (Exception invalid) {
			// No second model/parser-repair loop outside this Run's bounded invocation.
			throw new ModelOutputInvalidException("模型返回的问题补全结果无效，请重试。", invalid);
		}
	}

}
