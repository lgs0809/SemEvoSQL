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

import cn.lgs.semevosql.util.JsonUtil;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.serializer.StateSerializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.lang.reflect.*;
import java.math.*;
import java.time.*;
import java.util.*;

/** Versioned, closed data types only: no Java object deserialization or class loading from persisted names. */
public final class DurableGraphStateSerializer extends StateSerializer {
    public static final int SCHEMA_VERSION=1;
    private static final int MAX_BYTES=16_000_000;
    private static final Map<String,Class<?>> TYPES=new LinkedHashMap<>();
    private static final Set<String> SECRET_KEYS=Set.of("password","apikey","authorization","access_token","secret");
    static {
        for(Class<?> type:List.of(
            cn.lgs.semevosql.semantic.domain.SemanticBlueprint.class,
            cn.lgs.semevosql.semantic.domain.SemanticResultContract.class,
            cn.lgs.semevosql.semantic.domain.ScalarCalculation.class,
            cn.lgs.semevosql.semantic.domain.SemanticDefinitionBinding.class,
            cn.lgs.semevosql.semantic.domain.NumericValueRange.class,
            cn.lgs.semevosql.semantic.domain.ComputationIntent.class,
            cn.lgs.semevosql.dto.planner.Plan.class,
            cn.lgs.semevosql.dto.prompt.QueryEnhanceOutputDTO.class,
            cn.lgs.semevosql.dto.prompt.RequestQueryEnhancement.class,
            cn.lgs.semevosql.dto.planner.ExecutionStep.class,
            cn.lgs.semevosql.dto.datasource.SqlRetryDto.class,
            cn.lgs.semevosql.dto.schema.SchemaDTO.class,
            cn.lgs.semevosql.dto.schema.TableDTO.class,
            cn.lgs.semevosql.dto.schema.ColumnDTO.class,
            cn.lgs.semevosql.service.graph.Context.ConversationContextEnvelope.class,
            cn.lgs.semevosql.service.graph.Context.ConversationTurnSummary.class,
            cn.lgs.semevosql.learning.QueryCaseHints.class,
            cn.lgs.semevosql.task.QueryDecompositionService.RequestAnalysis.class,
            cn.lgs.semevosql.task.QueryDecompositionService.RequestType.class,
            cn.lgs.semevosql.task.QueryTask.class,
            cn.lgs.semevosql.task.RequestExecutionContext.class,
            cn.lgs.semevosql.review.PostExecutionReview.class,
            cn.lgs.semevosql.review.QueryRepairPolicy.RepairBudget.class,
            org.springframework.ai.document.Document.class,
            LocalDate.class,LocalDateTime.class,Instant.class,OffsetDateTime.class,UUID.class,
            Integer.class,Long.class,Double.class,Float.class,Short.class,Byte.class,BigDecimal.class,BigInteger.class)) register(type);
    }
    private static void register(Class<?> type) {
        if(TYPES.putIfAbsent(type.getName(),type)!=null)return;
        for(Class<?> child:type.getDeclaredClasses()) if(!child.getSimpleName().endsWith("Builder")) register(child);
        // Close the explicit DTO registry over enums declared by its fields.
        // New enum fields then retain their types without unrelated per-enum
        // patches. Persisted class names never trigger scanning or class loading;
        // arbitrary runtime objects and unrelated enums remain unauthorized.
        for(Field field:fields(type))registerDeclaredEnums(field.getGenericType());
    }
    private static void registerDeclaredEnums(Type declared) {
        if(declared instanceof Class<?> type){
            if(type.isEnum())register(type);
        } else if(declared instanceof ParameterizedType parameterized){
            for(Type argument:parameterized.getActualTypeArguments())registerDeclaredEnums(argument);
        } else if(declared instanceof GenericArrayType array){
            registerDeclaredEnums(array.getGenericComponentType());
        } else if(declared instanceof WildcardType wildcard){
            for(Type bound:wildcard.getUpperBounds())registerDeclaredEnums(bound);
            for(Type bound:wildcard.getLowerBounds())registerDeclaredEnums(bound);
        }
    }
    public DurableGraphStateSerializer(){super(OverAllState::new);}
    @Override public String contentType(){return "application/vnd.semevosql.graph-state.v1+json";}
    @Override public void writeData(Map<String,Object> state,ObjectOutput out) throws IOException {
        ObjectNode envelope=JsonUtil.getObjectMapper().createObjectNode();
        envelope.put("schemaVersion",SCHEMA_VERSION);
        envelope.set("state",encode(state,new IdentityHashMap<>(),0));
        byte[] bytes=JsonUtil.getObjectMapper().writeValueAsBytes(envelope);
        if(bytes.length>MAX_BYTES)throw new IOException("Graph state exceeds checkpoint size; persist large results as references");
        out.writeInt(bytes.length);out.write(bytes);
    }
    @Override @SuppressWarnings("unchecked") public Map<String,Object> readData(ObjectInput in) throws IOException {
        int size=in.readInt();if(size<1||size>MAX_BYTES)throw new IOException("Invalid Graph state size");
        byte[] bytes=new byte[size];in.readFully(bytes);
        JsonNode envelope=JsonUtil.getObjectMapper().readTree(bytes);
        if(envelope.path("schemaVersion").asInt(-1)!=SCHEMA_VERSION)throw new IOException("Unsupported Graph state schema version");
        Object state=decode(envelope.path("state"),0);
        if(!(state instanceof Map<?,?>))throw new IOException("Graph state must be a map");
        return (Map<String,Object>)state;
    }
    private JsonNode encode(Object value,IdentityHashMap<Object,Boolean> visiting,int depth) throws IOException {
        if(depth>100)throw new IOException("Graph state nesting too deep");
        if(value==null)return NullNode.instance;
        if(value instanceof String text)return TextNode.valueOf(text);
        if(value instanceof Boolean flag)return BooleanNode.valueOf(flag);
        ObjectNode node=JsonUtil.getObjectMapper().createObjectNode();
        Class<?> type=value.getClass();
        if(value instanceof Number || value instanceof java.time.temporal.TemporalAccessor || value instanceof UUID || type.isEnum()) {
            String name=type.isEnum()?((Enum<?>)value).getDeclaringClass().getName():type.getName();
            if(!TYPES.containsKey(name))throw new IOException("Unsupported Graph scalar type: "+name);
            if(value instanceof Double d&&!Double.isFinite(d)||value instanceof Float f&&!Float.isFinite(f))throw new IOException("Nonfinite Graph scalar");
            node.put("type",name);node.put("value",value instanceof Enum<?> e?e.name():value.toString());return node;
        }
        if(visiting.put(value,Boolean.TRUE)!=null)throw new IOException("Cyclic Graph state");
        try {
            if(value instanceof Map<?,?> map){
                node.put("type","map");ObjectNode fields=node.putObject("fields");
                for(var entry:map.entrySet()){
                    if(!(entry.getKey() instanceof String key)||SECRET_KEYS.contains(key.toLowerCase(Locale.ROOT)))throw new IOException("Unsupported or credential-bearing Graph key");
                    fields.set(key,encode(entry.getValue(),visiting,depth+1));
                }
            } else if(value instanceof Collection<?> items){
                node.put("type",value instanceof Set<?>?"set":"list");ArrayNode values=node.putArray("values");
                for(Object item:items)values.add(encode(item,visiting,depth+1));
            } else {
                if(!TYPES.containsKey(type.getName()))throw new IOException("Unsupported Graph state type: "+type.getName());
                node.put("type",type.getName());ObjectNode fields=node.putObject("fields");
                for(Field field:fields(type)){
                    field.setAccessible(true);fields.set(field.getName(),encode(field.get(value),visiting,depth+1));
                }
            }
            return node;
        } catch(ReflectiveOperationException error){throw new IOException("Unable to encode Graph DTO",error);}
        finally {visiting.remove(value);}
    }
    private Object decode(JsonNode node,int depth) throws IOException {
        if(depth>100)throw new IOException("Graph state nesting too deep");
        if(node.isNull())return null;if(node.isTextual())return node.asText();if(node.isBoolean())return node.asBoolean();
        String typeName=node.path("type").asText();
        if(typeName.equals("map")){
            Map<String,Object> values=new LinkedHashMap<>();
            var fields=node.path("fields").fields();while(fields.hasNext()){
                var entry=fields.next();if(SECRET_KEYS.contains(entry.getKey().toLowerCase(Locale.ROOT)))throw new IOException("Credential-bearing Graph key");
                values.put(entry.getKey(),decode(entry.getValue(),depth+1));
            }return values;
        }
        if(typeName.equals("list")||typeName.equals("set")){
            Collection<Object> values=typeName.equals("set")?new LinkedHashSet<>():new ArrayList<>();
            for(JsonNode child:node.path("values"))values.add(decode(child,depth+1));return values;
        }
        Class<?> type=TYPES.get(typeName);if(type==null)throw new IOException("Unknown Graph state type");
        try {
            if(node.has("value"))return JsonUtil.getObjectMapper().convertValue(node.get("value").asText(),type);
            Map<String,Object> values=new LinkedHashMap<>();
            var children=node.path("fields").fields();while(children.hasNext()){
                var child=children.next();values.put(child.getKey(),decode(child.getValue(),depth+1));
            }
            Set<String> expected=new LinkedHashSet<>();for(Field field:fields(type))expected.add(field.getName());
            // Supported additive DTO migrations preserve unknown provenance as null. They
            // never rebind an old approved plan to today's definition or infer a new bound.
            // Never re-read today's conversation to guess the identity of an old checkpoint.
            if(type==cn.lgs.semevosql.service.graph.Context.ConversationContextEnvelope.TurnView.class
                &&values.keySet().equals(Set.of("sequence","userQuestion","canonicalQuery","summary","relevanceScore","storedTokenEstimate"))){
                values.put("sourceRunId",null);values.put("sourceRevision",null);
            }
            Set<String> nullableAdditions=type==cn.lgs.semevosql.semantic.domain.SemanticBlueprint.class
                ? Set.of("resultContract","scalarCalculation")
                : type==cn.lgs.semevosql.semantic.domain.SemanticResultContract.class
                ? Set.of("queryMeasures")
                : type==cn.lgs.semevosql.semantic.domain.SemanticBlueprint.MetricSelection.class
                ? Set.of("definitionBinding","numericRange")
                : type==cn.lgs.semevosql.semantic.domain.SemanticBlueprint.DimensionSelection.class
                    ? Set.of("definitionBinding")
                    : type==cn.lgs.semevosql.semantic.domain.SemanticBlueprint.BindingDependency.class
                        ? Set.of("sourceRevision","sourceContentHash","dependencyFingerprint","definitionText","representationCode","representationHash") : Set.of();
            var missing=new LinkedHashSet<>(expected);missing.removeAll(values.keySet());
            if(!missing.isEmpty() && nullableAdditions.containsAll(missing) && expected.containsAll(values.keySet()))
                missing.forEach(field->values.put(field,null));
            if(!expected.equals(values.keySet()))throw new IOException("Graph DTO fields differ from state schema");
            if(type==cn.lgs.semevosql.task.RequestExecutionContext.class)return restoreRequest(values);
            if(type.isRecord()){
                RecordComponent[] components=type.getRecordComponents();Class<?>[] signature=Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
                Object[] args=Arrays.stream(components).map(c->values.get(c.getName())).toArray();
                Constructor<?> constructor=type.getDeclaredConstructor(signature);constructor.setAccessible(true);return constructor.newInstance(args);
            }
            if(type==org.springframework.ai.document.Document.class){
                @SuppressWarnings("unchecked") Map<String,Object> metadata=(Map<String,Object>)values.get("metadata");
                if(values.get("media")!=null)throw new IOException("Graph document media must be stored as a reference");
                return org.springframework.ai.document.Document.builder().id((String)values.get("id")).text((String)values.get("text")).metadata(metadata).score((Double)values.get("score")).build();
            }
            Constructor<?> constructor=type.getDeclaredConstructor();constructor.setAccessible(true);Object result=constructor.newInstance();
            for(Field field:fields(type)){field.setAccessible(true);field.set(result,values.get(field.getName()));}return result;
        } catch(ReflectiveOperationException|IllegalArgumentException error){throw new IOException("Unable to restore Graph DTO",error);}
    }
    @SuppressWarnings("unchecked") private Object restoreRequest(Map<String,Object> values) {
        var result=new cn.lgs.semevosql.task.RequestExecutionContext((String)values.get("requestId"),(String)values.get("originalQuery"),
            (List<cn.lgs.semevosql.task.QueryTask>)values.get("tasks"));
        for(var item:(List<cn.lgs.semevosql.task.RequestExecutionContext.ResolvedClarification>)values.get("clarifications"))result.acceptClarification(item.taskId(),item.question(),item.answer());
        for(var item:(List<cn.lgs.semevosql.task.RequestExecutionContext.TaskExecutionResult>)values.get("completedTasks"))result.acceptReviewedTask(item);
        if(!result.acceptedEvidence().equals(values.get("acceptedEvidence")))throw new IllegalArgumentException("Inconsistent accepted evidence");
        return result;
    }
    private static List<Field> fields(Class<?> type){
        List<Field> result=new ArrayList<>();for(Class<?> current=type;current!=null&&current!=Object.class;current=current.getSuperclass()){
            for(Field field:current.getDeclaredFields())if(!Modifier.isStatic(field.getModifiers())&&!field.isSynthetic()
                    && !(type==org.springframework.ai.document.Document.class && field.getName().equals("contentFormatter")))result.add(field);
        }return result;
    }
}
