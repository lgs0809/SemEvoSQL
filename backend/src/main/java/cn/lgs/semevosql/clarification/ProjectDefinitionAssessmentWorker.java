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
import org.springframework.transaction.event.*;

/** Persisted leases survive restarts; background failures neither fail nor pause interactive Runs. */
@Component
public class ProjectDefinitionAssessmentWorker {
    private final ProjectDefinitionAssessmentRepository repository;
    private final ProjectDefinitionContributions contributions;
    private final ProjectDefinitionAssessor assessor;
    private final Executor executor;
    private final AtomicBoolean running=new AtomicBoolean();
    public ProjectDefinitionAssessmentWorker(ProjectDefinitionAssessmentRepository repository,
            ProjectDefinitionContributions contributions,ProjectDefinitionAssessor assessor,
            @Qualifier("semEvoSQLProjectDefinitionAssessmentExecutor") Executor executor) {
        this.repository=repository;this.contributions=contributions;this.assessor=assessor;this.executor=executor;
    }
    @Scheduled(fixedDelayString="${semevosql.project-definition.assessment-scan-ms:10000}")
    public void scan() {
        if(!running.compareAndSet(false,true))return;
        try{executor.execute(()->{try{processOne();}finally{running.set(false);}});}
        catch(java.util.concurrent.RejectedExecutionException saturated){running.set(false);}
    }
    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void wake(PersonalSemanticDefinitionStore.Confirmed confirmed){scan();}
    public boolean processOne() {
        var claimed=repository.claim(Duration.ofMinutes(3));if(claimed.isEmpty())return false;
        var work=claimed.get();var c=work.candidate();
        try {
            var totals=contributions.totals(c.id(),c.revision(),c.project());
            var result=totals.authorizedSources()==0
                ?new ProjectDefinitionAssessor.Result(null,cn.lgs.semevosql.util.JsonUtil.getObjectMapper().createObjectNode().put("relation","WITHDRAWN"),false,false,false,false,true)
                :assessor.assess(work);
            var decision=ProjectDefinitionPublicationPolicy.assess(new ProjectDefinitionPublicationPolicy.Evidence(
                totals.authorizedSources(),totals.validUsers(),totals.validUses(),result.structureReady(),result.reviewed(),
                result.publicAssetExists(),result.conflict(),result.dependencyValid()));
            repository.complete(work,totals,result.structure(),result.alignment(),decision);
        }catch(RuntimeException unavailable) {
            // Only safe codes are persisted; raw provider errors may contain request data or credentials.
            String code=unavailable instanceof IllegalStateException?unavailable.getMessage():"ASSESSMENT_UNAVAILABLE";
            repository.fail(work,code);
        }
        return true;
    }
}
