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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
public class ModelConfigCacheInvalidation {

	private final AiModelRegistry aiModelRegistry;

	public void afterCommit(ModelType type) {
		if (type == null) {
			throw new IllegalArgumentException("模型类型不能为空");
		}
		Runnable invalidation = () -> invalidate(type);
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			invalidation.run();
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				invalidation.run();
			}
		});
	}

	private void invalidate(ModelType type) {
		if (ModelType.CHAT.equals(type)) {
			aiModelRegistry.refreshChat();
			return;
		}
		if (ModelType.EMBEDDING.equals(type)) {
			aiModelRegistry.refreshEmbedding();
			return;
		}
		if (ModelType.RERANK.equals(type)) {
			aiModelRegistry.refreshRerank();
			return;
		}
		throw new IllegalArgumentException("未知的模型类型: " + type);
	}

}
