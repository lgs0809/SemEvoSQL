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

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ProjectDefinitionPublicationWorker {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(ProjectDefinitionPublicationWorker.class);
    private final ProjectDefinitionPublicationRepository jobs;
    private final ProjectDefinitionPublisher publisher;
    private final Executor executor;
    private final AtomicBoolean running=new AtomicBoolean();
    private ProjectDefinitionIndexDependencyService indexDependencies;
    @org.springframework.beans.factory.annotation.Autowired
    public void setIndexDependencies(ProjectDefinitionIndexDependencyService service) { this.indexDependencies=service; }
    public ProjectDefinitionPublicationWorker(ProjectDefinitionPublicationRepository jobs,ProjectDefinitionPublisher publisher,
            @Qualifier("semEvoSQLProjectDefinitionPublicationExecutor") Executor executor) {
        this.jobs=jobs;this.publisher=publisher;this.executor=executor;
    }
    @Scheduled(fixedDelayString="${semevosql.project-definition.publication-scan-ms:10000}")
    public void scan() {
        if(!running.compareAndSet(false,true))return;
        try{executor.execute(()->{try{processOne();}finally{running.set(false);}});}
        catch(java.util.concurrent.RejectedExecutionException saturated){running.set(false);}
    }
    @org.springframework.transaction.event.TransactionalEventListener(phase=org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onIndexCompleted(cn.lgs.semevosql.semantic.retrieval.indexing.SemanticIndexWorkRepository.IndexCompleted event) { scan(); }
    public boolean processOne() {
        if(indexDependencies!=null)for(var waiting:jobs.waitingForIndex()) {
            try{indexDependencies.wake(waiting);}
            catch(RuntimeException unavailable){LOG.warn("Publication index readiness check deferred for job {} after {}",waiting.id(),unavailable.getClass().getSimpleName());}
        }
        for(long project:jobs.eligibleProjects())jobs.enqueueAutomatic(project);
        var claim=jobs.claim(Duration.ofMinutes(5));if(claim.isEmpty())return false;var work=claim.get();
        try{publisher.publish(work);}
        catch(ProjectDefinitionPublicationRepository.Stale stale){jobs.fail(work,true);}
        catch(cn.lgs.semevosql.semantic.retrieval.SemanticIndexNotReadyException notReady){jobs.failWaitingForIndex(work);}
        catch(RuntimeException unavailable){jobs.fail(work,false);}
        return true;
    }
}
