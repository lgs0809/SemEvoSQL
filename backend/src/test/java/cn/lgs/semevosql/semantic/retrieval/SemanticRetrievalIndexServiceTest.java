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
package cn.lgs.semevosql.semantic.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;

class SemanticRetrievalIndexServiceTest {

    @Test
    void dynamicEmbeddingProxyWithoutActiveIdentityFallsBackToLexicalOnly() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        EmbeddingModelIdentityProvider identityProvider = mock(EmbeddingModelIdentityProvider.class);
        when(identityProvider.currentEmbeddingIdentity()).thenReturn(Optional.empty());
        SemanticRetrievalIndexService service = new SemanticRetrievalIndexService(jdbc, Optional.of(embeddingModel),
                Optional.of(identityProvider));

        SemanticRetrievalIndexService.IndexingResult result = service.indexDocuments(List.of(document()));

        assertThat(result.indexedDocuments()).isZero();
        assertThat(result.vectorAvailable()).isFalse();
        verifyNoInteractions(embeddingModel, jdbc);
    }

    @Test
    void lexicalDocumentsAreReadyWhenDynamicProxyHasNoActiveEmbeddingIdentity() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        EmbeddingModelIdentityProvider identityProvider = mock(EmbeddingModelIdentityProvider.class);
        when(identityProvider.currentEmbeddingIdentity()).thenReturn(Optional.empty());
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq(1L), eq(2L), eq("catalog"))).thenReturn(1);
        SemanticRetrievalIndexService service = new SemanticRetrievalIndexService(jdbc, Optional.of(embeddingModel),
                Optional.of(identityProvider));

        SemanticRetrievalIndexService.IndexReadiness readiness = service.readiness(1L, 2L, "catalog");

        assertThat(readiness.status()).isEqualTo(SemanticRetrievalIndexService.IndexReadinessStatus.INDEX_READY);
        assertThat(readiness.documentCount()).isEqualTo(1);
        assertThat(readiness.vectorCount()).isZero();
        assertThat(readiness.detail()).contains("lexical retrieval ready");
        verifyNoInteractions(embeddingModel);
    }

    private SemanticRetrievalDocument document() {
        return new SemanticRetrievalDocument("doc-1", 1L, 2L, "catalog",
                SemanticRetrievalDocument.DocumentType.MODEL, "MODEL", "model:acceptance", 1,
                "acceptance", "acceptance_table", "acceptance", "acceptance", "source", "content",
                "fallback", "1", "FALLBACK_VALIDATION");
    }
}
