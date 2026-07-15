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
package cn.lgs.semevosql.service.aimodelconfig;

import cn.lgs.semevosql.enums.ModelType;
import cn.lgs.semevosql.dto.ModelConfigDTO;
import cn.lgs.semevosql.entity.ModelConfig;
import cn.lgs.semevosql.exception.ModelConfigNotFoundException;
import cn.lgs.semevosql.exception.ModelConnectionException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@AllArgsConstructor
public class ModelConfigOpsService {

	private final ModelConfigDataService modelConfigDataService;

	private final DynamicModelFactory modelFactory;

	private final ModelConfigCacheInvalidation cacheInvalidation;

	/**
	 * 专门处理：更新配置并热刷新的聚合逻辑
	 */
	@Transactional(rollbackFor = Exception.class)
	public void updateAndRefresh(ModelConfigDTO dto) {
		// 1. 更新数据库
		ModelConfig entity = modelConfigDataService.updateConfigInDb(dto);

		// 2. 更新配置后统一在事务提交后刷新对应模型缓存。active 配置会在更新时自动退出使用，
		// 未激活配置的刷新也是安全的，并避免任何旧配置对象继续驻留。
		cacheInvalidation.afterCommit(entity.getModelType());
	}

	/**
	 * 激活指定配置
	 */
	@Transactional(rollbackFor = Exception.class)
	public void activateConfig(Integer id) {
		ModelConfig entity = modelConfigDataService.findById(id);
		if (entity == null) {
			throw new ModelConfigNotFoundException(id);
		}
		if (!"PASSED".equals(entity.getValidationStatus())) {
			throw new cn.lgs.semevosql.exception.ModelConfigConflictException("请先验证当前模型配置可用，再设为系统当前模型");
		}

		log.info("Activating config ID={}, Type={}...", id, entity.getModelType());
		modelConfigDataService.switchActiveStatus(id, entity.getModelType());
		cacheInvalidation.afterCommit(entity.getModelType());
		log.info("Config ID={} activation persisted; cache invalidation scheduled after commit.", id);
	}

	/**
	 * 测试连接逻辑 注意：这里创建的模型是“临时”的，用完即丢，不会影响当前系统正在运行的模型
	 */
	public void testConnection(ModelConfigDTO config) {
		String modelType = config.getModelType();

		try {
			if (ModelType.CHAT.getCode().equalsIgnoreCase(modelType)) {
				testChatModel(config);
			}
			else if (ModelType.EMBEDDING.getCode().equalsIgnoreCase(modelType)) {
				testEmbeddingModel(config);
			}
			else if (ModelType.RERANK.getCode().equalsIgnoreCase(modelType)) {
				testRerankModel(config);
			}
			else {
				throw new IllegalArgumentException("未知的模型类型: " + modelType);
			}
		}
		catch (IllegalArgumentException e) {
			throw e;
		}
		catch (Exception e) {
			log.warn("Model connection test failed. modelType={}, errorType={}", config.getModelType(),
					e.getClass().getSimpleName());
			throw new ModelConnectionException(parseErrorMessage(e), e);
		}
	}

	private void testChatModel(ModelConfigDTO config) throws Exception {
		log.info("Testing Chat Model connection, provider: {}, modelName: {}", config.getProvider(),
				config.getModelName());

		// 1. 创建临时模型
		ChatModel tempModel = modelFactory.createChatModel(config);

		// 2. 发起最轻量的流式请求。部分 OpenAI-compatible 服务即使收到
		// stream=false 也固定返回 text/event-stream，使用流式接口可同时兼容这类服务
		// 和标准 OpenAI 服务。
		String promptText = "Hello";

		// 3. 调用
		int timeoutSeconds = config.getRequestTimeoutSeconds() == null ? 60
				: Math.max(1, config.getRequestTimeoutSeconds());
		ChatResponse response = tempModel.stream(new Prompt(promptText)).next().toFuture().get(timeoutSeconds,
				TimeUnit.SECONDS);

		// 4. 校验结果
		if (response == null) {
			throw new RuntimeException("模型未返回 ChatResponse");
		}
		log.info("Chat Model test passed.");
	}

	private void testEmbeddingModel(ModelConfigDTO config) {
		log.info("Testing Embedding Model connection, provider: {} modelName: {}", config.getProvider(),
				config.getModelName());
		EmbeddingModel tempModel = modelFactory.createEmbeddingProbe(config);
		float[] embedding = tempModel.embed("Test");
		if (embedding == null || embedding.length == 0) {
			throw new RuntimeException("模型生成的向量为空");
		}
		log.info("Embedding Model test passed. Dimension: {}", embedding.length);
	}

	private void testRerankModel(ModelConfigDTO config) {
		log.info("Testing Rerank Model connection, provider: {} modelName: {}", config.getProvider(),
				config.getModelName());
		var scores = modelFactory.createRerankProbe(config)
			.rerank("test query", java.util.List.of("relevant test document", "unrelated test document"), 2);
		if (scores.isEmpty()) {
			throw new RuntimeException("重排模型未返回有效分数");
		}
		log.info("Rerank Model test passed. Result count: {}", scores.size());
	}

	/**
	 * 辅助方法：提取更友好的错误信息 Spring AI 抛出的异常有时候嵌套很深
	 */
	private String parseErrorMessage(Exception error) {
		Throwable current = error;
		for (int depth = 0; current != null && depth < 10; depth++) {
			if (current instanceof WebClientResponseException responseException) {
				String providerDetail = safeProviderDetail(responseException.getResponseBodyAsString());
				int status = responseException.getStatusCode().value();
				if (status == 401) {
					return "鉴权失败 (401)，请检查 API Key 是否正确。" + providerDetail;
				}
				if (status == 404) {
					return "接口未找到 (404)，请检查 Base URL、路径、模型或厂商路由配置。" + providerDetail;
				}
				if (status == 429) {
					return "请求过多或余额不足 (429)，请检查厂商额度。" + providerDetail;
				}
				if (status >= 500) {
					return "厂商服务暂时不可用 (" + status + ")。" + providerDetail;
				}
			}
			String message = current.getMessage();
			if (StringUtils.hasText(message)) {
				if (message.contains("401")) {
					return "鉴权失败 (401)，请检查 API Key 是否正确。";
				}
				if (message.contains("404")) {
					return "接口未找到 (404)，请检查 Base URL、路径、模型或厂商路由配置。";
				}
				if (message.contains("429")) {
					return "请求过多或余额不足 (429)，请检查厂商额度。";
				}
			}
			current = current.getCause();
		}
		return "模型连接失败，请检查服务地址、凭据、模型名称和网络配置。";
	}

	private String safeProviderDetail(String responseBody) {
		if (!StringUtils.hasText(responseBody)) {
			return "";
		}
		String sanitized = responseBody.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+", "Bearer ****")
			.replaceAll("(?i)sk-[A-Za-z0-9_-]{8,}", "sk-****")
			.replaceAll("\\s+", " ")
			.trim();
		if (sanitized.length() > 300) {
			sanitized = sanitized.substring(0, 300) + "…";
		}
		return " 厂商返回: " + sanitized;
	}

}
