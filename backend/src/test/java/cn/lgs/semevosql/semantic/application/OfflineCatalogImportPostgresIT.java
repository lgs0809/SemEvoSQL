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

import cn.lgs.semevosql.bo.DbConfigBO;
import cn.lgs.semevosql.common.*;
import cn.lgs.semevosql.connector.pool.*;
import cn.lgs.semevosql.project.application.ProjectScopeService;
import cn.lgs.semevosql.project.domain.*;
import cn.lgs.semevosql.project.infrastructure.*;
import cn.lgs.semevosql.semantic.infrastructure.*;
import cn.lgs.semevosql.semantic.retrieval.*;
import cn.lgs.semevosql.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.DriverManager;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ibatis.session.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual JDBC metadata, Flyway, MyBatis and Spring transactions; seeded source rows remain unchanged. */
@Testcontainers
class OfflineCatalogImportPostgresIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;static SqlSessionTemplate sessions;static TransactionTemplate transactions;
    static AtomicLong ids=new AtomicLong(500);
    long id;String schema;MybatisSemanticProjectRepository projects;MybatisSemanticCatalogRepository repository;
    SourceSchemaExportService source;SemanticCatalogApplicationService catalogs;OfflineCatalogImportService service;LocalSecurityProperties security;
    final OperatorContext admin=new OperatorContext("initializer","AUTHENTICATED","synthetic-import","test");
    ObjectNode pack;
    @BeforeAll static void initialize() throws Exception {
        var ds=new DriverManagerDataSource(PG.getJdbcUrl()+"&stringtype=unspecified",PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").load().migrate();jdbc=new JdbcTemplate(ds);
        jdbc.update("INSERT INTO datasource(id,name,type,host,port,database_name,username,password) VALUES(17,'synthetic source','postgresql','127.0.0.1',5432,'synthetic','fixture','fixture')");
        var configuration=new Configuration();configuration.setMapUnderscoreToCamelCase(true);configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(configuration);
        factory.setTransactionFactory(new org.mybatis.spring.transaction.SpringManagedTransactionFactory());
        var sql=factory.getObject();sql.getConfiguration().addMapper(SemEvoSQLProjectMapper.class);sql.getConfiguration().addMapper(SemEvoSQLSemanticCatalogMapper.class);
        sessions=new SqlSessionTemplate(sql);transactions=new TransactionTemplate(new DataSourceTransactionManager(ds));
    }
    @BeforeEach void fixture() throws Exception {
        id=ids.incrementAndGet();schema="init_"+id;
        jdbc.update("INSERT INTO qw_project(id,project_code,name,business_domain,status,created_by) VALUES(?,?,?,'fixture','ACTIVE','initializer')",id,"import-"+id,"Synthetic initialization");
        jdbc.update("INSERT INTO qw_project_version(id,project_id,version_no,version_number,status,analysis_status,semantic_major,semantic_minor,semantic_patch) VALUES(?,?,1,'1.0.0','DRAFT','PENDING',1,0,0)",id,id);
        jdbc.execute("CREATE SCHEMA "+schema);jdbc.execute("CREATE TABLE "+schema+".t_order(id bigint PRIMARY KEY,amt bigint NOT NULL,pt timestamp,st integer NOT NULL)");
        jdbc.execute("INSERT INTO "+schema+".t_order VALUES(1,15000,'2026-01-01',1),(2,12000,'2026-01-02',1),(3,99900,'2026-01-03',9)");
        projects=new MybatisSemanticProjectRepository(sessions.getMapper(SemEvoSQLProjectMapper.class));
        transactions.executeWithoutResult(status->projects.saveDatasourceBinding(ProjectDatasourceBinding.create(id,id,17,"mall","Fixture","Synthetic source",0,List.of("t_order"))));
        var db=mock(DatabaseUtil.class);when(db.getDatasourceDbConfig(17)).thenReturn(DbConfigBO.builder().schema(schema).dialectType("postgresql").build());
        var pool=mock(DBConnectionPool.class);when(pool.getConnection(any())).thenAnswer(call->DriverManager.getConnection(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword()));
        var pools=mock(DBConnectionPoolFactory.class);when(pools.getPoolByDbType("postgresql")).thenReturn(pool);
        source=new SourceSchemaExportService(projects,db,pools);repository=new MybatisSemanticCatalogRepository(sessions.getMapper(SemEvoSQLSemanticCatalogMapper.class));
        catalogs=new SemanticCatalogApplicationService(repository,new SemanticCatalogReadService(repository),projects,null,null,null,null,null,source,
            new SemanticRetrievalDocumentBuildService(repository,new SemanticRetrievalDocumentRepository(jdbc),mock(SemanticRetrievalIndexService.class),transactions));
        security=new LocalSecurityProperties();security.setEnabled(true);var account=new LocalSecurityProperties.Account();account.setAdministrator(true);security.getAccounts().put("initializer",account);
        service=create(transactions);
        pack=OfflineCatalogProtocolTest.sample();((ObjectNode)pack.path("catalog").path("entities").get(0).path("source").path("tables").get(0)).put("schema",schema);
        pack.put("sourceSchemaFingerprint",OfflineCatalogProtocol.hash(source.capture(id,id)));
    }
    OfflineCatalogImportService create(TransactionTemplate tx) {
        var operators=new LocalOperatorService(security);
        return new OfflineCatalogImportService(source,projects,catalogs,repository,new ProjectScopeService(jdbc,operators),security,operators,jdbc,tx);
    }
    OfflineCatalogImportService.Preview preview() throws Exception{return service.preview(id,id,pack.toString(),admin);}
    void sharedFixture() throws Exception {
        pack=OfflineCatalogProtocolTest.sharedSample();
        for(var entity:pack.path("catalog").path("entities"))((ObjectNode)entity.path("source").path("tables").get(0)).put("schema",schema);
        pack.put("sourceSchemaFingerprint",OfflineCatalogProtocol.hash(source.capture(id,id)));
    }
    @Test void sharedImportCommitsCanonicalRevisionsRolesDictionaryAndScopedProjection() throws Exception {
        sharedFixture();var receipt=preview();assertEquals("COMMITTED",service.commit(id,id,receipt.importId(),admin).status());
        var snapshot=repository.loadCatalog(id,id);assertEquals(3,snapshot.getSharedDefinitions().size());assertEquals(6,snapshot.getModelBindings().size());
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM qw_semantic_definition_revision WHERE project_id=? AND definition_format='AST_V1_2'",Integer.class,id));
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM qw_semantic_enum_dictionary_entry WHERE project_id=?",Integer.class,id));
        var slice=repository.loadModelSlice(id,id,Set.of("paid_order"));assertEquals(1,slice.getModels().size());assertEquals(3,slice.getModelBindings().size());
        assertEquals(3,slice.getSharedDefinitions().size());assertEquals(1,slice.getEnumDictionaries().size());assertTrue(slice.getRelationships().isEmpty());
        var metric=slice.getMetrics().get(0);assertEquals("paid_order",metric.getDefinitionBinding().modelCode());assertEquals("payment",metric.getDefinitionBinding().bindingCode());
        assertEquals("settled_on",metric.getTimeColumn());assertTrue(metric.getExpression().contains("amount_fen"));
        assertEquals(List.of("已付款"),slice.getEnumValues().stream().filter(v->v.getValueCode().equals("1")).findFirst().orElseThrow().getConfirmedAliases());
        assertEquals(3,jdbc.queryForObject("SELECT count(*) FROM "+schema+".t_order",Integer.class));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE qw_semantic_definition_revision SET definition_json=definition_json || '{\"name\":\"changed\"}'::jsonb WHERE project_id=?",id));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE qw_semantic_metric SET expression='COUNT(*)' WHERE project_version_id=?",id));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO qw_semantic_enum_dictionary_entry VALUES(?,'payment_state',1,'\"late\"',10,'{\"value\":\"late\",\"label\":\"late\",\"aliases\":[]}'::jsonb)",id));
        var docs=new SemanticRetrievalDocumentRepository(jdbc).findCatalog(id,id,SemanticCatalogFingerprint.fingerprint(snapshot));assertEquals(2,docs.size());
        assertTrue(docs.stream().filter(d->d.modelCode().equals("paid_order")).findFirst().orElseThrow().semanticText().contains("已付订单到账金额"));
    }
    @Test void frozenSharedRoleCompilesAgainstRealSourceAndRejectsSwitchedDefinition() throws Exception {
        sharedFixture();var receipt=preview();service.commit(id,id,receipt.importId(),admin);
        var snapshot=repository.loadModelSlice(id,id,Set.of("paid_order"));var metric=snapshot.getMetrics().get(0);var model=snapshot.getModels().get(0);
        var selected=cn.lgs.semevosql.semantic.domain.SemanticBlueprint.MetricSelection.builder().modelCode(metric.getModelCode()).metricCode(metric.getMetricCode())
            .expression(metric.getExpression()).aggregation(metric.getAggregation()).unit(metric.getUnit()).timeColumn(metric.getTimeColumn()).filterExpression(metric.getFilterExpression())
            .definitionBinding(metric.getDefinitionBinding()).build();
        var plan=cn.lgs.semevosql.semantic.domain.SemanticBlueprint.builder().projectId(id).projectVersionId(id).canonicalQuery("合成共享定义编译集成验收")
            .models(List.of(cn.lgs.semevosql.semantic.domain.SemanticBlueprint.ModelSelection.builder().modelCode(model.getModelCode()).physicalTable(model.getPhysicalTable()).datasourceId(17).build()))
            .metrics(List.of(selected)).projections(List.of(cn.lgs.semevosql.semantic.domain.SemanticBlueprint.ProjectionSelection.builder().modelCode(metric.getModelCode())
                .expression(metric.getExpression()).alias(metric.getMetricCode()).projectionType("METRIC").build()))
            .sourceSubPlans(List.of(cn.lgs.semevosql.semantic.domain.SemanticBlueprint.SourceSubPlan.builder().datasourceId(17).modelCodes(List.of(model.getModelCode())).physicalTables(List.of(model.getPhysicalTable())).build()))
            .executable(true).validationErrors(List.of()).build();
        var compiler=new cn.lgs.semevosql.semantic.compiler.SemanticSqlCompiler();var compiled=compiler.compile(plan,snapshot,cn.lgs.semevosql.semantic.compiler.SqlDialect.POSTGRESQL).sources().get(0);
        assertFalse(compiled.sql().contains(" JOIN "));assertEquals(270.0,jdbc.queryForObject(compiled.sql(),Double.class,compiled.parameters().toArray()));
        var original=selected.getDefinitionBinding();selected.setDefinitionBinding(new cn.lgs.semevosql.semantic.domain.SemanticDefinitionBinding(original.definitionCode(),2,original.modelCode(),original.bindingCode(),original.roleName(),original.confirmedAliases(),null,null));
        assertThrows(IllegalArgumentException.class,()->compiler.compile(plan,snapshot,cn.lgs.semevosql.semantic.compiler.SqlDialect.POSTGRESQL));
        selected.setDefinitionBinding(original);snapshot.getColumns().stream().filter(c->c.getColumnName().equals("amount_fen")).findFirst().orElseThrow().setAllowAggregation(false);
        assertThrows(IllegalArgumentException.class,()->compiler.compile(plan,snapshot,cn.lgs.semevosql.semantic.compiler.SqlDialect.POSTGRESQL));
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void governedAttributeProjectionUsesRealScopedOwnerCandidatesCompilationAndFrozenRecovery(boolean explicitAst) throws Exception {
        sharedFixture();
        if (explicitAst) {
        var definition = pack.withObject("catalog").withArray("definitions").addObject();
        definition.put("code","record_identity").put("revision",2).put("type","ATTRIBUTE")
            .put("name","记录编号").put("description","仅用于合成验收的已确认记录标识");
        definition.putArray("aliases").add("记录标识");
        var specification=definition.putObject("specification");specification.put("attribute","key");
        specification.putArray("parameters").addObject().put("code","key").put("dataType","integer");
        for(var model:List.of("order","paid_order")) {
            var binding=pack.withObject("catalog").withArray("bindings").addObject();
            binding.put("model",model).put("code","record_role").put("definition","record_identity")
                .put("definitionRevision",2).put("roleName",model+"记录编号");
            binding.putArray("aliases");binding.putObject("attributeMappings").put("key",model.equals("order")?"order_id":"payment_key");
            pack.withArray("evidence").addObject().put("target","binding:"+model+"/record_role")
                .put("kind","document").put("source","synthetic-business-notes.md").put("location","合成程序验收")
                .put("statement","真实导入的隔离合成记录角色，不代表外部业务");
        }
        pack.withArray("evidence").addObject().put("target","definition:record_identity@2")
            .put("kind","document").put("source","synthetic-business-notes.md").put("location","合成程序验收")
            .put("statement","真实导入的隔离合成标识定义，不代表外部业务");
        }
        var receipt=preview();assertEquals("COMMITTED",service.commit(id,id,receipt.importId(),admin).status());
        var original=repository.loadCatalog(id,id);
        String storedHash=SemanticCatalogFingerprint.fingerprint(original);
        // This component fixture provides an authority hash without fabricating publication or model readiness.
        // Real release/index gates are covered separately by normal deployment acceptance.
        transactions.executeWithoutResult(status->{var version=projects.findVersion(id).orElseThrow();
            version.setCatalogHash(storedHash);projects.updateVersion(version);});
        String authority=repository.authoritativeCatalogHash(id,id);
        int dimensions=jdbc.queryForObject("SELECT count(*) FROM qw_semantic_dimension WHERE project_version_id=?",Integer.class,id);
        String bindingCode=explicitAst ? "record_role" : original.getLegacyModelBindings().stream()
            .filter(b->"order".equals(b.path("model").asText()) && "ATTRIBUTE".equals(b.path("assetType").asText())
                && "order_id".equals(b.path("assetKey").asText())).findFirst().orElseThrow().path("code").asText();
        String code=GovernedAttributeDimensions.identity("order",bindingCode);
        var owners=repository.findAssetOwners(id,id,"DIMENSION",Set.of(code));
        assertEquals(1,owners.size());assertEquals("order",owners.get(0).modelCode());assertEquals(code,owners.get(0).assetKey());
        assertTrue(repository.findAssetOwners(id+1,id,"DIMENSION",Set.of(code)).isEmpty());
        var reader=new SemanticCatalogReadService(repository);
        var view=reader.getForModels(id,id,Set.of("order"),authority);
        var dimension=view.getDimensions().stream().filter(d->code.equals(d.getDimensionCode())).findFirst().orElseThrow();
        var hit=new cn.lgs.semevosql.semantic.retrieval.SemanticHybridRetrievalService.RetrievalHit(
            cn.lgs.semevosql.semantic.retrieval.SemanticRetrievalDocument.DocumentType.MODEL,"MODEL","model:order","order",
            schema+".t_order",1d,Map.of(),Map.of());
        var candidates=new SemanticBlueprintGenerationService(repository,null).candidates(id,id,List.of(schema+".t_order"),List.of(hit));
        assertTrue(candidates.dimensions().stream().anyMatch(d->code.equals(d.getDimensionCode()) && dimension.getDefinitionBinding().equals(d.getDefinitionBinding())));
        var lookup=new SemanticCatalogLookupService(repository,reader);
        var loaded=lookup.loadAssets(id,id,authority,Set.of(new SemanticCatalogLookupService.AssetRef("DIMENSION",code)));
        assertEquals(List.of("order"),loaded.getModels().stream().map(cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot.Model::getModelCode).toList());
        var metric=view.getMetrics().get(0);var model=view.getModels().get(0);
        var selected=cn.lgs.semevosql.semantic.domain.SemanticBlueprint.DimensionSelection.builder().modelCode("order")
            .dimensionCode(code).businessName(dimension.getBusinessName()).columnName("order_id").dimensionType("ATTRIBUTE")
            .definitionBinding(dimension.getDefinitionBinding()).build();
        var plan=cn.lgs.semevosql.semantic.domain.SemanticBlueprint.builder().projectId(id).projectVersionId(id)
            .canonicalQuery("按合成记录编号统计已确认支付金额")
            .models(List.of(cn.lgs.semevosql.semantic.domain.SemanticBlueprint.ModelSelection.builder().modelCode("order")
                .physicalTable(model.getPhysicalTable()).datasourceId(17).build()))
            .dimensions(List.of(selected)).metrics(List.of(cn.lgs.semevosql.semantic.domain.SemanticBlueprint.MetricSelection.builder()
                .modelCode("order").metricCode(metric.getMetricCode()).expression(metric.getExpression()).aggregation(metric.getAggregation())
                .unit(metric.getUnit()).filterExpression(metric.getFilterExpression()).timeColumn(metric.getTimeColumn())
                .definitionBinding(metric.getDefinitionBinding()).build()))
            .projections(List.of(cn.lgs.semevosql.semantic.domain.SemanticBlueprint.ProjectionSelection.builder().modelCode("order")
                .columnName("order_id").alias(code).projectionType("DIMENSION").build(),
                cn.lgs.semevosql.semantic.domain.SemanticBlueprint.ProjectionSelection.builder().modelCode("order")
                    .expression(metric.getExpression()).alias(metric.getMetricCode()).projectionType("METRIC").build()))
            .groupBy(List.of(cn.lgs.semevosql.semantic.domain.SemanticBlueprint.GroupSelection.builder().modelCode("order").columnName("order_id").build()))
            .sourceSubPlans(List.of(cn.lgs.semevosql.semantic.domain.SemanticBlueprint.SourceSubPlan.builder().datasourceId(17)
                .modelCodes(List.of("order")).physicalTables(List.of(model.getPhysicalTable())).build()))
            .executable(true).validationErrors(List.of()).build();
        var compiler=new cn.lgs.semevosql.semantic.compiler.SemanticSqlCompiler();
        var compiled=compiler.compile(plan,view,cn.lgs.semevosql.semantic.compiler.SqlDialect.POSTGRESQL).sources().get(0);
        var rows=jdbc.queryForList(compiled.sql(),compiled.parameters().toArray());
        assertEquals(2,rows.size());
        assertEquals(Set.of(1L,2L),rows.stream().map(row->((Number)row.get(code)).longValue()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(270.0,rows.stream().mapToDouble(row->((Number)row.get(metric.getMetricCode())).doubleValue()).sum());
        var restored=JsonUtil.getObjectMapper().readValue(JsonUtil.getObjectMapper().writeValueAsString(plan),cn.lgs.semevosql.semantic.domain.SemanticBlueprint.class);
        var newReader=new SemanticCatalogReadService(new MybatisSemanticCatalogRepository(sessions.getMapper(SemEvoSQLSemanticCatalogMapper.class)));
        var recovered=newReader.getForModels(id,id,Set.of("order"),authority);
        assertEquals(compiled,compiler.compile(restored,recovered,cn.lgs.semevosql.semantic.compiler.SqlDialect.POSTGRESQL).sources().get(0));
        var ref=selected.getDefinitionBinding();restored.getDimensions().get(0).setDefinitionBinding(new cn.lgs.semevosql.semantic.domain.SemanticDefinitionBinding(
            ref.definitionCode(),ref.definitionRevision()+1,ref.modelCode(),ref.bindingCode(),ref.roleName(),ref.confirmedAliases(),null,null));
        assertThrows(IllegalArgumentException.class,()->compiler.compile(restored,recovered,cn.lgs.semevosql.semantic.compiler.SqlDialect.POSTGRESQL));
        assertEquals(dimensions,jdbc.queryForObject("SELECT count(*) FROM qw_semantic_dimension WHERE project_version_id=?",Integer.class,id));
        assertEquals(storedHash,SemanticCatalogFingerprint.fingerprint(repository.loadCatalog(id,id)));
        assertEquals(authority,repository.authoritativeCatalogHash(id,id));
        assertFalse(repository.loadCatalog(id,id).getDimensions().stream().anyMatch(d->code.equals(d.getDimensionCode())));
        if (!explicitAst) {
            for (String mutation : List.of("allow_projection=false", "allow_send_to_llm=false", "expression='order_id + 1'", "business_name='different role'")) {
                assertThrows(org.springframework.dao.DataAccessException.class,()->transactions.executeWithoutResult(tx->
                    jdbc.update("UPDATE qw_semantic_column SET "+mutation+" WHERE project_id=? AND project_version_id=? AND model_code='order' AND column_name='order_id'",id,id)));
                // Negative post-load snapshots cannot claim the immutable stored authority either.
                var changed=repository.loadModelSlice(id,id,Set.of("order"));
                var field=changed.getColumns().stream().filter(c->"order_id".equals(c.getColumnName())).findFirst().orElseThrow();
                switch(mutation) {
                    case "allow_projection=false" -> field.setAllowProjection(false);
                    case "allow_send_to_llm=false" -> field.setAllowSendToLlm(false);
                    case "expression='order_id + 1'" -> field.setExpression("order_id + 1");
                    default -> field.setBusinessName("different role");
                }
                assertFalse(GovernedAttributeDimensions.expand(changed).getDimensions().stream().anyMatch(d->code.equals(d.getDimensionCode())),mutation);
            }
            assertEquals(storedHash,SemanticCatalogFingerprint.fingerprint(repository.loadCatalog(id,id)));
        }
        System.out.println("GOVERNED_ATTRIBUTE_REAL_PG_IMPORT_OWNER_CANDIDATE_SQL_RECOVERY_UNCHANGED_HASH "+code);
    }

    @Test void postgresProjectionIdentityExactlyMatchesCanonicalModelAndRoleHash() {
        for(var pair:List.of(List.of("ordinary","role"),List.of("x".repeat(128),"y".repeat(128)),List.of("引号\\\"对象","角色\n制表\t"))) {
            String actual=jdbc.queryForObject("SELECT 'a_' || substr(encode(sha256(convert_to('[' || to_json(?::text)::text || ',' || to_json(?::text)::text || ']', 'UTF8')), 'hex'),1,32)",String.class,pair.get(0),pair.get(1));
            assertEquals(GovernedAttributeDimensions.identity(pair.get(0),pair.get(1)),actual);
        }
    }
    @Test void sharedRevisionRebuildsEveryBoundRoleButLeavesUnrelatedModelContentUnchanged() throws Exception {
        sharedFixture();var catalog=(ObjectNode)pack.path("catalog");var independent=(ObjectNode)catalog.path("entities").get(0).deepCopy();
        independent.put("code","independent_order");independent.put("name","独立订单参考实体");((com.fasterxml.jackson.databind.node.ArrayNode)catalog.get("entities")).add(independent);
        var copied=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        for(var evidence:pack.path("evidence"))if(evidence.path("target").asText().startsWith("entity:order")) {
            var row=(ObjectNode)evidence.deepCopy();row.put("target",row.path("target").asText().replace("entity:order","entity:independent_order"));copied.add(row);
        }
        copied.forEach(pack.withArray("evidence")::add);
        var first=preview();service.commit(id,id,first.importId(),admin);var documents=new SemanticRetrievalDocumentRepository(jdbc);
        var before=new HashMap<String,String>();documents.findVersion(id,id).forEach(d->before.put(d.modelCode(),d.contentHash()));
        var definition=(ObjectNode)catalog.path("definitions").get(0);definition.put("revision",2);definition.put("description","同一合成公式的新修订，业务说明更明确");
        for(var binding:catalog.path("bindings"))if(binding.path("definition").asText().equals("payment_amount"))((ObjectNode)binding).put("definitionRevision",2);
        for(var evidence:pack.path("evidence"))if(evidence.path("target").asText().equals("definition:payment_amount@1"))((ObjectNode)evidence).put("target","definition:payment_amount@2");
        var second=preview();service.commit(id,id,second.importId(),admin);
        documents.findVersion(id,id).forEach(d->{if(d.modelCode().equals("independent_order"))assertEquals(before.get(d.modelCode()),d.contentHash());
            else assertNotEquals(before.get(d.modelCode()),d.contentHash());});
        assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM qw_semantic_definition_revision WHERE project_id=? AND definition_code='payment_amount'",Integer.class,id));
        assertTrue(repository.loadCatalog(id,id).getMetrics().stream().allMatch(m->m.getDefinitionBinding().definitionRevision()==2));
    }
    @Test void crossProjectAndCrossVersionSharedReferencesCannotBeInserted() throws Exception {
        sharedFixture();var first=preview();service.commit(id,id,first.importId(),admin);long other=ids.incrementAndGet();
        jdbc.update("INSERT INTO qw_project(id,project_code,name,business_domain,status,created_by) VALUES(?,?,'Other','fixture','ACTIVE','initializer')",other,"other-"+other);
        jdbc.update("INSERT INTO qw_project_version(id,project_id,version_no,version_number,status,analysis_status,semantic_major,semantic_minor,semantic_patch) VALUES(?,?,1,'1.0.0','DRAFT','PENDING',1,0,0)",other,other);
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO qw_semantic_version_definition VALUES(?,?,'payment_amount',1)",other,other));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("INSERT INTO qw_semantic_version_definition VALUES(?,?,'payment_amount',1)",id,other));
        var snapshot=repository.loadCatalog(id,id);snapshot.getEnumValues().clear();
        assertThrows(IllegalArgumentException.class,()->transactions.executeWithoutResult(tx->catalogs.replaceDraftCatalog(id,id,snapshot)));
        assertEquals(4,repository.loadCatalog(id,id).getEnumValues().size());
    }
    @Test void alteredSharedRevisionOrProjectionFailsBeforeReplacingTheDraft() throws Exception {
        sharedFixture();var first=preview();service.commit(id,id,first.importId(),admin);var snapshot=repository.loadCatalog(id,id);String hash=SemanticCatalogFingerprint.fingerprint(snapshot);
        snapshot.getMetrics().get(0).setUnit("美元");assertThrows(IllegalArgumentException.class,()->transactions.executeWithoutResult(tx->catalogs.replaceDraftCatalog(id,id,snapshot)));
        assertEquals(hash,SemanticCatalogFingerprint.fingerprint(repository.loadCatalog(id,id)));
        ((ObjectNode)pack.path("catalog").path("definitions").get(0)).put("description","Same revision with altered meaning");
        var receipt=preview();assertThrows(IllegalArgumentException.class,()->service.commit(id,id,receipt.importId(),admin));
        assertEquals(hash,SemanticCatalogFingerprint.fingerprint(repository.loadCatalog(id,id)));
        assertEquals("PREVIEWED",jdbc.queryForObject("SELECT status FROM qw_semantic_catalog_import WHERE import_id=?",String.class,receipt.importId()));
    }
    @Test void authoredHintsPersistForAllSupportedAssetsAndReachOnlyTheirGovernedModelDocument() throws Exception {
        pack.put("formatVersion","1.1");
        var json=JsonUtil.getObjectMapper();
        var entity=(ObjectNode)pack.path("catalog").path("entities").get(0);
        var hints=json.valueToTree(Map.of("queryExpressions",List.of("已确认的业务问法"),"queryContexts",List.of("订单分析场景")));
        entity.set("retrieval",hints);
        ((ObjectNode)entity.path("attributes").get(1)).set("retrieval",hints);
        var metric=(ObjectNode)pack.path("catalog").path("metrics").get(0);metric.set("retrieval",hints);
        metric.set("valueRange",json.valueToTree(Map.of("minimum",0,"maximum",1000)));
        var cat=(ObjectNode)pack.path("catalog");
        cat.withArray("dimensions").add(json.valueToTree(Map.of("code","status_dimension","entity","order","attribute","status","name","状态","description","订单状态","retrieval",hints)));
        cat.withArray("rules").add(json.valueToTree(Map.of("code","payment_range","entity","order","attribute","paid_amount","kind","range","minimum",0,"maximum",1000000,"retrieval",hints)));
        cat.withArray("relationships").add(json.valueToTree(Map.of("code","same_order","fromEntity","order","toEntity","order","cardinality","one_to_one","pairs",List.of(Map.of("from","order_id","to","order_id")),"name","同一订单身份","retrieval",hints)));
        var example=pack.path("evidence").get(0);
        for(String target:List.of("dimension:status_dimension","rule:payment_range","relationship:same_order")) {
            var evidence=example.deepCopy();((ObjectNode)evidence).put("target",target);pack.withArray("evidence").add(evidence);
        }
        var initial=preview();service.commit(id,id,initial.importId(),admin);
        var actual=repository.loadCatalog(id,id);
        var expected=cn.lgs.semevosql.semantic.domain.RetrievalHints.decode(hints.toString());
        assertEquals(expected,actual.getModels().get(0).getRetrieval());
        assertEquals(expected,actual.getColumns().stream().filter(c->c.getColumnName().equals("paid_amount")).findFirst().orElseThrow().getRetrieval());
        assertEquals(expected,actual.getMetrics().get(0).getRetrieval());
        assertEquals(expected,actual.getDimensions().get(0).getRetrieval());
        assertEquals(expected,actual.getRules().get(0).getRetrieval());
        assertEquals(expected,actual.getRelationships().get(0).getRetrieval());
        var scoped=repository.loadModelSlice(id,id,Set.of("order"));
        assertEquals(expected,scoped.getMetrics().get(0).getRetrieval());
        assertEquals(expected,scoped.getColumns().stream().filter(c->c.getColumnName().equals("paid_amount")).findFirst().orElseThrow().getRetrieval());
        var texts=jdbc.queryForList("SELECT semantic_text FROM qw_semantic_retrieval_document WHERE project_version_id=?",String.class,id);
        assertEquals(1,texts.size());assertTrue(texts.get(0).contains("已确认的业务问法"));
        assertTrue(texts.get(0).contains("minimumValue"));assertFalse(texts.get(0).contains("retrievalJson"));
        assertEquals(actual.getMetrics().get(0).numericRange(),scoped.getMetrics().get(0).numericRange());
    }
    @Test void explicitMetricRangeSurvivesActualDraftPersistenceAndScopeReads() throws Exception {
        var initial=preview();service.commit(id,id,initial.importId(),admin);
        var snapshot=repository.loadCatalog(id,id);var metric=snapshot.getMetrics().get(0);
        String oldHash=SemanticCatalogFingerprint.fingerprint(snapshot);
        metric.setMinimumValue(new java.math.BigDecimal("-10"));metric.setMaximumValue(new java.math.BigDecimal("100"));
        metric.setMinimumInclusive(false);metric.setMaximumInclusive(true);
        transactions.executeWithoutResult(status->catalogs.replaceDraftCatalog(id,id,snapshot));
        var actual=repository.loadCatalog(id,id).getMetrics().get(0);
        assertEquals(metric.numericRange(),actual.numericRange());
        assertNotEquals(oldHash,SemanticCatalogFingerprint.fingerprint(repository.loadCatalog(id,id)));
        assertFalse(actual.numericRange().contains(new java.math.BigDecimal("-10")));
        assertTrue(actual.numericRange().contains(new java.math.BigDecimal("100")));
        actual.setMinimumValue(new java.math.BigDecimal("101"));
        assertThrows(IllegalArgumentException.class,()->actual.numericRange());
        assertFalse(catalogs.validateDraftWrite(repository.loadCatalog(id,id)).stream().anyMatch(v->v.contains("numeric range")));
    }
    @Test void previewIsRepeatableAndConcurrentCommitCreatesOneReceiptWithoutChangingBusinessRows() throws Exception {
        var first=preview();assertEquals(first.importId(),preview().importId());assertTrue(repository.loadCatalog(id,id).getModels().isEmpty());
        var futures=new ArrayList<Future<OfflineCatalogImportService.Receipt>>();
        var executor=Executors.newFixedThreadPool(4);try {
            for(int i=0;i<4;i++)futures.add(executor.submit(()->service.commit(id,id,first.importId(),admin)));
            Set<String> hashes=new HashSet<>();for(var future:futures){var receipt=future.get(20,TimeUnit.SECONDS);assertEquals("COMMITTED",receipt.status());hashes.add(receipt.catalogHash());}
            assertEquals(1,hashes.size());
        }finally{executor.shutdownNow();}
        var catalog=repository.loadCatalog(id,id);assertEquals(1,catalog.getModels().size());assertEquals(4,catalog.getColumns().size());
        assertEquals("\u5206",catalog.getColumns().stream().filter(c->c.getColumnName().equals("paid_amount")).findFirst().orElseThrow().getUnit());
        assertNotNull(catalog.getModels().get(0).getSourceJson());assertEquals(Set.of(schema+".t_order"),repository.enabledPhysicalTables(id,id));
        assertEquals(InitializationAnalysisStatus.COMPLETED,projects.findVersion(id).orElseThrow().getAnalysisStatus());
        assertEquals(ProjectVersionStatus.DRAFT,projects.findVersion(id).orElseThrow().getStatus());
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM "+schema+".t_order",Integer.class));assertEquals(126900,jdbc.queryForObject("SELECT SUM(amt)::int FROM "+schema+".t_order",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_semantic_catalog_import WHERE project_id=? AND status='COMMITTED'",Integer.class,id));
    }
    @Test void metadataExportIsStableAndContainsNoCredentialsOrSamples() throws Exception {
        var exported=source.capture(id,id);assertEquals(exported,source.capture(id,id));
        String json=exported.toString();assertFalse(json.contains(PG.getJdbcUrl()));assertFalse(json.contains(PG.getPassword()));
        for(String key:List.of("username","password","url","samples","rows"))assertFalse(json.contains("\""+key+"\""));
        assertEquals("integer",exported.path("tables").get(0).path("columns").get(0).path("dataType").asText());
        assertFalse(exported.path("tables").get(0).path("primaryKey").isEmpty());
    }
    @Test void changedPhysicalSchemaAndDraftCatalogRejectOldPreview() throws Exception {
        var initial=preview();jdbc.execute("COMMENT ON COLUMN "+schema+".t_order.amt IS 'Changed physical evidence'");
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.commit(id,id,initial.importId(),admin)).getStatusCode().value());
        assertTrue(repository.loadCatalog(id,id).getModels().isEmpty());
        pack.put("sourceSchemaFingerprint",OfflineCatalogProtocol.hash(source.capture(id,id)));var next=preview();
        jdbc.update("INSERT INTO qw_semantic_model(project_id,project_version_id,datasource_id,model_code,physical_table,business_name,status) VALUES(?,?,17,'manual','t_order','Concurrent human draft','ENABLED')",id,id);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.commit(id,id,next.importId(),admin)).getStatusCode().value());
        assertEquals("manual",repository.loadCatalog(id,id).getModels().get(0).getModelCode());
    }
    @Test void commitFailureRollsBackAllAssetsAnalysisStateAndReceiptThenRetries() throws Exception {
        var initial=preview();
        var failing=new TransactionTemplate(transactions.getTransactionManager()) {
            @Override public <T> T execute(TransactionCallback<T> action) {
                return super.execute(status->{action.doInTransaction(status);throw new IllegalStateException("Synthetic crash before transaction commit");});
            }
        };
        assertThrows(IllegalStateException.class,()->create(failing).commit(id,id,initial.importId(),admin));
        assertTrue(repository.loadCatalog(id,id).getModels().isEmpty());assertEquals(InitializationAnalysisStatus.PENDING,projects.findVersion(id).orElseThrow().getAnalysisStatus());
        assertEquals("PREVIEWED",jdbc.queryForObject("SELECT status FROM qw_semantic_catalog_import WHERE import_id=?",String.class,initial.importId()));
        assertEquals("COMMITTED",service.commit(id,id,initial.importId(),admin).status());
    }
    @Test void ordinaryMemberAndUnprovenIdentityCannotImportOrPublish() throws Exception {
        var member=new LocalSecurityProperties.Account();member.setProjectIds(List.of(id));security.getAccounts().put("member",member);
        var actor=new OperatorContext("member","AUTHENTICATED","test","test");
        assertEquals(403,assertThrows(ResponseStatusException.class,()->service.preview(id,id,pack.toString(),actor)).getStatusCode().value());
        var initial=preview();security.getAccounts().get("initializer").setAdministrator(false);
        assertEquals(403,assertThrows(ResponseStatusException.class,()->service.commit(id,id,initial.importId(),admin)).getStatusCode().value());
        assertTrue(repository.loadCatalog(id,id).getModels().isEmpty());
    }
    @Test void publishedInputProvenanceCannotBeAlteredAfterCommit() throws Exception {
        var initial=preview();service.commit(id,id,initial.importId(),admin);
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE qw_semantic_catalog_import SET input_hash=? WHERE import_id=?","sha256:"+"0".repeat(64),initial.importId()));
        assertEquals(initial.inputHash(),jdbc.queryForObject("SELECT input_hash FROM qw_semantic_catalog_import WHERE import_id=?",String.class,initial.importId()));
    }
    @Test void assetAndDeterministicProjectionWorkCommitTogetherAndFailureRollsBackBoth() throws Exception {
        var first=preview();
        var documentRepo=spy(new SemanticRetrievalDocumentRepository(jdbc));
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("Synthetic crash after text/work insert");})
            .when(documentRepo).replaceCatalog(any(),any(),any(),anyList());
        catalogs=new SemanticCatalogApplicationService(repository,new SemanticCatalogReadService(repository),projects,
            null,null,null,null,null,source,new SemanticRetrievalDocumentBuildService(repository,documentRepo,mock(SemanticRetrievalIndexService.class),transactions));
        service=create(transactions);
        assertThrows(IllegalStateException.class,()->service.commit(id,id,first.importId(),admin));
        assertTrue(repository.loadCatalog(id,id).getModels().isEmpty());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_semantic_retrieval_document WHERE project_id=?",Integer.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_semantic_document_index_work w JOIN qw_semantic_retrieval_document d ON d.id=w.document_id WHERE d.project_id=?",Integer.class,id));
        assertEquals("PREVIEWED",jdbc.queryForObject("SELECT status FROM qw_semantic_catalog_import WHERE import_id=?",String.class,first.importId()));
    }
    @Test void obsoleteProjectionCannotOverwriteNewCatalogTextAndWork() throws Exception {
        var first=preview();service.commit(id,id,first.importId(),admin);
        var old=repository.loadCatalog(id,id);String oldHash=SemanticCatalogFingerprint.fingerprint(old);
        old.getMetrics().get(0).setDescription("New authoritative synthetic definition");
        transactions.executeWithoutResult(status->catalogs.replaceDraftCatalog(id,id,old));
        var docs=new SemanticRetrievalDocumentRepository(jdbc);
        String current=SemanticCatalogFingerprint.fingerprint(repository.loadCatalog(id,id));
        var builder=new SemanticRetrievalDocumentBuildService(repository,docs,mock(SemanticRetrievalIndexService.class),transactions);
        assertThrows(IllegalStateException.class,()->builder.prepare(id,id,oldHash));
        assertEquals(1,docs.findCatalog(id,id,current).size());
        assertTrue(docs.findCatalog(id,id,current).get(0).semanticText().contains("New authoritative synthetic definition"));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_semantic_document_index_work w JOIN qw_semantic_retrieval_document d ON d.id=w.document_id WHERE d.project_id=? AND w.status='PENDING'",Integer.class,id));
    }
    @Test void publicationLockRejectsConcurrentRawMutationAndMovingPublishedRows() throws Exception {
        var first=preview();service.commit(id,id,first.importId(),admin);
        var published=new CountDownLatch(1);var allowCommit=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try {
            var publish=executor.submit(()->transactions.execute(status->{
                projects.lockProject(id);projects.lockVersion(id,id);
                jdbc.update("UPDATE qw_project_version SET status='PUBLISHED' WHERE id=?",id);
                published.countDown();
                try {if(!allowCommit.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test coordination timeout");}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                return true;
            }));
            assertTrue(published.await(3,TimeUnit.SECONDS));
            var edit=executor.submit(()->jdbc.update("UPDATE qw_semantic_metric SET description='late mutation' WHERE project_version_id=?",id));
            assertThrows(TimeoutException.class,()->edit.get(150,TimeUnit.MILLISECONDS));
            allowCommit.countDown();assertTrue(publish.get(3,TimeUnit.SECONDS));
            assertInstanceOf(org.springframework.dao.DataAccessException.class,assertThrows(ExecutionException.class,()->edit.get(3,TimeUnit.SECONDS)).getCause());
            assertFalse(repository.loadCatalog(id,id).getMetrics().get(0).getDescription().equals("late mutation"));
            long target=ids.incrementAndGet();
            jdbc.update("INSERT INTO qw_project_version(id,project_id,version_no,version_number,status,analysis_status,semantic_major,semantic_minor,semantic_patch) VALUES(?,?,2,'1.0.1','DRAFT','PENDING',1,0,1)",target,id);
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE qw_semantic_metric SET project_version_id=? WHERE project_version_id=?",target,id));
            assertTrue(repository.loadCatalog(id,target).getMetrics().isEmpty());
        }finally{allowCommit.countDown();executor.shutdownNow();}
    }

}
