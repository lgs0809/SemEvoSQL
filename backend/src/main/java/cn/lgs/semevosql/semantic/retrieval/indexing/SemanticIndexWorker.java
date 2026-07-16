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
package cn.lgs.semevosql.semantic.retrieval.indexing;

import cn.lgs.semevosql.semantic.retrieval.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import java.util.concurrent.Executor;

/** Durable scan plus after-commit wakeup. No inference runs on a request or scheduler thread. */
@Component
public class SemanticIndexWorker {
    private static final Logger LOG=LoggerFactory.getLogger(SemanticIndexWorker.class);
    private final SemanticIndexWorkRepository workRepository;
    private final SemanticRetrievalDocumentRepository documents;
    private final SemanticRetrievalIndexService index;
    private final Executor executor;
    private final AtomicBoolean running=new AtomicBoolean();
    private volatile SemanticIndexWorkRepository.Work current;
    private SemanticReindexMaintenanceService maintenance;

    @org.springframework.beans.factory.annotation.Autowired
    public void setMaintenance(SemanticReindexMaintenanceService maintenance) { this.maintenance=maintenance; }

    public SemanticIndexWorker(SemanticIndexWorkRepository workRepository,SemanticRetrievalDocumentRepository documents,
            SemanticRetrievalIndexService index,@Qualifier("semEvoSQLIndexExecutor") Executor executor) {
        this.workRepository=workRepository;this.documents=documents;this.index=index;this.executor=executor;
    }

    @TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)
    public void onChanged(DocumentsChanged event) { wake(); }

    @Scheduled(fixedDelayString="${semevosql.retrieval.index-scan-ms:10000}")
    public void scan() { wake(); }

    @Scheduled(fixedDelayString="${semevosql.retrieval.index-heartbeat-ms:15000}")
    public void heartbeat() {
        if(maintenance!=null)maintenance.heartbeat();
        var work=current;
        if(work!=null) workRepository.renew(work);
    }

    public void wake() {
        if(!running.compareAndSet(false,true)) return;
        try {
            executor.execute(()->{
                try {
                    if(maintenance!=null && !maintenance.processOneResult().mayContinueDocumentDrain())return;
                    for(int n=0;n<16 && processOne();n++) { /* bounded drain; scan handles remaining work */ }
                }
                catch(RuntimeException failure) { LOG.warn("Semantic index worker deferred after {}",failure.getClass().getSimpleName()); }
                finally { current=null;running.set(false); }
            });
        } catch(RuntimeException rejected) { running.set(false);LOG.warn("Semantic index wakeup deferred; durable work retained"); }
    }

    /** Whether the current drain may continue; a deferred encoding leaves other work for a later scan. */
    public boolean processOne() {
        var claimed=workRepository.claim();
        if(claimed.isEmpty()) return false;
        var work=claimed.get();current=work;
        boolean success=false;
        boolean continueDrain=true;
        try {
            var document=documents.findById(work.documentId());
            if(document.isPresent() && Objects.equals(document.get().contentHash(),work.contentHash())
                    && Objects.equals(document.get().sourceFingerprint(),work.sourceFingerprint())) {
                var outcome=index.indexDocuments(List.of(document.get()));
                success=outcome.vectorAvailable();
                continueDrain=success;
            }
        } catch(RuntimeException failure) {
            continueDrain=false;
            LOG.warn("Semantic document encoding deferred after {}",failure.getClass().getSimpleName());
        } finally {
            workRepository.finish(work,success);current=null;
        }
        // The remote process may still be unwinding a deadline. Do not immediately consume
        // every other document's retry budget while the same dependency is unavailable.
        return continueDrain;
    }

    public record DocumentsChanged(Long projectId,Long versionId) {}
}
