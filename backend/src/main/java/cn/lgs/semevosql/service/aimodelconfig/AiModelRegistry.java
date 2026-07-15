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

import cn.lgs.semevosql.dto.ModelConfigDTO;
import cn.lgs.semevosql.enums.ModelType;
import cn.lgs.semevosql.semantic.retrieval.EmbeddingModelIdentityProvider;
import cn.lgs.semevosql.semantic.retrieval.RerankModel;
import cn.lgs.semevosql.semantic.retrieval.RerankModelProvider;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiModelRegistry implements RerankModelProvider, EmbeddingModelIdentityProvider,
        cn.lgs.semevosql.semantic.retrieval.EmbeddingIndexModelProvider {

	private final DynamicModelFactory modelFactory;

	private final ModelConfigDataService modelConfigDataService;

	// 缓存对象 (volatile 保证可见性)
	private volatile ChatClient currentChatClient;

	private volatile EmbeddingModel currentEmbeddingModel;

	private volatile RerankModel currentRerankModel;

	// =========================================================
	// 1. 获取 ChatClient (懒加载 + 缓存)
	// =========================================================
	public ChatClient getChatClient() {
		if (currentChatClient == null) {
			synchronized (this) {
				if (currentChatClient == null) {
					log.info("Initializing global ChatClient...");
					try {
						ModelConfigDTO config = modelConfigDataService.getActiveConfigByType(ModelType.CHAT);
						if (config != null) {
							ChatModel chatModel = modelFactory.createChatModel(config);
							// 核心：基于新 Model 创建新 Client，彻底消除旧参数缓存
							currentChatClient = ChatClient.builder(chatModel).build();
						}
					}
					catch (Exception e) {
						log.error("Failed to initialize ChatClient: {}", e.getMessage(), e);
					}

					// 兜底：如果还没初始化成功，抛出运行时异常，提示用户配置
					if (currentChatClient == null) {
						throw new RuntimeException(
								"No active CHAT model configured. Please configure it in the dashboard.");
					}
				}
			}
		}
		return currentChatClient;
	}

	// =========================================================
	// 2. 获取 EmbeddingModel (懒加载 + 缓存)
	// =========================================================
	public EmbeddingModel getEmbeddingModel() {
		if (currentEmbeddingModel == null) {
			synchronized (this) {
				if (currentEmbeddingModel == null) {
					log.info("Initializing global EmbeddingModel...");
					try {
						ModelConfigDTO config = modelConfigDataService.getActiveConfigByType(ModelType.EMBEDDING);
						if (config != null) {
							currentEmbeddingModel = modelFactory.createEmbeddingModel(config);
						}
					}
					catch (Exception e) {
						log.error("Failed to initialize EmbeddingModel: {}", e.getMessage());
					}

					if (currentEmbeddingModel == null) {
						throw new RuntimeException(
								"No active EMBEDDING model configured. Please configure it in the dashboard.");
					}
				}
			}
		}
		return currentEmbeddingModel;
	}

    @Override
    public EmbeddingModel currentIndexEmbeddingModel() {
        var config = modelConfigDataService.getActiveConfigByType(ModelType.EMBEDDING);
        if (config == null) throw new IllegalStateException("No active EMBEDDING model configured");
        return modelFactory.createIndexEmbeddingModel(config);
    }

	@Override
	public Optional<EmbeddingModelIdentity> currentEmbeddingIdentity() {
		ModelConfigDTO config = modelConfigDataService.getActiveConfigByType(ModelType.EMBEDDING);
		if (config == null) {
			return Optional.empty();
		}
		var attributes = cn.lgs.semevosql.semantic.retrieval.EmbeddingEncodingIdentity.attributes(
            config.getProvider(), config.getModelName(), config.getBaseUrl(), config.getEmbeddingsPath(), config.getEmbeddingDimensions());
		String model = Objects.toString(config.getProvider(), "") + ":" + Objects.toString(config.getModelName(), "");
		return Optional.of(new EmbeddingModelIdentity(model, attributes));
	}

	@Override
	public RerankModel currentRerankModel() {
		if (currentRerankModel == null) {
			synchronized (this) {
				if (currentRerankModel == null) {
					ModelConfigDTO config = modelConfigDataService.getActiveConfigByType(ModelType.RERANK);
					if (config == null) {
						throw new IllegalStateException(
								"No active RERANK model configured. Please configure it in the dashboard.");
					}
					try {
						currentRerankModel = modelFactory.createRerankModel(config);
					}
					catch (Exception e) {
						log.error("Failed to initialize RerankModel: {}", e.getMessage(), e);
						throw new IllegalStateException("Failed to initialize active RERANK model.", e);
					}
				}
			}
		}
		return currentRerankModel;
	}

	// =========================================================
	// 3. 刷新/重置缓存 (用于热切换)
	// =========================================================

	public void refreshChat() {
		this.currentChatClient = null;
		log.info("Chat cache cleared.");
	}

	public void refreshEmbedding() {
		this.currentEmbeddingModel = null;
		log.info("Embedding cache cleared.");
	}

	public void refreshRerank() {
		this.currentRerankModel = null;
		log.info("Rerank cache cleared.");
	}

}
