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

import static org.assertj.core.api.Assertions.*;
import cn.lgs.semevosql.semantic.domain.*;
import cn.lgs.semevosql.semantic.infrastructure.*;
import cn.lgs.semevosql.semantic.retrieval.SemanticHybridRetrievalService.RetrievalHit;
import cn.lgs.semevosql.semantic.retrieval.SemanticRetrievalDocument.DocumentType;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

/** Actual PostgreSQL + MyBatis SQL. All catalog rows are synthetic, in a disposable container. */
@Testcontainers
class SemanticCandidateLoadingPostgresIT {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;
    static SqlSessionFactory sessions;
    static final AtomicLong IDS = new AtomicLong(100);
    static final ReadTrace reads = new ReadTrace();
    SqlSession session;
    MybatisSemanticCatalogRepository repository;
    SemanticBlueprintGenerationService service;
    long project;
    static final String HASH = "a".repeat(64);

    @BeforeAll static void migrate() {
        var ds = new DriverManagerDataSource(PG.getJdbcUrl()+"&stringtype=unspecified",PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").load().migrate();
        jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO datasource(id,name,type,host,port,database_name,username,password) VALUES(1,'synthetic-one','postgresql','127.0.0.1',5432,'synthetic','fixture','fixture'),(2,'synthetic-two','postgresql','127.0.0.1',5432,'synthetic','fixture','fixture')");
        var config = new Configuration(new Environment("fixture",new JdbcTransactionFactory(),ds));
        config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.addMapper(SemEvoSQLSemanticCatalogMapper.class);
        config.addInterceptor(reads);
        sessions = new SqlSessionFactoryBuilder().build(config);
    }
    @BeforeEach void setup() {
        project = IDS.incrementAndGet();
        jdbc.update("INSERT INTO qw_project(id,project_code,name,business_domain,status,created_by) VALUES(?,?,?,'fixture','ACTIVE','fixture')",project,"slice-"+project,"slice "+project);
        jdbc.update("INSERT INTO qw_project_version(id,project_id,version_no,version_number,status,analysis_status,catalog_hash,semantic_major,semantic_minor,semantic_patch) VALUES(?,?,1,'1.0.0','DRAFT','COMPLETED',?,1,0,0)",project,project,HASH);
        session = sessions.openSession(true);
        repository = new MybatisSemanticCatalogRepository(session.getMapper(SemEvoSQLSemanticCatalogMapper.class));
        service = new SemanticBlueprintGenerationService(repository,null);
        reads.records.clear();
        reads.afterRead=null;
        session.getConfiguration().setLocalCacheScope(LocalCacheScope.STATEMENT);
    }
    @AfterEach void close() { if (session != null) session.close(); }
    void model(String code,String table,int source) {
        jdbc.update("INSERT INTO qw_semantic_model(project_id,project_version_id,datasource_id,model_code,physical_table,business_name,status) VALUES(?,?,?,?,?,?,'ENABLED')",project,project,source,code,table,code);
    }
    void relationship(String code,String from,String to) {
        jdbc.update("INSERT INTO qw_semantic_relationship(project_id,project_version_id,relationship_code,source_model_code,target_model_code,cardinality,join_condition,status) VALUES(?,?,?,?,?,'MANY_TO_ONE',?,'ENABLED')",project,project,code,from,to,from+".id = "+to+".id");
    }
    RetrievalHit hit(String code,String table) {
        return new RetrievalHit(DocumentType.MODEL,"MODEL","model:"+code,code,table,1d,Map.of(),Map.of());
    }
    SemanticCandidateSet candidates(String code,String table) {
        reads.records.clear();
        return service.candidates(project,project,List.of(table),List.of(hit(code,table)));
    }
    SemanticCatalogLookupService lookup() {
        return new SemanticCatalogLookupService(repository,new cn.lgs.semevosql.semantic.application.SemanticCatalogReadService(repository));
    }
    cn.lgs.semevosql.clarification.SemanticBindingTargetValidator bindingValidator() {
        var projects=org.mockito.Mockito.mock(cn.lgs.semevosql.project.domain.SemanticProjectRepository.class);
        var version=new cn.lgs.semevosql.project.domain.SemanticProjectVersion();
        version.setId(project);version.setProjectId(project);version.setCatalogHash(HASH);
        org.mockito.Mockito.when(projects.findVersion(project)).thenReturn(Optional.of(version));
        return new cn.lgs.semevosql.clarification.SemanticBindingTargetValidator(projects,lookup());
    }
    @Test void clarificationLoadsOnlyMatchedBodiesAndKeepsGlobalRules() {
        model("orders","orders",1); model("unrelated","unrelated",1);
        jdbc.update("INSERT INTO qw_semantic_metric(project_id,project_version_id,model_code,metric_code,business_name,expression,status) VALUES(?,?,'orders','ordered_amount','下单金额','sum(amount)','ENABLED')",project,project);
        jdbc.update("INSERT INTO qw_semantic_rule(project_id,project_version_id,rule_code,rule_type,business_name,expression,status) VALUES(?,?,'time_required','RUNTIME_CLARIFICATION_POLICY','必须指定时间','require_time_range','ENABLED')",project,project);
        jdbc.update("UPDATE qw_semantic_model SET description=repeat('unrelated body ',1000) WHERE project_id=? AND model_code='unrelated'",project);
        var snapshot = lookup().loadClarificationContext(project,project,"查询下单金额",List.of(),Set.of(),Set.of("金额"));
        assertThat(snapshot.getModels()).extracting(SemanticCatalogSnapshot.Model::getModelCode).containsExactly("orders");
        assertThat(snapshot.getRules()).extracting(SemanticCatalogSnapshot.Rule::getRuleCode).containsExactly("time_required");
        assertThat(reads.records).noneMatch(r->r.name().matches(".*\\.(findModels|findColumns|findMetrics|findDimensions|findRelationships|findGrains|findEnumValues|findRules)$"));
        assertThat(reads.records.stream().filter(r->r.name().endsWith("findBindingTermPage"))).allMatch(r->!r.sql().contains("expression") && !r.sql().contains("SELECT *"));
        reads.records.clear();
        lookup().loadClarificationContext(project,project,"金额",List.of(),Set.of("orders"),Set.of("金额"));
        assertThat(reads.records).noneMatch(r->r.name().endsWith("findBindingTermPage"));
        var unmatched = lookup().loadClarificationContext(project,project,"完全未知的问题",List.of(),Set.of(),Set.of());
        assertThat(unmatched.getModels()).isEmpty();
        assertThat(unmatched.getRules()).hasSize(1);
    }

    @Test void explicitModelMentionDoesNotExpandToUnrelatedGenericMatches() {
        model("orders","orders",1);
        for (int i=0;i<30;i++) model("noise"+i,"noise"+i,1);
        jdbc.update("INSERT INTO qw_semantic_metric(project_id,project_version_id,model_code,metric_code,business_name,expression,status) SELECT ?,?,'noise'||n,'metric_'||n,'其他金额'||n,'sum(amount)','ENABLED' FROM generate_series(0,29) n",project,project);
        var snapshot=lookup().loadClarificationContext(project,project,"orders的金额",List.of(),Set.of(),Set.of("金额"));
        assertThat(snapshot.getModels()).extracting(SemanticCatalogSnapshot.Model::getModelCode).containsExactly("orders");
        assertThatThrownBy(()->lookup().loadClarificationContext(project,project,"统计金额",List.of(),Set.of(),Set.of("金额")))
            .isInstanceOf(SemanticPlanningRejectedException.class).hasMessageContaining("budget");
    }

    @Test void correctionSearchTraversesBeyondTwoHundredWithoutLoadingDefinitions() {
        model("orders","orders",1);
        jdbc.update("INSERT INTO qw_semantic_metric(project_id,project_version_id,model_code,metric_code,business_name,expression,description,status) SELECT ?,?,'orders','metric_'||n,'指标'||n,'sum(amount)',repeat('long irrelevant formula documentation ',100),'ENABLED' FROM generate_series(1,205) n",project,project);
        long cursor=0; var keys=new LinkedHashSet<String>();
        for (int pageNo=0;pageNo<5;pageNo++) {
            var page=lookup().bindingPage(project,project,"METRIC","",cursor,50);
            assertThat(page.terms()).hasSize(pageNo==4?5:50);
            page.terms().forEach(term->assertThat(keys.add(term.assetKey())).isTrue());
            assertThat(page.hasMore()).isEqualTo(pageNo<4);
            if(page.hasMore()) cursor=page.nextAfterId(); else assertThat(page.nextAfterId()).isNull();
        }
        assertThat(keys).hasSize(205).contains("metric_205");
        assertThat(lookup().bindingPage(project,project,"METRIC","指标205",0,50).terms())
            .extracting(SemanticCatalogRepository.BindingTerm::assetKey).containsExactly("metric_205");
        assertThat(lookup().bindingPage(project,project,"METRIC","%",0,50).terms()).isEmpty();
        assertThat(reads.records).allMatch(r->r.name().endsWith("findBindingTermPage") || r.name().endsWith("authoritativeCatalogHash"));
        assertThat(reads.records.stream().filter(r->r.name().endsWith("findBindingTermPage")))
            .allMatch(r->r.rows()<=51 && !r.sql().contains("description") && !r.sql().contains("SELECT *"));
        System.out.println("CORRECTION_PAGING_ACTUAL_READS "+reads.records);
    }

    @Test void correctionSearchKeepsTimeTypeOwnerStatusAndVersionAuthority() {
        model("orders","orders",1); model("hidden","hidden",1);
        jdbc.update("INSERT INTO qw_semantic_column(project_id,project_version_id,model_code,column_name,business_name,role,status) VALUES(?,?,'orders','paid_at','支付时间','TIME','ENABLED'),(?,?,'orders','amount','金额','ATTRIBUTE','ENABLED'),(?,?,'hidden','paid_at','隐藏时间','TIME','ENABLED')",project,project,project,project,project,project);
        jdbc.update("UPDATE qw_semantic_model SET status='DISABLED' WHERE project_id=? AND model_code='hidden'",project);
        assertThat(lookup().bindingPage(project,project,"TIME_COLUMN",null,0,50).terms())
            .extracting(SemanticCatalogRepository.BindingTerm::assetKey).containsExactly("orders:paid_at");
        assertThatThrownBy(()->lookup().bindingPage(project+1,project,"TIME_COLUMN",null,0,50)).isInstanceOf(SemanticPlanningRejectedException.class);
        assertThatThrownBy(()->lookup().bindingPage(project,project,"TIME_COLUMN",null,-1,50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->lookup().bindingPage(project,project,"TIME_COLUMN",null,0,101)).isInstanceOf(IllegalArgumentException.class);
        reads.afterRead=name->{if(name.endsWith("findBindingTermPage")) {reads.afterRead=null;jdbc.update("UPDATE qw_project_version SET catalog_hash=? WHERE id=?","b".repeat(64),project);}};
        assertThatThrownBy(()->lookup().bindingPage(project,project,"TIME_COLUMN",null,0,50)).hasMessageContaining("identity changed");
    }

    @Test void runtimeBindingServicePreservesExplicitBusinessTermsAndSkipsCatalogWithoutPreferences() {
        model("orders","orders",1);model("refunds","refunds",1);
        jdbc.update("INSERT INTO qw_semantic_metric(project_id,project_version_id,model_code,metric_code,business_name,expression,status) VALUES(?,?,'orders','amount','金额','sum(amount)','ENABLED'),(?,?,'refunds','refund_amount','退款金额','sum(refund_amount)','ENABLED')",project,project,project,project);
        var aliases=org.mockito.Mockito.mock(cn.lgs.semevosql.clarification.ProjectSemanticAliasService.class);
        var preferences=org.mockito.Mockito.mock(cn.lgs.semevosql.clarification.UserSemanticPreferenceService.class);
        var bindings=new cn.lgs.semevosql.clarification.RuntimeSemanticBindingService(preferences,aliases,lookup(),null);
        reads.records.clear();
        assertThat(bindings.resolve(project,project,"owner","本月订单").empty()).isTrue();
        assertThat(reads.records).isEmpty();
        var alias=new cn.lgs.semevosql.clarification.ProjectSemanticAliasService.ProjectSemanticAlias(
                1L,project,project,"金额","金额","METRIC","amount","金额","fixture","ACTIVE",null,null);
        org.mockito.Mockito.when(aliases.applicable(project,project,"退款金额")).thenReturn(List.of(alias));
        assertThat(bindings.resolve(project,project,"owner","退款金额").empty()).isTrue();
        var explicit=bindings.explicit(project,project,"金额","METRIC","refund_amount","退款金额");
        assertThat(explicit.additionalPhysicalTables()).containsExactly("refunds");
        assertThat(explicit.hints().strictAssetBinding()).isTrue();
        assertThat(reads.records).noneMatch(r->r.name().matches(".*\\.(findModels|findColumns|findMetrics|findDimensions|findRelationships|findGrains|findEnumValues|findRules)$"));
    }

    @Test void runtimeBindingsLoadOwnersAndExplicitLongerTermsButNeverUnrelatedModelBodies() {
        model("orders","orders",1);model("refunds","refunds",1);model("noise","noise",2);
        jdbc.update("INSERT INTO qw_semantic_metric(project_id,project_version_id,model_code,metric_code,business_name,expression,status) VALUES(?,?,'orders','amount','金额','sum(amount)','ENABLED'),(?,?,'refunds','refund_amount','退款金额','sum(refund_amount)','ENABLED'),(?,?,'noise','other','无关内容','sum(other)','ENABLED')",project,project,project,project,project,project);
        var refs=List.of(new SemanticCatalogLookupService.BindingRef(new SemanticCatalogLookupService.AssetRef("METRIC","amount"),"金额"),
                new SemanticCatalogLookupService.BindingRef(new SemanticCatalogLookupService.AssetRef("METRIC","removed"),"旧称"));
        var slice=lookup().loadRuntimeBindings(project,project,refs,"请统计退 款金 额");
        assertThat(slice.getModels()).extracting(SemanticCatalogSnapshot.Model::getModelCode).containsExactlyInAnyOrder("orders","refunds");
        assertThat(reads.records).noneMatch(r->r.name().matches(".*\\.(findModels|findColumns|findMetrics|findDimensions|findRelationships|findGrains|findEnumValues|findRules)$"));
        assertThat(reads.records.stream().filter(r->r.name().endsWith("findBindingTermPage"))).allMatch(r->!r.sql().contains("expression") && !r.sql().contains("SELECT *"));
        reads.afterRead=name->{if(name.endsWith("findBindingTermPage")) {reads.afterRead=null;jdbc.update("UPDATE qw_project_version SET catalog_hash=? WHERE id=?","b".repeat(64),project);}};
        assertThatThrownBy(()->lookup().loadRuntimeBindings(project,project,refs,"退款金额"))
                .isInstanceOf(SemanticPlanningRejectedException.class).hasMessageContaining("identity changed");
    }

    @Test void durableBindingUsesExactIdentityAndRejectsDisabledOwningModel() {
        model("orders","orders",1);model("unrelated","other_table",1);
        jdbc.update("INSERT INTO qw_semantic_metric(project_id,project_version_id,model_code,metric_code,business_name,expression,status) VALUES(?,?,'orders','amount','金额','sum(amount)','ENABLED')",project,project);
        var validator=bindingValidator();
        validator.requireAsset(project,project,"METRIC","amount");
        assertThat(reads.records).noneMatch(r->r.name().matches(".*\\.(findModels|findColumns|findMetrics|findDimensions|findRelationships|findGrains|findEnumValues|findRules)$"));
        assertThatThrownBy(()->validator.requireAsset(project+1,project,"METRIC","amount")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->validator.requireAsset(project,project,"METRIC","missing")).isInstanceOf(IllegalArgumentException.class);
        jdbc.update("UPDATE qw_semantic_model SET status='DISABLED' WHERE project_id=? AND model_code='orders'",project);
        assertThatThrownBy(()->validator.requireAsset(project,project,"METRIC","amount")).isInstanceOf(SemanticPlanningRejectedException.class);
    }
    @Test void durableBindingRejectsAuthorityChangesDuringTargetResolution() {
        model("orders","orders",1);
        jdbc.update("INSERT INTO qw_semantic_column(project_id,project_version_id,model_code,column_name,business_name,role,status) VALUES(?,?,'orders','paid_at','支付时间','TIME','ENABLED')",project,project);
        var validator=bindingValidator();
        reads.afterRead=name->{
            if(name.endsWith("findAssetOwners")) {
                reads.afterRead=null;
                jdbc.update("UPDATE qw_project_version SET catalog_hash=? WHERE id=?","b".repeat(64),project);
            }
        };
        assertThatThrownBy(()->validator.requireAsset(project,project,"TIME_COLUMN","orders:paid_at"))
            .isInstanceOf(SemanticPlanningRejectedException.class).hasMessageContaining("identity changed");
    }
    @Test void legacyTableOnlyRecallMustResolveEveryRequestedTable() {
        model("orders","orders",1);
        assertThatThrownBy(() -> service.candidates(project,project,List.of("orders","missing"),List.of()))
            .isInstanceOf(SemanticPlanningRejectedException.class).hasMessageContaining("identify");
    }
    @Test void legacyTableOnlyRecallMustNotChooseBetweenSameNamedTables() {
        model("orders","orders",1); model("other_orders","orders",2);
        assertThatThrownBy(() -> service.candidates(project,project,List.of("orders"),List.of()))
            .isInstanceOf(SemanticPlanningRejectedException.class);
    }
    @Test void sharedReadBoundaryIncludesLegacyResolutionAndRejectsIdentityChange() {
        model("orders","orders",1);
        var reader = new SemanticCatalogReadService(repository);
        assertThat(reader.getForPhysicalTables(project,project,List.of("orders")).getModels())
            .extracting(SemanticCatalogSnapshot.Model::getModelCode).containsExactly("orders");
        reads.afterRead = name -> {
            if(name.endsWith("findModelsForTables")) {
                reads.afterRead=null;
                jdbc.update("UPDATE qw_project_version SET catalog_hash=? WHERE id=?","b".repeat(64),project);
            }
        };
        assertThatThrownBy(() -> reader.getForPhysicalTables(project,project,List.of("orders")))
            .isInstanceOf(SemanticPlanningRejectedException.class).hasMessageContaining("identity changed");
    }
    @Test void assetAndPageReadsRecheckAuthorityAfterTheWholeOperation() {
        model("orders","orders",1);
        reads.afterRead = name -> {
            if(name.endsWith("findAssetOwners")) {
                reads.afterRead=null;
                jdbc.update("UPDATE qw_project_version SET catalog_hash=? WHERE id=?","b".repeat(64),project);
            }
        };
        assertThatThrownBy(() -> lookup().loadAssets(project,project,HASH,
                List.of(new SemanticCatalogLookupService.AssetRef("MODEL","orders"))))
            .isInstanceOf(SemanticPlanningRejectedException.class).hasMessageContaining("identity changed");
        reads.afterRead = name -> {
            if(name.endsWith("findModelSummaryPage")) {
                reads.afterRead=null;
                jdbc.update("UPDATE qw_project_version SET catalog_hash=? WHERE id=?","c".repeat(64),project);
            }
        };
        assertThatThrownBy(() -> lookup().modelPage(project,project,"b".repeat(64),null,1))
            .isInstanceOf(SemanticPlanningRejectedException.class).hasMessageContaining("identity changed");
    }
    @Test void blueprintLegacyEntryCannotLoadAnEntireAmbiguousCatalog() {
        model("orders","orders",1);model("other_orders","orders",2);
        var application = new SemanticCatalogApplicationService(repository,new SemanticCatalogReadService(repository),
            null,null,null,null,null,null,null,null);
        assertThatThrownBy(() -> application.buildBlueprint(project,project,"orders",List.of("orders"),
            cn.lgs.semevosql.learning.QueryCaseHints.empty()))
            .isInstanceOf(SemanticPlanningRejectedException.class).hasMessageContaining("uniquely identify");
        assertThat(reads.records).noneMatch(r -> r.name().matches(".*\\.(findModels|findColumns|findMetrics|findDimensions|findRelationships|findGrains|findEnumValues|findRules)$"));
    }
    @Test void explicitAssetsResolveOwnersInBatchesAndKeepBothRelationshipEnds() {
        model("orders","orders",1);model("customers","customers",1);model("noise","orders",2);
        relationship("buyer","orders","customers");
        jdbc.update("INSERT INTO qw_semantic_metric(project_id,project_version_id,model_code,metric_code,business_name,expression,status) VALUES(?,?,'orders','amount','amount','sum(amount)','ENABLED'),(?,?,'orders','count','count','count(*)','ENABLED')",project,project,project,project);
        var refs=List.of(new SemanticCatalogLookupService.AssetRef("METRIC","amount"),new SemanticCatalogLookupService.AssetRef("METRIC","count"),new SemanticCatalogLookupService.AssetRef("RELATIONSHIP","buyer"));
        var result=lookup().loadAssets(project,project,HASH,refs);
        assertThat(result.getModels()).extracting(SemanticCatalogSnapshot.Model::getModelCode).containsExactlyInAnyOrder("orders","customers");
        assertThat(result.getMetrics()).hasSize(2);
        assertThat(result.getRelationships()).hasSize(1);
        assertThat(reads.records.stream().filter(r->r.name().endsWith("findAssetOwners"))).hasSize(2);
        assertThat(reads.records).noneMatch(r->r.name().matches(".*\\.(findModels|findColumns|findMetrics|findDimensions|findRelationships|findGrains|findEnumValues|findRules)$"));
        var ownerReads=reads.records.stream().filter(r->r.name().endsWith("findAssetOwners")).toList();
        assertThat(ownerReads).allMatch(r->!r.sql().contains("SELECT *") && !r.sql().contains("description"));
        System.out.println("EXPLICIT_ASSET_OWNER_READS "+ownerReads);
        assertThatThrownBy(()->lookup().loadAssets(project+1,project,HASH,refs)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->lookup().loadAssets(project,project,"b".repeat(64),refs)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->lookup().loadAssets(project,project,HASH,List.of(new SemanticCatalogLookupService.AssetRef("METRIC","missing"))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown or disabled");
        jdbc.update("UPDATE qw_semantic_model SET status='DISABLED' WHERE project_id=? AND model_code='customers'",project);
        assertThatThrownBy(()->lookup().loadAssets(project,project,HASH,refs)).isInstanceOf(IllegalStateException.class);
    }
    @Test void globalRuleOnlyLookupDoesNotLoadUnrelatedModels() {
        model("orders","orders",1);
        jdbc.update("INSERT INTO qw_semantic_rule(project_id,project_version_id,rule_code,rule_type,business_name,expression,status) VALUES(?,?,'mandatory','MANDATORY_FILTER','mandatory','1=1','ENABLED')",project,project);
        var result=lookup().loadAssets(project,project,HASH,List.of(new SemanticCatalogLookupService.AssetRef("RULE","mandatory")));
        assertThat(result.getModels()).isEmpty();assertThat(result.getRules()).hasSize(1);
        assertThat(reads.records).hasSize(4);
        assertThat(reads.records).noneMatch(r->r.name().endsWith("findModelsForModels"));
    }
    @Test void compoundAssetIdentityAndTimeRoleAreResolvedExactly() {
        model("orders","orders",1);
        jdbc.update("INSERT INTO qw_semantic_column(project_id,project_version_id,model_code,column_name,business_name,role,status) VALUES(?,?,'orders','paid_at','paid at','TIME','ENABLED'),(?,?,'orders','status','status','DIMENSION','ENABLED')",project,project,project,project);
        jdbc.update("INSERT INTO qw_semantic_enum_value(project_id,project_version_id,model_code,column_name,value_code,business_name,status) VALUES(?,?,'orders','status','PAID','paid','ENABLED')",project,project);
        var refs=List.of(new SemanticCatalogLookupService.AssetRef("TIME_COLUMN","orders:paid_at"),new SemanticCatalogLookupService.AssetRef("ENUM_VALUE","orders:status:PAID"),new SemanticCatalogLookupService.AssetRef("COLUMN","orders:status"));
        var result=lookup().loadAssets(project,project,HASH,refs);
        assertThat(result.getModels()).hasSize(1);assertThat(result.getColumns()).hasSize(2);assertThat(result.getEnumValues()).hasSize(1);
        assertThatThrownBy(()->lookup().loadAssets(project,project,HASH,List.of(new SemanticCatalogLookupService.AssetRef("TIME_COLUMN","orders:status"))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.findAssetOwners(project,project,"MODEL",Set.of("orders' OR 1=1 --"))).isEmpty();
        assertThat(repository.findAssetOwners(project,project,"not_a_table",Set.of("orders"))).isEmpty();
    }

    @Test void summaryPagesAreBoundedStableAndDoNotReadDescriptions() {
        for(String code:List.of("a","b","c","d","e")) model(code,code,1);
        jdbc.update("UPDATE qw_semantic_model SET description=repeat('long unrelated text ',1000) WHERE project_id=?",project);
        var first=lookup().modelPage(project,project,HASH,null,2);
        assertThat(first.models()).extracting(SemanticCatalogLookupService.ModelSummary::modelCode).containsExactly("a","b");
        assertThat(first.hasMore()).isTrue();assertThat(first.nextModelCode()).isEqualTo("b");
        var second=lookup().modelPage(project,project,HASH,first.nextModelCode(),2);
        assertThat(second.models()).extracting(SemanticCatalogLookupService.ModelSummary::modelCode).containsExactly("c","d");
        var last=lookup().modelPage(project,project,HASH,second.nextModelCode(),2);
        assertThat(last.models()).extracting(SemanticCatalogLookupService.ModelSummary::modelCode).containsExactly("e");
        assertThat(last.hasMore()).isFalse();assertThat(last.nextModelCode()).isNull();
        assertThat(lookup().modelPage(project,project,HASH,"z",2).models()).isEmpty();
        var pageReads=reads.records.stream().filter(r->r.name().endsWith("findModelSummaryPage")).toList();
        assertThat(pageReads).hasSize(4).allMatch(r->r.rows()<=3 && !r.sql().contains("description") && !r.sql().contains("SELECT *"));
        assertThatThrownBy(()->lookup().modelPage(project,project,HASH,null,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->lookup().modelPage(project,project,HASH,null,101)).isInstanceOf(IllegalArgumentException.class);
        jdbc.update("UPDATE qw_project_version SET catalog_hash=? WHERE id=?","b".repeat(64),project);
        assertThatThrownBy(()->lookup().modelPage(project,project,HASH,"b",2)).isInstanceOf(IllegalStateException.class);
    }
    @Test void nonUniqueAssetKeysMustNotSilentlyPickFirstModel() {
        model("orders","orders",1);model("customers","customers",1);
        jdbc.update("INSERT INTO qw_semantic_grain(project_id,project_version_id,model_code,grain_code,key_columns,status) VALUES(?,?,'orders','daily','[\"id\"]','ENABLED'),(?,?,'customers','daily','[\"id\"]','ENABLED')",project,project,project,project);
        assertThatThrownBy(()->lookup().loadAssets(project,project,HASH,List.of(new SemanticCatalogLookupService.AssetRef("GRAIN","daily"))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Ambiguous");
    }

    @Test void scopedReadsPreserveGlobalRulesAndDoNotLoadOtherDatasourceModels() {
        model("orders","orders",1); model("other","orders",2);
        jdbc.update("INSERT INTO qw_semantic_rule(project_id,project_version_id,rule_code,rule_type,business_name,expression,status) VALUES(?,?,'required','MANDATORY_FILTER','required','1=1','ENABLED')",project,project);
        var result = candidates("orders","orders");
        assertThat(result.modelCodes()).containsExactly("orders");
        assertThat(result.catalogHash()).isEqualTo(HASH);
        assertThat(result.mandatoryGovernanceRules()).extracting(SemanticCatalogSnapshot.Rule::getRuleCode).containsExactly("required");
        assertThat(reads.records).noneMatch(r -> r.name().matches(".*\\.(findModels|findColumns|findMetrics|findDimensions|findRelationships|findGrains|findEnumValues|findRules)$"));
        assertThat(repository.findModelsByCodes(project+1,project,Set.of("orders"))).isEmpty();
        assertThat(repository.authoritativeCatalogHash(project+1,project)).isNull();
    }
    @Test void twoHopIntermediateNodesAndCyclesAreResolvedInBatches() {
        model("orders","orders",1);model("bridge","bridge",1);model("customers","customers",1);
        relationship("ob","orders","bridge");relationship("bc","bridge","customers");relationship("bo","bridge","orders");
        var result=candidates("orders","orders");
        assertThat(result.modelCodes()).containsExactlyInAnyOrder("orders","bridge","customers");
        assertThat(result.relationships()).hasSize(3);
        assertThat(reads.records.stream().filter(r -> r.name().endsWith("findRelationshipsTouching")).count()).isEqualTo(2);
        // The four new, model-scoped definition/dictionary/migration reads stay independent of project size.
        assertThat(reads.records).hasSize(16);
    }
    @Test void unrelatedExpansionDoesNotIncreaseSelectedRowsOrTextRead() {
        model("orders","orders",1);
        var before = candidates("orders","orders");
        int beforeRows=reads.records.stream().mapToInt(Read::rows).sum();
        long beforeBytes=reads.records.stream().mapToLong(Read::bytes).sum();
        jdbc.update("""
            INSERT INTO qw_semantic_model(project_id,project_version_id,datasource_id,model_code,physical_table,business_name,description,status)
            SELECT ?,?,1,'noise'||n,'noise'||n,'noise'||n,repeat('synthetic unrelated description ',70),'ENABLED'
            FROM generate_series(1,5000) n
            """,project,project);
        jdbc.update("""
            INSERT INTO qw_semantic_column(project_id,project_version_id,model_code,column_name,business_name,description,status)
            SELECT ?,?,'noise'||n,'id','noise',repeat('unrelated column ',100),'ENABLED' FROM generate_series(1,5000) n
            """,project,project);
        jdbc.update("""
            INSERT INTO qw_semantic_metric(project_id,project_version_id,model_code,metric_code,business_name,expression,description,status)
            SELECT ?,?,'noise'||n,'noise_metric'||n,'noise','sum(id)',repeat('unrelated metric ',100),'ENABLED' FROM generate_series(1,5000) n
            """,project,project);
        jdbc.execute("ANALYZE qw_semantic_model");
        var after=candidates("orders","orders");
        assertThat(after.modelCodes()).isEqualTo(before.modelCodes());
        assertThat(reads.records.stream().mapToInt(Read::rows).sum()).isEqualTo(beforeRows);
        assertThat(reads.records.stream().mapToLong(Read::bytes).sum()).isEqualTo(beforeBytes);
        assertThat(reads.records).hasSize(15);
        String explain=jdbc.queryForObject("EXPLAIN (ANALYZE,BUFFERS,FORMAT JSON) SELECT * FROM qw_semantic_model WHERE project_id=? AND project_version_id=? AND status='ENABLED' AND model_code IN (?)",String.class,project,project,"orders");
        System.out.println("SCOPED_CATALOG_5001_MODEL_ACTUAL_PLAN "+explain);
        System.out.println("SCOPED_CATALOG_READS "+reads.records);
    }
    @Test void disabledRequiredDependencyAndUnknownSeedCannotProducePartialCandidates() {
        model("orders","orders",1);model("bridge","bridge",1);relationship("ob","orders","bridge");
        jdbc.update("UPDATE qw_semantic_model SET status='DISABLED' WHERE project_version_id=? AND model_code='bridge'",project);
        assertThatThrownBy(() -> candidates("orders","orders")).isInstanceOf(SemanticPlanningRejectedException.class).hasMessageContaining("missing or disabled");
        assertThatThrownBy(() -> candidates("unknown","orders")).isInstanceOf(SemanticPlanningRejectedException.class).hasMessageContaining("missing or disabled");
    }
    @Test void historyReadsEverySelectedIdentityWithoutAnArbitraryFourModelTruncation() {
        Set<String> codes=new LinkedHashSet<>();
        for(int n=0;n<6;n++){model("m"+n,"t"+n,1);codes.add("m"+n);}
        reads.records.clear();
        assertThat(service.physicalTablesForHistoricalModels(project,project,codes)).hasSize(6);
        assertThat(reads.records).hasSize(1);
        assertThat(reads.records.get(0).rows()).isEqualTo(6);
    }
    @Test void detailsKeepColumnPolicyMetricDimensionEnumAndGrainContracts() {
        model("orders","orders",1);
        jdbc.update("INSERT INTO qw_semantic_column(project_id,project_version_id,model_code,column_name,business_name,data_type,role,nullable_flag,allow_send_to_llm,status) VALUES(?,?,'orders','paid_at','paid time','timestamp','TIME',false,true,'ENABLED'),(?,?,'orders','secret_note','restricted','text','ATTRIBUTE',true,false,'ENABLED')",project,project,project,project);
        jdbc.update("INSERT INTO qw_semantic_metric(project_id,project_version_id,model_code,metric_code,business_name,expression,status) VALUES(?,?,'orders','amount','amount','sum(paid_amount)','ENABLED')",project,project);
        jdbc.update("INSERT INTO qw_semantic_dimension(project_id,project_version_id,model_code,dimension_code,business_name,column_name,status) VALUES(?,?,'orders','paid_day','paid day','paid_at','ENABLED')",project,project);
        jdbc.update("INSERT INTO qw_semantic_enum_value(project_id,project_version_id,model_code,column_name,value_code,business_name,aliases,status) VALUES(?,?,'orders','status','PAID','paid','[\"settled\",\"paid\"]'::jsonb,'ENABLED')",project,project);
        jdbc.update("INSERT INTO qw_semantic_grain(project_id,project_version_id,model_code,grain_code,key_columns,status) VALUES(?,?,'orders','order_id','[\"id\",\"tenant_id\"]'::jsonb,'ENABLED')",project,project);
        var detail=repository.loadModelSlice(project,project,Set.of("orders"));
        assertThat(detail.getColumns()).hasSize(2);
        assertThat(detail.getColumns().get(0).getNullable()).isFalse();
        assertThat(detail.getMetrics()).extracting(SemanticCatalogSnapshot.Metric::getMetricCode).containsExactly("amount");
        assertThat(detail.getDimensions()).extracting(SemanticCatalogSnapshot.Dimension::getDimensionCode).containsExactly("paid_day");
        assertThat(detail.getEnumValues().get(0).getAliases()).isEqualTo("settled,paid");
        assertThat(detail.getGrains().get(0).getKeyColumns()).isEqualTo("id,tenant_id");
        var result=candidates("orders","orders");
        assertThat(result.timeColumns()).extracting(SemanticCatalogSnapshot.Column::getColumnName).containsExactly("paid_at");
        assertThat(result.filterableColumns()).isEmpty();
    }
    @Test void summariesReadNoLongDescriptionsAndPreserveLargeNamespaceSentinel() {
        for(int n=0;n<4;n++) model("m"+n,"t"+n,1);
        jdbc.update("UPDATE qw_semantic_model SET description=repeat('long synthetic document ',1000) WHERE project_version_id=?",project);
        var summaries=repository.findEnabledModelSummaries(project,project,3);
        assertThat(summaries).hasSize(3).allMatch(m -> m.getDescription()==null && m.getEvidence()==null);
        assertThat(SemanticCatalogApplicationService.boundedCatalogFallback(SemanticCatalogSnapshot.builder().models(summaries).build(),2).hits()).isEmpty();
        assertThat(repository.enabledDatasourceIds(project,project)).containsExactly(1);
        assertThat(repository.enabledPhysicalTables(project,project)).containsExactlyInAnyOrder("t0","t1","t2","t3");
    }
    @Test void runtimeDetailsRejectStaleIdentityAndRequiredModelsThatBecameUnavailable() {
        model("orders","orders",1);
        var runtime=new cn.lgs.semevosql.semantic.application.SemanticCatalogReadService(repository);
        assertThat(runtime.getForModels(project,project,Set.of("orders"),HASH).getModels()).hasSize(1);
        assertThatThrownBy(() -> runtime.getForModels(project+1,project,Set.of("orders"),HASH)).hasMessageContaining("frozen catalog");
        assertThatThrownBy(() -> runtime.getForModels(project,project,Set.of("orders"),"stale")).hasMessageContaining("frozen catalog");
        jdbc.update("UPDATE qw_semantic_model SET status='DISABLED' WHERE project_version_id=?",project);
        assertThatThrownBy(() -> runtime.getForModels(project,project,Set.of("orders"),HASH)).hasMessageContaining("missing or disabled");
    }
    @Test void authorityIsRecheckedEvenWithMybatisSessionCachingEnabled() {
        model("orders","orders",1);
        session.getConfiguration().setLocalCacheScope(LocalCacheScope.SESSION);
        reads.afterRead=name -> {
            if(name.endsWith("findModelsForModels")) {
                reads.afterRead=null;
                jdbc.update("UPDATE qw_project_version SET catalog_hash=? WHERE id=?","b".repeat(64),project);
            }
        };
        assertThatThrownBy(() -> candidates("orders","orders")).isInstanceOf(SemanticPlanningRejectedException.class)
            .hasMessageContaining("identity changed");
    }
    record Read(String name,String sql,int rows,long bytes) {}
    @Intercepts(@Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}))
    static class ReadTrace implements Interceptor {
        final List<Read> records = new ArrayList<>();
        java.util.function.Consumer<String> afterRead;
        @Override public Object intercept(Invocation invocation) throws Throwable {
            var statement=(MappedStatement)invocation.getArgs()[0];
            Object result=invocation.proceed();
            var rows=(List<?>)result;
            records.add(new Read(statement.getId(),statement.getBoundSql(invocation.getArgs()[1]).getSql().replaceAll("\\s+"," "),rows.size(),
                cn.lgs.semevosql.util.JsonUtil.getObjectMapper().writeValueAsBytes(result).length));
            if(afterRead!=null) afterRead.accept(statement.getId());
            return result;
        }
    }
}
