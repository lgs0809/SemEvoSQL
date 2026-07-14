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
package cn.lgs.semevosql.util;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import java.util.*;

/** Stable identity material across checkpoint round trips and JVMs; ordered lists retain their meaning. */
public final class CanonicalJson {
    private static final ObjectMapper MAPPER=createMapper();
    private CanonicalJson() {}
    private static ObjectMapper createMapper() {
        var module=new SimpleModule();
        module.addSerializer(new CanonicalSetSerializer());
        return JsonUtil.getObjectMapper().copy().registerModule(module);
    }
    public static String write(Object value) throws JsonProcessingException {
        return MAPPER.writeValueAsString(normalize(MAPPER.valueToTree(value)));
    }
    private static JsonNode normalize(JsonNode value) {
        if(value.isObject()) {
            ObjectNode result=JsonNodeFactory.instance.objectNode();
            var fields=new TreeMap<String,JsonNode>();value.fields().forEachRemaining(e->fields.put(e.getKey(),e.getValue()));
            fields.forEach((key,item)->result.set(key,normalize(item)));return result;
        }
        if(value.isArray()) {
            ArrayNode result=JsonNodeFactory.instance.arrayNode();value.forEach(item->result.add(normalize(item)));return result;
        }
        return value;
    }
    @SuppressWarnings("rawtypes")
    private static final class CanonicalSetSerializer extends StdSerializer<Set> {
        private CanonicalSetSerializer(){super(Set.class);}
        @Override public void serialize(Set values,JsonGenerator output,SerializerProvider provider) throws IOException {
            List<JsonNode> ordered=new ArrayList<>();
            for(Object value:values)ordered.add(normalize(MAPPER.valueToTree(value)));
            ordered.sort(Comparator.comparing(JsonNode::toString));
            output.writeStartArray();for(JsonNode value:ordered)output.writeTree(value);output.writeEndArray();
        }
    }
}
