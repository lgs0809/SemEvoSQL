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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import cn.lgs.semevosql.semantic.domain.*;
import cn.lgs.semevosql.semantic.application.SemanticCatalogFingerprint;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.TransactionStatus;

class DeterministicRetrievalBuildTest {
    private TransactionTemplate transactions() {
        var tx=mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(call -> ((TransactionCallback<?>)call.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        return tx;
    }
    @Test void preparingDurableWorkNeverInvokesAModelOnTheRequestThread() {
        var catalogs=mock(SemanticCatalogRepository.class);
        var documents=mock(SemanticRetrievalDocumentRepository.class);
        var index=mock(SemanticRetrievalIndexService.class);
        var snapshot=SemanticCatalogSnapshot.builder().projectId(1L).projectVersionId(2L)
            .models(List.of(SemanticCatalogSnapshot.Model.builder().modelCode("orders").physicalTable("orders")
                .datasourceId(1).status(SemanticAssetStatus.ENABLED).build())).build();
        when(catalogs.loadCatalog(1L,2L)).thenReturn(snapshot);
        var prepared=new SemanticRetrievalDocumentBuildService(catalogs,documents,index,transactions())
            .prepare(1L,2L,SemanticCatalogFingerprint.fingerprint(snapshot));
        assertEquals(1,prepared.size());verify(documents).replaceCatalog(eq(1L),eq(2L),anyString(),eq(prepared));
        verifyNoInteractions(index);
    }

    @Test void rebuildUsesSameVersionedDescriptionAndCorrectionChangesExactlyTheIndexedText() {
        var catalogs=mock(SemanticCatalogRepository.class);
        var documents=mock(SemanticRetrievalDocumentRepository.class);
        var index=mock(SemanticRetrievalIndexService.class);
        Map<String,SemanticRetrievalDocument> saved=new LinkedHashMap<>();
        doAnswer(call->{List<SemanticRetrievalDocument> batch=call.getArgument(3);saved.clear();
            batch.forEach(doc -> saved.put(doc.id(),doc));return null;})
            .when(documents).replaceCatalog(any(),any(),any(),anyList());
        when(documents.findCatalog(eq(1L),eq(2L),anyString())).thenAnswer(call->new ArrayList<>(saved.values()));
        when(index.indexDocuments(anyList())).thenAnswer(call->{
            List<SemanticRetrievalDocument> batch=call.getArgument(0);
            assertEquals(saved.values().stream().toList(),batch);
            return new SemanticRetrievalIndexService.IndexingResult(batch.size(),true);
        });
        var model=SemanticCatalogSnapshot.Model.builder().datasourceId(1).modelCode("orders").physicalTable("orders")
            .businessName("订单").description("每笔订单一行，金额包含已支付和未支付，不能直接当成收入").status(SemanticAssetStatus.ENABLED).build();
        var metric=SemanticCatalogSnapshot.Metric.builder().modelCode("orders").metricCode("paid_amount")
            .businessName("实付金额").description("已支付订单金额，不扣退款").aggregation("SUM").unit("元").status(SemanticAssetStatus.ENABLED).build();
        var snapshot=SemanticCatalogSnapshot.builder().projectId(1L).projectVersionId(2L).models(List.of(model)).metrics(List.of(metric)).build();
        when(catalogs.loadCatalog(1L,2L)).thenReturn(snapshot);
        var builder=new SemanticRetrievalDocumentBuildService(catalogs,documents,index,transactions());
        String oldHash=SemanticCatalogFingerprint.fingerprint(snapshot);
        var result=builder.build(1L,2L,oldHash);
        assertEquals(1,result.documents());assertEquals(0,result.enrichedDocuments());assertEquals(0,result.fallbackDocuments());
        var first=new LinkedHashMap<>(saved);
        builder.build(1L,2L,oldHash);assertEquals(first,saved);
        assertTrue(saved.values().stream().allMatch(d->d.generatorModel().equals("OFFLINE_CATALOG")));
        assertTrue(saved.values().stream().anyMatch(d->d.semanticText().contains("不扣退款")));
        metric.setDescription("已支付订单金额，按本测试修订扣除成功退款");
        assertThrows(IllegalStateException.class,()->builder.build(1L,2L,oldHash));
        String correctedHash=SemanticCatalogFingerprint.fingerprint(snapshot);
        builder.build(1L,2L,correctedHash);
        var corrected=saved.values().stream().filter(d->d.assetKey().equals("model:orders")).findFirst().orElseThrow();
        var original=first.get(corrected.id());
        assertNotEquals(original.contentHash(),corrected.contentHash());assertNotEquals(original.sourceFingerprint(),corrected.sourceFingerprint());
        assertEquals(correctedHash,corrected.catalogHash());assertFalse(corrected.semanticText().contains("不扣退款"));
        verify(index,times(3)).indexDocuments(anyList());
    }
}
