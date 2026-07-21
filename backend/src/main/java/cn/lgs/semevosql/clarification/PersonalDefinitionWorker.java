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

import cn.lgs.semevosql.semantic.application.PersonalDefinitionStructurer;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** A durable independent background queue. Failed model calls leave confirmed text usable. */
@Component
public class PersonalDefinitionWorker {
    private final PersonalSemanticDefinitionStore definitions;
    private final PersonalDefinitionStructurer structurer;
    private final Executor executor;
    private final AtomicBoolean running=new AtomicBoolean();
    private PersonalDefinitionUseRecorder uses;
    @org.springframework.beans.factory.annotation.Autowired
    public void usageRecorder(PersonalDefinitionUseRecorder uses){this.uses=uses;}
    public PersonalDefinitionWorker(PersonalSemanticDefinitionStore definitions,PersonalDefinitionStructurer structurer,
            @Qualifier("semEvoSQLPersonalDefinitionExecutor") Executor executor) {
        this.definitions=definitions;this.structurer=structurer;this.executor=executor;
    }
    @Scheduled(fixedDelayString="${semevosql.personal-definition.scan-ms:10000}")
    public void scan() {
        if(!running.compareAndSet(false,true))return;
        try{executor.execute(()->{try{if(uses!=null)uses.sweep();for(int n=0;n<8&&processOne();n++){} }finally{running.set(false);}});}
        catch(java.util.concurrent.RejectedExecutionException unavailable){running.set(false);}
    }
    @org.springframework.transaction.event.TransactionalEventListener(phase=org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void wake(PersonalSemanticDefinitionStore.Confirmed confirmed){scan();}
    public boolean processOne() {
        var claim=definitions.claim(Duration.ofSeconds(120));if(claim.isEmpty())return false;
        var job=claim.get();
        try {
            var result=structurer.structure(job.definition());
            definitions.complete(job,result,job.definition().dependencyFingerprint());
        }catch(PersonalDefinitionStructurer.NeedsText unavailable){
            if("UNSUPPORTED_CAPABILITY".equals(unavailable.getMessage()))definitions.retainText(job);
            else definitions.fail(job,"STRUCTURING_"+unavailable.getMessage(),true);
        }
        catch(IllegalArgumentException invalid){definitions.fail(job,"INVALID_STRUCTURE",false);}
        catch(RuntimeException failure){definitions.fail(job,"MODEL_OR_DEPENDENCY_UNAVAILABLE",false);}
        return true;
    }
}
