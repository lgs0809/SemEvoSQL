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

import cn.lgs.semevosql.common.EmbeddingModelSupport;
import cn.lgs.semevosql.semantic.retrieval.EmbeddingIndexModelProvider;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.*;

/** Separate executor, durable leases and source CAS; inference never extends a confirmation transaction. */
@Component
public class ProjectDefinitionIndexWorker {
    private final ProjectDefinitionCandidateRepository documents;
    private final PersonalDefinitionRetrievalService retrieval;
    private final EmbeddingIndexModelProvider models;
    private final Executor executor;
    private final AtomicBoolean running=new AtomicBoolean();
    public ProjectDefinitionIndexWorker(ProjectDefinitionCandidateRepository documents,PersonalDefinitionRetrievalService retrieval,
            Optional<EmbeddingIndexModelProvider> models,@Qualifier("semEvoSQLProjectDefinitionIndexExecutor") Executor executor) {
        this.documents=documents;this.retrieval=retrieval;this.models=models.orElse(null);this.executor=executor;
    }
    @Scheduled(fixedDelayString="${semevosql.project-definition.index-scan-ms:10000}")
    public void scan() {
        if(!running.compareAndSet(false,true))return;
        try{executor.execute(()->{try{for(int n=0;n<4&&processOne();n++){} }finally{running.set(false);}});}
        catch(java.util.concurrent.RejectedExecutionException rejected){running.set(false);}
    }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void wake(PersonalSemanticDefinitionStore.Confirmed confirmed){scan();}
    public boolean processOne() {
        var identity=retrieval.identity();if(models==null||identity==null)return false;
        var claim=documents.claim(identity,Duration.ofMinutes(5));if(claim.isEmpty())return false;var work=claim.get();
        try {
            var vectors=EmbeddingModelSupport.embedTexts(models.currentIndexEmbeddingModel(),List.of(work.candidate().indexText()));
            if(vectors.size()!=1||!Objects.equals(identity,retrieval.identity()))throw new IllegalArgumentException("Encoding identity changed or no vector returned");
            documents.complete(work,identity,vectors.get(0));
        }catch(RuntimeException unavailable){documents.fail(work);}
        return true;
    }
}
