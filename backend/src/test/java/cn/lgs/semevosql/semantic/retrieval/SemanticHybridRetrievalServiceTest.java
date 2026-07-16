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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SemanticHybridRetrievalServiceTest {

	private SemanticRetrievalDocumentRepository documentRepository;

	private SemanticRetrievalIndexService indexService;

	private RerankModelProvider rerankModelProvider;

	private SemanticHybridRetrievalService service;

	@BeforeEach
	void setUp() {
		documentRepository = mock(SemanticRetrievalDocumentRepository.class);
		indexService = mock(SemanticRetrievalIndexService.class);
		rerankModelProvider = mock(RerankModelProvider.class);
		service = new SemanticHybridRetrievalService(documentRepository, indexService, rerankModelProvider);
		when(indexService.vectorScores(anyLong(), anyLong(), anyString(), anyString(), any(), anyInt()))
			.thenReturn(Map.of());
		when(documentRepository.lexicalScores(anyLong(),anyLong(),anyString(),anyString(),any(),anyInt()))
            .thenReturn(Map.of("metric:a",2d,"metric:b",1d));
        when(documentRepository.findByIds(anyLong(),anyLong(),anyString(),any(),any())).thenReturn(List.of(
				document("metric:a", "metric amount", "metric amount"),
				document("metric:b", "amount alternative", "the exact business payment amount requested")));
	}

	@Test
	void rerankerCanReorderRrfCandidatesAndKeepsEvidence() {
		when(rerankModelProvider.currentRerankModel()).thenReturn((query, documents, topN) -> List.of(
				new RerankModel.RerankScore(1, 0.99d), new RerankModel.RerankScore(0, 0.25d)));

		List<SemanticHybridRetrievalService.RetrievalHit> hits = service.retrieve(1L, 2L, "catalog", "metric amount", 2);

		assertThat(hits).extracting(SemanticHybridRetrievalService.RetrievalHit::assetKey)
			.containsExactly("metric:b", "metric:a");
		assertThat(hits.get(0).channelRanks()).containsKeys("RRF", "RERANK");
		assertThat(hits.get(0).channelScores()).containsEntry("RERANK", 0.99d).containsKey("RRF");
	}

	@Test
	void rerankerUsesConfiguredModelWindowAndKeepsRrfTail() {
		org.springframework.test.util.ReflectionTestUtils.setField(service, "modelRerankTopK", 4);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "modelRetainTopK", 12);
		List<SemanticRetrievalDocument> documents = IntStream.range(0, 12)
			.mapToObj(index -> document("metric:" + index, "metric amount " + index, "metric amount document " + index))
			.toList();
		when(documentRepository.findByIds(anyLong(),anyLong(),anyString(),any(),any())).thenReturn(documents);
        var lexical=new java.util.LinkedHashMap<String,Double>();
        for(int n=0;n<documents.size();n++) lexical.put(documents.get(n).id(),12d-n);
        when(documentRepository.lexicalScores(anyLong(),anyLong(),anyString(),anyString(),any(),anyInt())).thenReturn(lexical);
		AtomicInteger rerankedDocumentCount = new AtomicInteger();
		when(rerankModelProvider.currentRerankModel()).thenReturn((query, rerankDocuments, topN) -> {
			rerankedDocumentCount.set(rerankDocuments.size());
			return IntStream.range(0, rerankDocuments.size())
				.mapToObj(index -> new RerankModel.RerankScore(index, 1d - index * 0.01d))
				.toList();
		});

		List<SemanticHybridRetrievalService.RetrievalHit> hits = service.retrieve(1L, 2L, "catalog", "metric amount", 12);

		assertThat(rerankedDocumentCount).hasValue(4);
		assertThat(hits).hasSize(12);
		assertThat(hits.subList(0, 4)).allSatisfy(hit -> assertThat(hit.channelRanks()).containsKey("RERANK"));
		assertThat(hits.subList(4, 12)).allSatisfy(hit -> assertThat(hit.channelRanks()).doesNotContainKey("RERANK"));
	}

	@Test
	void missingRerankerFallsBackToGovernedRrfCandidates() {
		when(rerankModelProvider.currentRerankModel())
			.thenThrow(new IllegalStateException("No active RERANK model configured."));

		List<SemanticHybridRetrievalService.RetrievalHit> hits = service.retrieve(1L, 2L, "catalog", "metric amount", 1);

		assertThat(hits).singleElement().satisfies(hit -> {
			assertThat(hit.assetKey()).isEqualTo("metric:a");
			assertThat(hit.channelRanks()).containsKey("RRF").doesNotContainKey("RERANK");
		});
	}

	@Test
	void rerankerFailureFallsBackWithoutFailingRetrieval() {
		when(rerankModelProvider.currentRerankModel()).thenReturn((query, documents, topN) -> {
			throw new IllegalStateException("rerank unavailable");
		});

		List<SemanticHybridRetrievalService.RetrievalHit> hits = service.retrieve(1L, 2L, "catalog", "metric amount", 2);

		assertThat(hits).extracting(SemanticHybridRetrievalService.RetrievalHit::assetKey)
			.containsExactly("metric:a", "metric:b");
		assertThat(hits).allSatisfy(hit -> assertThat(hit.channelRanks()).doesNotContainKey("RERANK"));
	}

    @Test void queryLoadsOnlyBoundedHitsAndNoLegacyThirdRankingChannel() {
        when(rerankModelProvider.currentRerankModel()).thenThrow(new IllegalStateException("disabled"));
        var hits=service.retrieve(1L,2L,"catalog","metric amount",2);
        assertThat(hits).allSatisfy(hit -> assertThat(hit.channelRanks()).containsKeys("FTS","RRF")
            .doesNotContainKeys("EXACT","BM25"));
        verify(documentRepository,never()).findCatalog(any(),any(),any());
        verify(documentRepository).findByIds(1L,2L,"catalog",SemanticRetrievalScope.all(),java.util.Set.of("metric:a","metric:b"));
    }

    @Test void noTermsDoesNotSearchOrLoadTheCatalog() {
        assertThat(service.retrieve(1L,2L,"catalog"," ? ! _ ",4)).isEmpty();
        verify(documentRepository,never()).lexicalScores(any(),any(),any(),any(),any(),anyInt());
        verify(documentRepository,never()).findByIds(any(),any(),any(),any(),any());
        verify(indexService,never()).vectorScores(any(),any(),any(),any(),any(),anyInt());
    }

    @Test void ftsFailureCanUseAuthorizedVectorHits() {
        when(documentRepository.lexicalScores(any(),any(),any(),any(),any(),anyInt()))
            .thenThrow(new IllegalStateException("fts down"));
        when(indexService.vectorScores(any(),any(),any(),any(),any(),anyInt())).thenReturn(Map.of("metric:a",0.9));
        when(rerankModelProvider.currentRerankModel()).thenThrow(new IllegalStateException("disabled"));
        assertThat(service.retrieve(1L,2L,"catalog","metric amount",4)).singleElement()
            .satisfies(hit -> assertThat(hit.channelRanks()).containsKey("VECTOR").doesNotContainKey("FTS"));
    }

    @Test void failedCatalogReadWithNoAlternativeIsNotAnEmptyNamespace() {
        when(documentRepository.lexicalScores(any(),any(),any(),any(),any(),anyInt()))
            .thenThrow(new IllegalStateException("fts down"));
        assertThrows(IllegalStateException.class,()->service.retrieve(1L,2L,"catalog","metric amount",4));
    }

    @Test void vectorFailureStillReturnsFtsCandidates() {
        when(indexService.vectorScores(any(),any(),any(),any(),any(),anyInt())).thenThrow(new IllegalStateException("vector down"));
        when(rerankModelProvider.currentRerankModel()).thenThrow(new IllegalStateException("disabled"));
        assertThat(service.retrieve(1L,2L,"catalog","metric amount",4)).hasSize(2)
            .allSatisfy(hit -> assertThat(hit.channelRanks()).containsKey("FTS").doesNotContainKey("VECTOR"));
    }

	private SemanticRetrievalDocument document(String assetKey, String lexical, String semantic) {
		return new SemanticRetrievalDocument(assetKey, 1L, 2L, "catalog", SemanticRetrievalDocument.DocumentType.METRIC,
				"METRIC", assetKey, 1, "orders", "orders", lexical, semantic, "source", "content", "test", "1",
				"ENRICHED");
	}

}
