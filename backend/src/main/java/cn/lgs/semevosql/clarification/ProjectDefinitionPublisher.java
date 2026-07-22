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

import cn.lgs.semevosql.common.OperatorContext;
import cn.lgs.semevosql.evolution.domain.*;
import cn.lgs.semevosql.project.application.ProjectInitializationApplicationService;
import cn.lgs.semevosql.project.domain.*;
import cn.lgs.semevosql.semantic.application.*;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Reuses ordinary draft validation/publication; only final activation and evidence receipts share a short transaction. */
@Service
public class ProjectDefinitionPublisher {
    private final ProjectDefinitionPublicationRepository jobs;
    private final SemanticProjectRepository projects;
    private final SemanticCatalogRepository catalogs;
    private final SemanticCatalogApplicationService drafts;
    private final ProjectInitializationApplicationService initialization;
    private final TransactionTemplate transactions;
    public ProjectDefinitionPublisher(ProjectDefinitionPublicationRepository jobs,SemanticProjectRepository projects,
            SemanticCatalogRepository catalogs,SemanticCatalogApplicationService drafts,
            ProjectInitializationApplicationService initialization,TransactionTemplate transactions) {
        this.jobs=jobs;this.projects=projects;this.catalogs=catalogs;this.drafts=drafts;this.initialization=initialization;this.transactions=transactions;
    }
    public void publish(ProjectDefinitionPublicationRepository.Work original) {
        var operator=new OperatorContext(original.operator(),original.operatorSource(),"definition-publication-"+original.id(),"definition-publication-"+original.id());
        if("ASSOCIATE".equals(original.action())) {
            transactions.executeWithoutResult(ignored->{projects.lockProject(original.project());jobs.assertCurrent(original);jobs.complete(original,original.baseVersion());});
            return;
        }
        transactions.executeWithoutResult(ignored->{
            projects.lockProject(original.project());jobs.assertCurrent(original);
            if(original.preparedVersion()==null) {
                var latest=projects.findVersions(original.project()).stream().map(v->SemanticVersionNumber.parse(v.getVersionNumber()))
                    .max(SemanticVersionNumber::compareTo).orElseThrow().next(SemanticVersionLevel.MINOR);
                var view=initialization.createDraftVersion(original.project(),latest.toString(),ProjectVersionCreationMode.CLONE,
                    original.baseVersion(),"PROJECT_DEFINITION:"+original.candidate()+":"+original.contentRevision(),operator);
                var version=view.version();version.setVersionLevel(SemanticVersionLevel.MINOR);version.setVersionCause(SemanticVersionCause.SHARED_DEFINITION_PROMOTION);
                projects.updateVersion(version);
                jobs.prepared(original,version.getId(),SemanticCatalogFingerprint.fingerprint(catalogs.loadCatalog(original.project(),version.getId())));
            }
        });
        var work=jobs.refresh(original);long versionId=work.preparedVersion();
        var version=projects.findVersion(versionId).orElseThrow();
        if(version.getStatus()==ProjectVersionStatus.DRAFT) {
            if(version.getAnalysisStatus()==InitializationAnalysisStatus.PENDING || version.getAnalysisStatus()==InitializationAnalysisStatus.FAILED)
                initialization.startAnalysis(work.project(),versionId,operator);
            transactions.executeWithoutResult(ignored->{
                projects.lockProject(work.project());jobs.assertCurrent(work);
                String current=SemanticCatalogFingerprint.fingerprint(catalogs.loadCatalog(work.project(),versionId));
                if(work.materializedCatalogHash()==null) {
                    if(!current.equals(work.initialCatalogHash()))throw new ProjectDefinitionPublicationRepository.Stale();
                    var base=catalogs.loadCatalog(work.project(),work.baseVersion());
                    if(!work.catalogHash().equals(SemanticCatalogFingerprint.fingerprint(base)))throw new ProjectDefinitionPublicationRepository.Stale();
                    var materialized=ProjectDefinitionCatalogMaterializer.materialize(work,base);
                    drafts.replaceDraftCatalog(work.project(),versionId,materialized);
                    jobs.materialized(work,SemanticCatalogFingerprint.fingerprint(catalogs.loadCatalog(work.project(),versionId)));
                } else if(!current.equals(work.materializedCatalogHash()))throw new ProjectDefinitionPublicationRepository.Stale();
            });
            version=projects.findVersion(versionId).orElseThrow();
            if(version.getAnalysisStatus()==InitializationAnalysisStatus.RUNNING)
                initialization.completeAnalysis(work.project(),versionId,operator);
            initialization.validateVersion(work.project(),versionId,operator);
        }
        var ready=jobs.refresh(work);
        if(!Objects.equals(ready.materializedCatalogHash(),SemanticCatalogFingerprint.fingerprint(catalogs.loadCatalog(work.project(),versionId))))
            throw new ProjectDefinitionPublicationRepository.Stale();
        // Release/schema checks happen before entering the final transaction and recheck source revision on commit.
        var prepared=initialization.preparePublication(work.project(),versionId,operator);
        transactions.executeWithoutResult(ignored->{
            projects.lockProject(work.project());jobs.assertCurrent(ready);
            if(!prepared.report().catalogHash().equals(ready.materializedCatalogHash()))throw new ProjectDefinitionPublicationRepository.Stale();
            initialization.commitPublication(prepared,operator);
            initialization.activateVersion(work.project(),versionId,operator);
            jobs.complete(ready,versionId);
        });
    }
}
