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
package cn.lgs.semevosql.semantic.domain;

import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Immutable, restricted row source; logical fields never authorize undeclared physical sources. */
public record GovernedModelSource(List<Table> tables, String base, List<Join> joins,
        List<Projection> projections, List<JsonNode> filters) {
    public GovernedModelSource {
        tables=List.copyOf(tables); joins=List.copyOf(joins); projections=List.copyOf(projections);
        filters=filters.stream().map(node -> node.<JsonNode>deepCopy()).toList();
        Set<String> aliases=new HashSet<>();
        for(var table:tables) {
            identifier(table.alias());identifier(table.schema());identifier(table.table());
            if(!aliases.add(table.alias()))throw new IllegalArgumentException("Duplicate source alias");
        }
        if(!aliases.contains(base))throw new IllegalArgumentException("Source base is not declared");
        Set<String> joined=new HashSet<>();joined.add(base);
        for(var join:joins) {
            if(!Set.of("left","inner").contains(join.type()) || !aliases.contains(join.right())
                    || joined.contains(join.right()) || join.on().isEmpty())
                throw new IllegalArgumentException("Invalid source join");
            for(var pair:join.on()) {
                identifier(pair.left().column());identifier(pair.right().column());
                if(!joined.contains(pair.left().source()) || !join.right().equals(pair.right().source()))
                    throw new IllegalArgumentException("Source join must extend the declared unique side");
            }
            joined.add(join.right());
        }
        if(!joined.equals(aliases))throw new IllegalArgumentException("Unconnected source tables");
        Set<String> codes=new HashSet<>();
        for(var projection:projections) {
            identifier(projection.code());identifier(projection.mapping().column());
            if(!aliases.contains(projection.mapping().source()) || !codes.add(projection.code()))
                throw new IllegalArgumentException("Invalid logical projection");
        }
        if(codes.isEmpty())throw new IllegalArgumentException("Source projections are empty");
        for(var filter:filters) if(!codes.contains(filter.path("attribute").asText()))
            throw new IllegalArgumentException("Unknown fixed-filter attribute");
    }
    public static GovernedModelSource parse(String json) {
        try {return JsonUtil.getObjectMapper().readValue(json,GovernedModelSource.class);}
        catch(Exception invalid){throw new IllegalArgumentException("Invalid governed model source",invalid);}
    }
    public List<String> physicalTables(){return tables.stream().map(t->t.schema()+"."+t.table()).distinct().toList();}
    public static String identifier(String value) {
        if(value==null || !value.matches("[A-Za-z_][A-Za-z0-9_$]*"))throw new IllegalArgumentException("Unsafe source identifier");
        return value;
    }
    public record Table(String alias,String schema,String table) {}
    public record Mapping(String source,String column) {}
    public record Pair(Mapping left,Mapping right) {}
    public record Join(String type,String right,List<Pair> on) { public Join {on=List.copyOf(on);} }
    public record Projection(String code,Mapping mapping) {}
}
