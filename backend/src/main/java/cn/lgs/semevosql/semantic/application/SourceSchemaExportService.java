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
package cn.lgs.semevosql.semantic.application;

import cn.lgs.semevosql.connector.pool.DBConnectionPoolFactory;
import cn.lgs.semevosql.project.domain.*;
import cn.lgs.semevosql.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** JDBC metadata only: no sampling, provider call, credential export or business SELECT. */
@Service
@RequiredArgsConstructor
public class SourceSchemaExportService {
    private final SemanticProjectRepository projects;
    private final DatabaseUtil databases;
    private final DBConnectionPoolFactory pools;

    public JsonNode capture(Long projectId,Long versionId) throws Exception {
        var version=projects.findVersion(versionId).orElseThrow();
        if(!projectId.equals(version.getProjectId()))throw new IllegalArgumentException("Version belongs to another project");
        var bindings=projects.findDatasourceBindings(versionId).stream().sorted(Comparator.comparing(ProjectDatasourceBinding::getDomainCode)).toList();
        if(bindings.isEmpty())throw new IllegalArgumentException("Bind exposed physical tables before exporting metadata");
        Set<String> domains=new HashSet<>();String dialect=null;List<Map<String,Object>> tables=new ArrayList<>();
        for(var binding:bindings) {
            if(!domains.add(binding.getDomainCode()))throw new IllegalArgumentException("Datasource logical identity is ambiguous");
            var config=databases.getDatasourceDbConfig(binding.getDatasourceId());
            String type=cn.lgs.semevosql.semantic.compiler.SqlDialect.from(config.getDialectType()).name().toLowerCase(Locale.ROOT);
            if(!Set.of("mysql","postgresql").contains(type))throw new IllegalArgumentException("Offline metadata dialect unsupported");
            if(dialect!=null && !dialect.equals(type))throw new IllegalArgumentException("Mixed dialects require a versioned source protocol");
            dialect=type;
            try(var connection=pools.getPoolByDbType(config.getDialectType()).getConnection(config)) {
                var metadata=connection.getMetaData();
                String catalog=type.equals("mysql")?config.getSchema():connection.getCatalog();
                String schema=type.equals("mysql")?null:config.getSchema();
                for(String requested:binding.getExposedTables().stream().sorted().toList()) {
                    String table=requested;String sourceSchema=config.getSchema();
                    if(requested.contains(".")) {
                        var parts=requested.split("\\.",-1);
                        if(parts.length!=2 || !parts[0].equals(sourceSchema))throw new IllegalArgumentException("Exposed table escapes configured schema");
                        table=parts[1];
                    }
                    tables.add(table(metadata,catalog,schema,sourceSchema,table,binding.getDomainCode()));
                }
            }
        }
        return JsonUtil.getObjectMapper().valueToTree(Map.of("formatVersion","1.0","dialect",dialect,"tables",tables));
    }
    /** Applies to ordinary catalog edits and publication as well as offline imports. */
    public void verifyStructuredSources(cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot snapshot) {
        if(snapshot.getModels().stream().noneMatch(m->m.getSourceJson()!=null))return;
        try {
            var physical=capture(snapshot.getProjectId(),snapshot.getProjectVersionId());
            var bindings=projects.findDatasourceBindings(snapshot.getProjectVersionId());
            for(var model:snapshot.getModels()) if(model.getSourceJson()!=null) {
                var source=cn.lgs.semevosql.semantic.domain.GovernedModelSource.parse(model.getSourceJson());
                var binding=bindings.stream().filter(b->b.getDatasourceId().equals(model.getDatasourceId())).findFirst().orElseThrow();
                Map<String,JsonNode> byAlias=new HashMap<>();
                for(var table:source.tables()) {
                    JsonNode actual=null;
                    for(var item:physical.path("tables"))if(item.path("datasource").asText().equals(binding.getDomainCode())
                            && item.path("schema").asText().equals(table.schema()) && item.path("table").asText().equals(table.table()))actual=item;
                    if(actual==null)throw new IllegalArgumentException("Structured model source is not currently exposed");
                    byAlias.put(table.alias(),actual);
                }
                for(var projection:source.projections()) {
                    JsonNode actual=null;
                    for(var column:byAlias.get(projection.mapping().source()).path("columns"))if(column.path("name").asText().equals(projection.mapping().column()))actual=column;
                    var logical=snapshot.getColumns().stream().filter(c->c.getModelCode().equals(model.getModelCode()) && c.getColumnName().equals(projection.code())).findFirst().orElseThrow();
                    if(actual==null || !actual.path("dataType").asText().equals(logical.getDataType()))throw new IllegalArgumentException("Structured source column type changed");
                }
                var grain=snapshot.getGrains().stream().filter(g->g.getModelCode().equals(model.getModelCode())
                    && g.getStatus()==cn.lgs.semevosql.semantic.domain.SemanticAssetStatus.ENABLED).findFirst().orElseThrow();
                Set<String> identity=new HashSet<>();
                for(String logicalKey:grain.getKeyColumns().split(",")) {
                    var projection=source.projections().stream().filter(p->p.code().equals(logicalKey.trim())).findFirst().orElseThrow();
                    if(!projection.mapping().source().equals(source.base()))throw new IllegalArgumentException("Structured grain must use its base identity");
                    var base=byAlias.get(source.base());JsonNode physicalKey=null;
                    for(var column:base.path("columns"))if(column.path("name").asText().equals(projection.mapping().column()))physicalKey=column;
                    if(physicalKey==null || physicalKey.path("nullable").asBoolean())throw new IllegalArgumentException("Structured grain key is nullable");
                    identity.add(projection.mapping().column());
                }
                if(!coversUniqueKey(byAlias.get(source.base()),identity))throw new IllegalArgumentException("Structured grain unique constraint is no longer present");
                for(var join:source.joins()) {
                    var target=byAlias.get(join.right());Set<String> joined=new HashSet<>();join.on().forEach(pair->joined.add(pair.right().column()));
                    if(!coversUniqueKey(target,joined))throw new IllegalArgumentException("Structured join unique constraint is no longer present");
                }
            }
        }catch(RuntimeException invalid){throw invalid;}catch(Exception unavailable){throw new IllegalStateException("Cannot verify current structured source metadata",unavailable);}
    }
    private static boolean coversUniqueKey(JsonNode table,Set<String> columns) {
        var keys=new ArrayList<JsonNode>();keys.add(table.path("primaryKey"));table.path("uniqueKeys").forEach(keys::add);
        return keys.stream().filter(key->!key.isEmpty()).anyMatch(key->{for(var col:key)if(!columns.contains(col.asText()))return false;return true;});
    }
    private Map<String,Object> table(DatabaseMetaData metadata,String catalog,String schema,String sourceSchema,String name,String datasource) throws SQLException {
        String pattern=pattern(metadata,name);Map<String,Object> table=new LinkedHashMap<>();
        table.put("datasource",datasource);table.put("schema",sourceSchema);table.put("table",name);
        boolean found=false;
        try(var rs=metadata.getTables(catalog,schema,pattern,new String[]{"TABLE","VIEW"})) {
            while(rs.next()) if(name.equals(rs.getString("TABLE_NAME"))) {
                if(found)throw new IllegalArgumentException("Ambiguous physical table metadata");found=true;
                comment(table,rs.getString("REMARKS"));
            }
        }
        if(!found)throw new IllegalArgumentException("An exposed physical table is no longer present");
        List<Map<String,Object>> columns=new ArrayList<>();
        try(var rs=metadata.getColumns(catalog,schema,pattern,"%")) {
            while(rs.next()) if(name.equals(rs.getString("TABLE_NAME"))) {
                var column=new LinkedHashMap<String,Object>();column.put("name",rs.getString("COLUMN_NAME"));
                column.put("dataType",dataType(rs.getInt("DATA_TYPE")));column.put("nativeType",rs.getString("TYPE_NAME"));
                column.put("nullable",rs.getInt("NULLABLE")!=DatabaseMetaData.columnNoNulls);comment(column,rs.getString("REMARKS"));
                columns.add(column);
            }
        }
        columns.sort(Comparator.comparing(c->c.get("name").toString()));table.put("columns",columns);
        var pk=new TreeMap<Integer,String>();
        try(var rs=metadata.getPrimaryKeys(catalog,schema,name)){while(rs.next())pk.put(rs.getInt("KEY_SEQ"),rs.getString("COLUMN_NAME"));}
        table.put("primaryKey",new ArrayList<>(pk.values()));
        Map<String,TreeMap<Integer,String>> indexes=new TreeMap<>();Set<String> unsupportedIndexes=new HashSet<>();
        try(var rs=metadata.getIndexInfo(catalog,schema,name,true,false)) {
            while(rs.next()) {
                String index=rs.getString("INDEX_NAME"),column=rs.getString("COLUMN_NAME");
                if(index==null || rs.getBoolean("NON_UNIQUE"))continue;
                if(column==null || rs.getString("FILTER_CONDITION")!=null){unsupportedIndexes.add(index);continue;}
                indexes.computeIfAbsent(index,k->new TreeMap<>()).put(rs.getInt("ORDINAL_POSITION"),column);
            }
        }
        var unique=new ArrayList<List<String>>();
        indexes.forEach((key,cols)->{if(!unsupportedIndexes.contains(key))unique.add(new ArrayList<>(cols.values()));});
        table.put("uniqueKeys",unique.stream().distinct().sorted(Comparator.comparing(Object::toString)).toList());
        Map<String,List<Map<String,Object>>> foreign=new TreeMap<>();
        try(var rs=metadata.getImportedKeys(catalog,schema,name)) {
            while(rs.next()) {
                String targetSchema=rs.getString(schema==null?"PKTABLE_CAT":"PKTABLE_SCHEM");
                String key=Objects.toString(rs.getString("FK_NAME"),rs.getString("PKTABLE_NAME"));
                foreign.computeIfAbsent(key,k->new ArrayList<>()).add(Map.of("sequence",rs.getInt("KEY_SEQ"),
                    "column",rs.getString("FKCOLUMN_NAME"),"targetColumn",rs.getString("PKCOLUMN_NAME"),
                    "schema",targetSchema,"table",rs.getString("PKTABLE_NAME")));
            }
        }
        var fks=new ArrayList<Map<String,Object>>();
        foreign.values().forEach(parts->{
            parts.sort(Comparator.comparingInt(x->(Integer)x.get("sequence")));var first=parts.get(0);
            fks.add(Map.of("columns",parts.stream().map(x->x.get("column")).toList(),"target",Map.of("datasource",datasource,
                "schema",first.get("schema"),"table",first.get("table"),"columns",parts.stream().map(x->x.get("targetColumn")).toList())));
        });table.put("foreignKeys",fks);return table;
    }
    private static void comment(Map<String,Object> target,String comment){if(comment!=null)target.put("comment",comment);}
    private static String pattern(DatabaseMetaData metadata,String name) throws SQLException {
        String escape=metadata.getSearchStringEscape();return name.replace(escape,escape+escape).replace("_",escape+"_").replace("%",escape+"%");
    }
    /** Legacy catalogs retain native SQL names; consumers adapt them without rewriting published facts. */
    public static String protocolType(String nativeType) {
        String type=Objects.toString(nativeType,"").trim().toLowerCase(Locale.ROOT).replaceAll("\\([^)]*\\)","").replaceAll("\\s+"," ").trim();
        if(Set.of("integer","decimal","string","boolean","date","datetime").contains(type))return type;
        type=switch(type){
            case "int","int4"->"INTEGER";case "int8"->"BIGINT";case "int2"->"SMALLINT";
            case "text"->"VARCHAR";case "bool"->"BOOLEAN";case "double precision"->"DOUBLE";
            case "timestamp without time zone"->"TIMESTAMP";case "timestamptz","timestamp with time zone"->"TIMESTAMP_WITH_TIMEZONE";
            default->type.toUpperCase(Locale.ROOT);
        };
        try{return dataType(java.sql.JDBCType.valueOf(type).getVendorTypeNumber());}
        catch(IllegalArgumentException unsupported){return "unsupported";}
    }
    public static String dataType(int jdbcType) {
        return switch(jdbcType) {
            case Types.TINYINT,Types.SMALLINT,Types.INTEGER,Types.BIGINT->"integer";
            case Types.NUMERIC,Types.DECIMAL,Types.FLOAT,Types.REAL,Types.DOUBLE->"decimal";
            case Types.CHAR,Types.VARCHAR,Types.LONGVARCHAR,Types.NCHAR,Types.NVARCHAR,Types.LONGNVARCHAR,Types.CLOB,Types.NCLOB->"string";
            case Types.BOOLEAN,Types.BIT->"boolean";case Types.DATE->"date";
            case Types.TIMESTAMP,Types.TIMESTAMP_WITH_TIMEZONE->"datetime";
            default->throw new IllegalArgumentException("Physical type has no supported offline mapping: "+jdbcType);
        };
    }
}
