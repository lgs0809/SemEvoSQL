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
package cn.lgs.semevosql.model;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import reactor.util.context.ContextView;

/** One gateway call owns all HTTP attempts, including provider-option fallback and SDK traffic. */
@Slf4j
public final class ModelTransportBudget {
    private static final ThreadLocal<ModelTransportBudget> BLOCKING=new ThreadLocal<>();
    private final String callId;
    private final ModelCallPurpose purpose;
    private final int maximum;
    private final AtomicInteger requests=new AtomicInteger();
    public ModelTransportBudget(String callId,ModelCallPurpose purpose,int maximum) {
        this.callId=callId;this.purpose=purpose;this.maximum=Math.min(ModelNetworkRetry.MAX_HTTP_ATTEMPTS,Math.max(1,maximum));
    }
    public void beforeRequest() {
        int current;
        do {current=requests.get();if(current>=maximum)throw new ExhaustedException();}
        while(!requests.compareAndSet(current,current+1));
        log.info("Model HTTP request callId={} purpose={} httpAttempt={} maxHttpAttempts={}",callId,purpose,current+1,maximum);
    }
    public int requests(){return requests.get();}
    public static ModelTransportBudget blocking(){return BLOCKING.get();}
    public static <T> T inContext(ContextView context,Callable<T> operation) throws Exception {
        ModelTransportBudget previous=BLOCKING.get();
        ModelTransportBudget current=context.getOrDefault(ModelTransportBudget.class,null);
        try {if(current==null)BLOCKING.remove();else BLOCKING.set(current);return operation.call();}
        finally {if(previous==null)BLOCKING.remove();else BLOCKING.set(previous);}
    }
    public static final class ExhaustedException extends IllegalStateException {
        public ExhaustedException(){super("Model HTTP attempt budget exhausted");}
    }
}
