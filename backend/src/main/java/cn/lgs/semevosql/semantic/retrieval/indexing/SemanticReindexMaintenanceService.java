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

import cn.lgs.semevosql.semantic.retrieval.SemanticRetrievalIndexService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Uses the existing indexing executor. Model calls never run inside a metadata transaction. */
@Service
public class SemanticReindexMaintenanceService {
    private static final Logger LOG=LoggerFactory.getLogger(SemanticReindexMaintenanceService.class);
    private final SemanticReindexWorkRepository work;
    private final SemanticRetrievalIndexService index;
    private final TransactionTemplate transaction;
    private volatile SemanticReindexWorkRepository.Work current;
    public SemanticReindexMaintenanceService(SemanticReindexWorkRepository work,SemanticRetrievalIndexService index,
            PlatformTransactionManager transactions) {
        this.work=work;this.index=index;this.transaction=new TransactionTemplate(transactions);
    }

    public Map<String,Object> request(String actor) { return work.request(index.reindexIdentity(),actor); }
    public Map<String,Object> status() { return work.status(); }
    public void heartbeat() { var active=current;if(active!=null)work.renew(active); }

    /** Claim compatibility for progress observers; the worker uses the explicit drain result. */
    public boolean processOne() { return processOneResult()!=ProcessingResult.NO_WORK; }

    public ProcessingResult processOneResult() {
        var claimed=work.claim();if(claimed.isEmpty())return ProcessingResult.NO_WORK;
        var ticket=claimed.get();current=ticket;
        try {
            index.stageReindex(ticket.identity());
            boolean completed=Boolean.TRUE.equals(transaction.execute(status->{
                if(!work.lockCurrent(ticket))return false;
                int count=index.publishStagedReindex(ticket.identity());
                if(!work.finish(ticket,count,null))throw new IllegalStateException("REINDEX_PUBLICATION_LEASE_EXPIRED");
                return true;
            }));
            return completed?ProcessingResult.COMPLETED:ProcessingResult.DEFERRED;
        }catch(RuntimeException failure) {
            work.finish(ticket,null,failure instanceof SemanticRetrievalIndexService.EmbeddingReindexRequiredException
                ?"ENCODING_CONFIGURATION_CHANGED":"EMBEDDING_UNAVAILABLE_RETRY_SCHEDULED");
            LOG.warn("Semantic reindex retained for retry after {}",failure.getClass().getSimpleName());
            return ProcessingResult.DEFERRED;
        }finally {current=null;}
    }

    public enum ProcessingResult {
        NO_WORK,COMPLETED,DEFERRED;
        public boolean mayContinueDocumentDrain() { return this!=DEFERRED; }
    }
}
