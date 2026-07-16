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
package cn.lgs.semevosql.semantic.compiler;

import cn.lgs.semevosql.semantic.domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Both lowering paths expand the same governed source into a logical row relation. */
public final class GovernedModelSourceRenderer {
    private GovernedModelSourceRenderer() {}
    public static String relation(SemanticCatalogSnapshot.Model model,SqlDialect dialect) {
        if(model.getSourceJson()==null)return qualified(model.getPhysicalTable(),dialect);
        var source=GovernedModelSource.parse(model.getSourceJson());
        var tables=source.tables().stream().collect(Collectors.toMap(GovernedModelSource.Table::alias,Function.identity()));
        var projections=source.projections().stream().map(p->column(p.mapping(),dialect)+" AS "+dialect.quote(p.code())).toList();
        var base=tables.get(source.base());
        var sql=new StringBuilder("(SELECT ").append(String.join(", ",projections)).append(" FROM ")
            .append(table(base,dialect)).append(' ').append(dialect.quote(base.alias()));
        for(var join:source.joins()) {
            var target=tables.get(join.right());
            sql.append(' ').append(join.type().toUpperCase(Locale.ROOT)).append(" JOIN ")
                .append(table(target,dialect)).append(' ').append(dialect.quote(target.alias())).append(" ON ")
                .append(join.on().stream().map(p->column(p.left(),dialect)+" = "+column(p.right(),dialect))
                    .collect(Collectors.joining(" AND ")));
        }
        if(!source.filters().isEmpty()) {
            var mappings=source.projections().stream().collect(Collectors.toMap(GovernedModelSource.Projection::code,GovernedModelSource.Projection::mapping));
            sql.append(" WHERE ").append(predicates(source.filters(),code->column(mappings.get(code),dialect)));
        }
        return sql.append(')').toString();
    }
    public static String predicates(Collection<JsonNode> filters,Function<String,String> field) {
        return filters.stream().map(filter->{
            String left=field.apply(GovernedModelSource.identifier(filter.path("attribute").asText()));
            if(left==null)throw new IllegalArgumentException("Unknown predicate field");
            String op=filter.path("operator").asText();
            return switch(op) {
                case "is_null" -> left+" IS NULL";
                case "is_not_null" -> left+" IS NOT NULL";
                case "in","not_in" -> {
                    JsonNode values=filter.path("value");
                    if(!values.isArray() || values.isEmpty())throw new IllegalArgumentException("Empty predicate values");
                    var literals=new ArrayList<String>();values.forEach(v->literals.add(literal(v)));
                    yield left+(op.equals("in")?" IN (":" NOT IN (")+String.join(",",literals)+")";
                }
                default -> {
                    String symbol=switch(op) {
                        case "eq"->"=";case "ne"->"<>";case "gt"->">";case "gte"->">=";case "lt"->"<";case "lte"->"<=";
                        default->throw new IllegalArgumentException("Unsupported predicate operator");
                    };
                    yield left+" "+symbol+" "+literal(filter.path("value"));
                }
            };
        }).collect(Collectors.joining(" AND "));
    }
    private static String literal(JsonNode value) {
        if(value.isTextual()) {
            // NO_BACKSLASH_ESCAPES is not assumed. Reject ambiguous MySQL escape/control bytes.
            String text=value.asText();
            if(text.indexOf('\\')>=0 || text.chars().anyMatch(c->c<32))throw new IllegalArgumentException("Unsupported predicate string escape");
            return "'"+text.replace("'","''")+"'";
        }
        if(value.isBoolean())return value.asBoolean()?"TRUE":"FALSE";
        if(value.isNumber())return value.decimalValue().toPlainString();
        throw new IllegalArgumentException("Unsupported predicate literal");
    }
    private static String column(GovernedModelSource.Mapping mapping,SqlDialect dialect) {
        if(mapping==null)throw new IllegalArgumentException("Unknown source field");
        return dialect.quote(mapping.source())+"."+dialect.quote(mapping.column());
    }
    private static String table(GovernedModelSource.Table table,SqlDialect dialect){return qualified(table.schema()+"."+table.table(),dialect);}
    private static String qualified(String name,SqlDialect dialect){return Arrays.stream(name.split("\\.",-1)).map(dialect::quote).collect(Collectors.joining("."));}
}
