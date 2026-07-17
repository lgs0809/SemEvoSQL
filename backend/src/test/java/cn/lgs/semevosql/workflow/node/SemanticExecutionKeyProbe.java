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
package cn.lgs.semevosql.workflow.node;

import cn.lgs.semevosql.semantic.domain.*;
import cn.lgs.semevosql.util.JsonUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import java.util.*;

public final class SemanticExecutionKeyProbe {
    public static void main(String[] args) throws Exception {
        var plan=plan();
        String json=JsonUtil.getObjectMapper().writeValueAsString(plan);
        String legacy=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        System.out.println("legacy:"+legacy);
        System.out.println(SemanticExecutionNode.executionKey(new OverAllState(Map.of()),plan));
        System.out.println(SemanticExecutionNode.executionKey(new OverAllState(Map.of()),JsonUtil.getObjectMapper().readValue(json,SemanticBlueprint.class)));
        var serializer=new cn.lgs.semevosql.service.graph.checkpoint.DurableGraphStateSerializer();
        var bytes=new java.io.ByteArrayOutputStream();
        try(var output=new java.io.ObjectOutputStream(bytes)){serializer.writeData(Map.of("plan",plan),output);}
        try(var input=new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()))){
            var restored=(SemanticBlueprint)serializer.readData(input).get("plan");
            System.out.println(SemanticExecutionNode.executionKey(new OverAllState(Map.of()),restored));
        }

    }
    static SemanticBlueprint plan() {
        return SemanticBlueprint.builder().executable(true).computationIntent(new ComputationIntent(Set.of(
            ComputationIntent.Capability.FILTER,ComputationIntent.Capability.TIME_FILTER,
            ComputationIntent.Capability.AGGREGATION,ComputationIntent.Capability.LIMIT))).build();
    }
}
