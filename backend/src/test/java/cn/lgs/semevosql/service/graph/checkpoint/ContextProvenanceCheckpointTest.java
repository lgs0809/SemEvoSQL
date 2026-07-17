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
package cn.lgs.semevosql.service.graph.checkpoint;

import static org.assertj.core.api.Assertions.*;
import cn.lgs.semevosql.service.graph.Context.ConversationContextEnvelope.TurnView;
import cn.lgs.semevosql.service.graph.Context.ConversationTurnSummary;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextProvenanceCheckpointTest {
    private final DurableGraphStateSerializer serializer=new DurableGraphStateSerializer();
    private final TurnView turn=new TurnView(3L,"那二月呢","二月金额",ConversationTurnSummary.fallback("二月金额",""),1D,20,"source-run",7L);

    @Test void frozenProvenanceSurvivesCheckpointRoundTrip() throws Exception {
        var restored=(TurnView)serializer.dataFromBytes(serializer.dataToBytes(Map.of("turn",turn))).get("turn");
        assertThat(restored).isEqualTo(turn);
    }

    byte[] legacy(boolean malformed) throws Exception {
        byte[] bytes=serializer.dataToBytes(Map.of("turn",turn));
        ObjectNode root;
        try(var input=new ObjectInputStream(new ByteArrayInputStream(bytes))){
            root=(ObjectNode)JsonUtil.getObjectMapper().readTree(input.readNBytes(input.readInt()));
        }
        var fields=(ObjectNode)root.path("state").path("fields").path("turn").path("fields");
        fields.remove("sourceRunId");fields.remove("sourceRevision");
        if(malformed)fields.remove("canonicalQuery");
        var output=new ByteArrayOutputStream();
        try(var stream=new ObjectOutputStream(output)){
            var json=JsonUtil.getObjectMapper().writeValueAsBytes(root);stream.writeInt(json.length);stream.write(json);
        }
        return output.toByteArray();
    }

    @Test void oldCheckpointRestoresWithUnknownIdentityWithoutInventingSource() throws Exception {
        var restored=(TurnView)serializer.dataFromBytes(legacy(false)).get("turn");
        assertThat(restored.sequence()).isEqualTo(3L);
        assertThat(restored.canonicalQuery()).isEqualTo("二月金额");
        assertThat(restored.sourceRunId()).isNull();assertThat(restored.sourceRevision()).isNull();
    }

    @Test void additiveMigrationDoesNotAcceptUnrelatedMissingFields() {
        assertThatThrownBy(()->serializer.dataFromBytes(legacy(true))).isInstanceOf(IOException.class);
    }
}
