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
package cn.lgs.semevosql.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueryRunStreamContractTest {
    @Mock QueryRunService runs;
    @InjectMocks QueryRunController controller;

    @Test void stableEnvelopeDeliversUnlistedFutureEventsAndRespectsReplayCursor() {
        var event=RunEvent.builder().runId("r").sequence(8).eventType("FUTURE_BUSINESS_EVENT")
                .payload("exact pending understanding").build();
        when(runs.stream("r",7)).thenReturn(Flux.just(event));
        var streamed=controller.stream("r",3L,"7",true).blockFirst();
        assertNotNull(streamed);assertEquals("run-event",streamed.event());
        assertEquals("8",streamed.id());assertEquals(event,streamed.data());
    }
    @Test void existingNamedEventContractRemainsAvailable() {
        var event=RunEvent.builder().runId("r").sequence(1).eventType("QUERY_UNDERSTANDING_READY").build();
        when(runs.stream("r",0)).thenReturn(Flux.just(event));
        assertEquals("QUERY_UNDERSTANDING_READY",controller.stream("r",null,null,false).blockFirst().event());
    }
}
