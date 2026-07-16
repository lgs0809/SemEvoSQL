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

import cn.lgs.semevosql.semantic.application.SemanticCatalogFingerprint;
import cn.lgs.semevosql.semantic.domain.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Builds one complete governed document per model. It never truncates or invents business definitions. */
@Service
public class SemanticRetrievalDocumentBuildService {
    private final SemanticCatalogRepository catalogRepository;
    private final SemanticRetrievalDocumentRepository documentRepository;
    private final SemanticRetrievalIndexService indexService;
    private final TransactionTemplate transactions;
    private final SemanticModelDocumentAssembler assembler = new SemanticModelDocumentAssembler();

    public SemanticRetrievalDocumentBuildService(SemanticCatalogRepository catalogRepository,
            SemanticRetrievalDocumentRepository documentRepository, SemanticRetrievalIndexService indexService,
            TransactionTemplate transactions) {
        this.catalogRepository = catalogRepository;
        this.documentRepository = documentRepository;
        this.indexService = indexService;
        this.transactions = transactions;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public BuildResult build(Long projectId, Long projectVersionId, String catalogHash) {
        var documents = prepare(projectId, projectVersionId, catalogHash);
        var indexing = indexService.indexDocuments(documents);
        indexService.assertReady(projectId, projectVersionId, catalogHash);
        return new BuildResult(documents.size(), 0, 0, indexing.indexedDocuments(), indexing.vectorAvailable());
    }

    /** Commit complete text with durable work; the caller never needs to wait for a model. */
    @Transactional
    public List<SemanticRetrievalDocument> prepare(Long projectId, Long projectVersionId, String catalogHash) {
        return transactions.execute(status -> {
            // Catalog writers take this same row lock. The source cannot change between
            // checking its hash and committing a complete text plus durable index work.
            catalogRepository.lockVersion(projectId,projectVersionId);
            var snapshot = catalogRepository.loadCatalog(projectId, projectVersionId);
            assertCatalogHash(snapshot, catalogHash);
            var documents = snapshot.getModels().stream().filter(SemanticCatalogSnapshot.Model::isEnabled)
                .sorted(Comparator.comparing(SemanticCatalogSnapshot.Model::getModelCode))
                .map(model -> assembler.assemble(snapshot, model, catalogHash)).toList();
            documentRepository.replaceCatalog(projectId, projectVersionId, catalogHash, documents);
            return documents;
        });
    }

    public void assertReady(Long projectId, Long projectVersionId, String catalogHash) {
        documentRepository.assertCatalogVersion(projectId, projectVersionId, catalogHash);
        indexService.assertConfiguredIndexCompatible();
        indexService.assertReady(projectId, projectVersionId, catalogHash);
    }

    public int reindexEmbeddings() { return indexService.reindexAll(); }

    public SemanticRetrievalIndexService.IndexingResult reindexEmbeddings(Long projectId, Long projectVersionId) {
        var documents = documentRepository.findVersion(projectId, projectVersionId);
        if (documents.isEmpty()) return new SemanticRetrievalIndexService.IndexingResult(0, false);
        if (documents.stream().map(SemanticRetrievalDocument::catalogHash).distinct().count() != 1)
            throw new IllegalStateException("Semantic retrieval documents contain multiple catalog hashes");
        return indexService.indexDocuments(documents);
    }

    private void assertCatalogHash(SemanticCatalogSnapshot snapshot, String expected) {
        if (!Objects.equals(expected, SemanticCatalogFingerprint.fingerprint(snapshot)))
            throw new IllegalStateException("Semantic Catalog changed while retrieval artifacts were being built");
    }

    public record BuildResult(int documents, int enrichedDocuments, int fallbackDocuments, int indexedEmbeddings,
            boolean vectorAvailable) {}
}
