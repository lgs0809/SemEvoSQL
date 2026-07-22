/*
 * Copyright 2026 the original author or authors.
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
package cn.lgs.semevosql.clarification;

import cn.lgs.semevosql.semantic.application.SemanticCatalogFingerprint;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository;
import cn.lgs.semevosql.semantic.retrieval.SemanticIndexNotReadyException;
import cn.lgs.semevosql.semantic.retrieval.SemanticRetrievalIndexService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A completed index only advances the existing approved job's due time, never publication itself. */
@Service
public class ProjectDefinitionIndexDependencyService {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ProjectDefinitionIndexDependencyService.class);
    private final ProjectDefinitionPublicationRepository jobs;
    private final SemanticCatalogRepository catalogs;
    private final SemanticRetrievalIndexService index;
    public ProjectDefinitionIndexDependencyService(ProjectDefinitionPublicationRepository jobs,
            SemanticCatalogRepository catalogs,SemanticRetrievalIndexService index) {
        this.jobs=jobs;this.catalogs=catalogs;this.index=index;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean wake(ProjectDefinitionPublicationRepository.Work work) {
        try {
            jobs.assertIndexWaitCurrent(work);
            if(!work.materializedCatalogHash().equals(SemanticCatalogFingerprint.fingerprint(
                    catalogs.loadCatalog(work.project(),work.preparedVersion()))))return false;
            index.assertReady(work.project(),work.preparedVersion(),work.materializedCatalogHash());
            return jobs.indexDependencyReady(work);
        } catch(ProjectDefinitionPublicationRepository.Stale | SemanticIndexNotReadyException notCurrentOrReady) {
            return false;
        }
    }
    public record CheckRequest(long publicationId,int contentRevision,String representationHash) {}
    public record CheckResult(String status,int totalModels,int readyModels) {}
    /** Explicit administrator action also handles legacy generic failures without reclassifying their history. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public CheckResult check(long project,long candidate,CheckRequest request,cn.lgs.semevosql.common.OperatorContext operator) {
        if(request.publicationId()<=0 || request.contentRevision()<=0 || request.representationHash()==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请刷新当前发布进度后再检查");
        var work=jobs.retryable(project,candidate,request.publicationId()).orElseThrow(
            ()->new ResponseStatusException(HttpStatus.CONFLICT,"发布状态已变化，请刷新当前进度"));
        if(work.contentRevision()!=request.contentRevision() || !work.representationHash().equals(request.representationHash()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"口径依据已变化，请刷新后重新审核");
        try{jobs.assertManualIndexRetryCurrent(work);}
        catch(ProjectDefinitionPublicationRepository.Stale changed){throw new ResponseStatusException(HttpStatus.CONFLICT,"审批或发布依据已变化，请刷新后重新审核");}
        if(!work.materializedCatalogHash().equals(SemanticCatalogFingerprint.fingerprint(catalogs.loadCatalog(project,work.preparedVersion()))))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"草稿内容已变化，请按正常流程重新审核");
        var readiness=index.readiness(project,work.preparedVersion(),work.materializedCatalogHash());
        if(readiness.status()!=SemanticRetrievalIndexService.IndexReadinessStatus.INDEX_READY)
            return new CheckResult("NOT_READY",readiness.documentCount(),readiness.vectorCount());
        boolean changed=jobs.manuallyRetryReadyIndex(work);
        if(changed)LOG.info("Administrator {} checked ready publication {} for project {}; existing approved job made due",operator.operator(),work.id(),project);
        return new CheckResult(changed?"QUEUED":"ALREADY_QUEUED",readiness.documentCount(),readiness.vectorCount());
    }
}
