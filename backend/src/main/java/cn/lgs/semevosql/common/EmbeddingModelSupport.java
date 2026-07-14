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
package cn.lgs.semevosql.common;

import java.util.List;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import cn.lgs.semevosql.semantic.retrieval.AtomicEmbeddingIdentity;

/**
 * Calls the non-default EmbeddingModel API explicitly.
 *
 * <p>SemEvoSQL's primary EmbeddingModel bean is a dynamic Spring AOP proxy. Calling interface default methods such as
 * {@code embed(List<String>)} can execute the default method on the proxy rather than the current dynamic target. Calling
 * {@link EmbeddingModel#call(EmbeddingRequest)} is an ordinary interface method and therefore always resolves through the
 * current target model.
 */
public final class EmbeddingModelSupport {

	private EmbeddingModelSupport() {
	}

	public static List<float[]> embedTexts(EmbeddingModel model, List<String> texts) {
		return embedCertifiedTexts(model, texts).vectors();
    }

    public record EncodedTexts(List<float[]> vectors, AtomicEmbeddingIdentity identity) {
        public EncodedTexts { vectors = List.copyOf(vectors); }
    }

    public static EncodedTexts embedCertifiedTexts(EmbeddingModel model, List<String> texts) {
		if (model == null || texts == null || texts.isEmpty()) {
			return new EncodedTexts(List.of(), null);
		}
		EmbeddingResponse response = model.call(
				new EmbeddingRequest(List.copyOf(texts), EmbeddingOptions.builder().build()));
		if (response == null || response.getResults() == null) {
			return new EncodedTexts(List.of(), null);
		}
        Object metadata = response.getMetadata().get(AtomicEmbeddingIdentity.METADATA_KEY);
		return new EncodedTexts(response.getResults().stream().map(Embedding::getOutput).toList(),
            metadata instanceof AtomicEmbeddingIdentity identity ? identity : null);
	}

}
