/*
 * Copyright 2026 the original author or authors.
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
package cn.lgs.semevosql.clarification;

import cn.lgs.semevosql.project.domain.SemanticProjectRepository;
import cn.lgs.semevosql.semantic.application.FrozenPersonalMetric;
import cn.lgs.semevosql.semantic.application.SemanticCatalogLookupService;
import cn.lgs.semevosql.semantic.domain.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Disposable DB contract fixtures. They do not manufacture real model or acceptance use records. */
@Testcontainers
class PersonalPublicDefinitionPostgresIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;static TransactionTemplate tx;static PersonalSemanticDefinitionStore definitions;static long next=9800;
    long project;SemanticBindingTargetValidator targets;SemanticProjectRepository projects;PersonalPublicDefinitionService service;
    PersonalSemanticDefinitionStore.Definition original;SemanticCatalogSnapshot old,current;
    SemanticCatalogLookupService catalogLookup;
    @BeforeAll static void database() {
        var ds=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").load().migrate();
        jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));definitions=new PersonalSemanticDefinitionStore(jdbc);
        jdbc.execute("CREATE TABLE reminder_orders(amount numeric(12,2),created_at timestamp)");
        jdbc.update("INSERT INTO reminder_orders VALUES(10,'2026-01-02'),(20,'2026-01-03')");
    }
    @BeforeEach void setup() {
        project=++next;
        jdbc.update("INSERT INTO qw_project(id,project_code,name,business_domain,status,created_by) VALUES(?,?,'Synthetic reminders','test','ACTIVE','fixture')",project,"reminder-"+project);
        jdbc.update("INSERT INTO qw_project_version(id,project_id,version_no,version_number,status,analysis_status,semantic_major,semantic_minor,semantic_patch) VALUES(?,?,1,'1.0.0','DRAFT','COMPLETED',1,0,0)",project,project);
        old=catalog("SUM(amount)");current=catalog("SUM(amount) * 2");
        var capture=PersonalDefinitionSnapshot.capture(old,"METRIC","public_amount");
        original=tx.execute(ignored->definitions.confirm(new PersonalSemanticDefinitionStore.Confirmation(project,"alice","我的金额","全部订单金额合计，按创建时间，单位元。",
            "METRIC","public_amount","我的金额","ASSET_CONFIRMATION",UUID.randomUUID().toString(),project,capture.snapshot(),capture.dependencyFingerprint(),PersonalSemanticDefinitionStore.Sharing.PRIVATE,0)));
        targets=mock(SemanticBindingTargetValidator.class);projects=mock(SemanticProjectRepository.class);
        doAnswer(ignored->{jdbc.queryForObject("SELECT id FROM qw_project WHERE id=? FOR UPDATE",Long.class,project);return null;}).when(projects).lockProject(project);
        when(targets.activeVersionId(project)).thenReturn(project);
        when(targets.captureAsset(eq(project),anyLong(),eq("METRIC"),eq("public_amount"))).thenAnswer(ignored->PersonalDefinitionSnapshot.capture(current,"METRIC","public_amount"));
        catalogLookup=mock(SemanticCatalogLookupService.class);
        when(catalogLookup.loadRuntimeBindings(eq(project),anyLong(),anyCollection(),anyCollection(),anyString())).thenAnswer(ignored->current);
        service=new PersonalPublicDefinitionService(jdbc,definitions,targets,projects,catalogLookup);
    }
    private SemanticCatalogSnapshot catalog(String expression) {
        return SemanticCatalogSnapshot.builder().projectId(project).projectVersionId(project)
            .models(List.of(SemanticCatalogSnapshot.Model.builder().modelCode("orders").physicalTable("reminder_orders").datasourceId(1).businessName("合成订单").status(SemanticAssetStatus.ENABLED).build()))
            .columns(List.of("amount","created_at").stream().map(name->SemanticCatalogSnapshot.Column.builder().modelCode("orders").columnName(name)
                .dataType(name.equals("amount")?"DECIMAL(12,2)":"TIMESTAMP").allowAggregation(true).allowFilter(true).allowProjection(true).allowSendToLlm(true).status(SemanticAssetStatus.ENABLED).build()).toList())
            .metrics(new ArrayList<>(List.of(SemanticCatalogSnapshot.Metric.builder().metricCode("public_amount").modelCode("orders").businessName("项目金额")
                .description("全部订单金额的已确认定义。").expression(expression).aggregation("EXPRESSION").timeColumn("created_at").unit("元").status(SemanticAssetStatus.ENABLED).build()))).build();
    }
    private PersonalPublicDefinitionService.Difference difference() {
        return service.next(project,project,"alice","查看我的金额","unused").orElseThrow();
    }
    private SemanticBlueprint.BindingDependency selected() {
        return SemanticBlueprint.BindingDependency.builder().source("USER").scope("USER").principalId(original.principal())
            .sourceRecordId(original.preferenceId()).sourceRevision(original.revision()).sourceContentHash(original.contentHash())
            .assetType(original.assetType()).assetKey(original.assetKey()).definitionText(original.text())
            .dependencyFingerprint(original.dependencyFingerprint()).build();
    }
    @Test void publicSelectionDoesNotPromptOrAlterDefaultButActualPrivateSelectionDoes() {
        assertTrue(service.nextSelected(project,project,"alice",List.of(),"public-run").isEmpty());
        assertEquals(original,definitions.current(original.preferenceId()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_personal_public_choice WHERE preference_id=?",Integer.class,original.preferenceId()));
        assertEquals(original,service.nextSelected(project,project,"alice",List.of(selected()),"private-run").orElseThrow().personal());
        var forged=selected();forged.setPrincipalId("bob");
        assertThrows(SecurityException.class,()->service.nextSelected(project,project,"alice",List.of(forged),"forged-run"));
        var invalid=selected();invalid.setDefinitionText("fake");
        assertThrows(SecurityException.class,()->service.nextSelected(project,project,"alice",List.of(invalid),"forged-run"));
    }
    @Test void selectedSoftRecallIsCheckedEvenWithoutItsExactPhraseAndKeepAcknowledgesTheExactPair() {
        var q=question();
        assertTrue(service.next(project,project,"alice","统计全部订单合计",q.runId()).isEmpty());
        assertTrue(service.nextSelected(project,project,"alice",List.of(selected()),q.runId()).isPresent());
        tx.executeWithoutResult(ignored->service.answer(q,answer("KEEP_PERSONAL")));
        assertTrue(service.nextSelected(project,project,"alice",List.of(selected()),q.runId()).isEmpty());
        current.getMetrics().get(0).setExpression("SUM(amount) * 3");
        assertTrue(service.nextSelected(project,project,"alice",List.of(selected()),q.runId()).isPresent());
    }
    @Test void interactivePreviewPreservesOldCalculationButCannotBypassPhysicalAuthorizationOrStrictCallers() {
        var reader=mock(cn.lgs.semevosql.semantic.application.SemanticCatalogReadService.class);
        when(reader.getForModels(project,project,Set.of("orders"))).thenAnswer(ignored->current);
        var overlay=new cn.lgs.semevosql.semantic.application.PersonalDefinitionCatalogOverlay(definitions,reader);
        overlay.publicDefinitions(service);
        var reference=selected();reference.setScope("USER_DEFAULT_CANDIDATE");
        assertThrows(IllegalStateException.class,()->overlay.prepare(project,project,"alice",List.of(reference)));
        var preview=overlay.prepare(project,project,"alice",List.of(reference),true);
        assertEquals("p_"+original.preferenceId()+"_1",preview.metrics().get(0).getMetricCode());
        assertEquals(new java.math.BigDecimal("30.00"),jdbc.queryForObject("SELECT "+preview.metrics().get(0).getExpression()+" FROM reminder_orders",java.math.BigDecimal.class));
        assertTrue(preview.selected(cn.lgs.semevosql.learning.QueryCaseHints.empty(),List.of()).isEmpty());
        current.getColumns().get(0).setAllowSendToLlm(false);
        assertThrows(SecurityException.class,()->overlay.prepare(project,project,"alice",List.of(reference),true));
    }
    @Test void runtimeDefaultsAreCandidatesWhileStrictAndExplicitBindingsKeepTheirContracts() {
        var preferences=mock(UserSemanticPreferenceService.class);
        var aliases=mock(ProjectSemanticAliasService.class);
        var preference=new UserSemanticPreferenceService.UserSemanticPreference(original.preferenceId(),project,"alice","我的金额",
            "我的金额","METRIC","public_amount","我的金额",0,0,0,false,false,null,null,null,1);
        when(preferences.applicable(project,"alice","我的金额")).thenReturn(List.of(preference));
        when(preferences.definition(original.preferenceId(),1)).thenReturn(original);
        when(preferences.findById(original.preferenceId())).thenReturn(Optional.of(preference));
        when(catalogLookup.loadCurrentAssets(eq(project),eq(project),anyCollection())).thenReturn(current);
        var runtime=new RuntimeSemanticBindingService(preferences,aliases,catalogLookup,mock(cn.lgs.semevosql.run.RunExecutionFenceService.class));
        runtime.publicDefinitions(service);
        assertThrows(IllegalStateException.class,()->runtime.resolve(project,project,"alice","我的金额"));
        var candidates=runtime.planningCandidates(project,project,"alice","我的金额",Set.of());
        assertEquals(1,candidates.bindings().size());assertTrue(candidates.hints().emptyHints());
        assertEquals(original.revision(),candidates.bindings().get(0).sourceRevision());
        var explicit=runtime.explicit(project,project,"我的金额","METRIC","public_amount","我的金额","USER",original.preferenceId(),"alice");
        assertTrue(explicit.hints().strictAssetBinding());
        assertEquals(Set.of("p_"+original.preferenceId()+"_1"),explicit.hints().metricCodes());
    }
    private RuntimeClarification question() {
        String run=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO qw_query_run(run_id,run_type,thread_id,attempt_id,status,idempotency_key,project_id,project_version_id) VALUES(?,'INTERACTIVE_QUERY',?,?,'WAITING_HUMAN',?,?,?)",run,run,run,run,project,project);
        jdbc.update("INSERT INTO qw_runtime_clarification(clarification_id,run_id,question,options_json,status,asset_type,raw_expression,revision) VALUES(?,?,'Synthetic immutable reminder','[]','PENDING',?,'我的金额',0)",id,run,PersonalPublicDefinitionService.TYPE);
        var q=RuntimeClarification.builder().clarificationId(id).runId(run).rawExpression("我的金额").assetType(PersonalPublicDefinitionService.TYPE).build();
        tx.executeWithoutResult(ignored->service.freeze(id,difference()));return q;
    }
    private RuntimeClarificationService.AnswerCommand answer(String option) {
        return new RuntimeClarificationService.AnswerCommand(0,UUID.randomUUID().toString(),option,null,SemanticBindingScope.QUERY,"alice");
    }
    @Test void keepRetainsMeaningHistoryAndAcknowledgesOnlyThisCombination() {
        var q=question();var before=definitions.current(original.preferenceId());
        tx.executeWithoutResult(ignored->service.answer(q,answer("KEEP_PERSONAL")));
        assertEquals(before,definitions.current(original.preferenceId()));
        assertTrue(service.next(project,project,"alice","我的金额",q.runId()).isEmpty());
        current.getMetrics().get(0).setDescription("无关展示说明修改");current.setProjectVersionId(project+100);
        assertTrue(service.next(project,project,"alice","我的金额",q.runId()).isEmpty());
        current.getMetrics().get(0).setExpression("SUM(amount) * 3");
        assertTrue(service.next(project,project,"alice","我的金额",q.runId()).isPresent());
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_personal_public_choice WHERE preference_id=?",Integer.class,original.preferenceId()));
    }
    @Test void adoptCreatesExactOwnRevisionKeepsPastAndDoesNotCreatePromotion() {
        var q=question();tx.executeWithoutResult(ignored->service.answer(q,answer("ADOPT_PUBLIC")));
        var latest=definitions.current(original.preferenceId());assertEquals(2,latest.revision());assertEquals(PersonalSemanticDefinitionStore.Sharing.PRIVATE,latest.sharing());
        assertEquals(PersonalDefinitionSnapshot.capture(current,"METRIC","public_amount").snapshot(),latest.snapshot());
        assertEquals(original.text(),definitions.require(original.preferenceId(),1).text());
        assertTrue(service.next(project,project,"alice","我的金额",q.runId()).isEmpty());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_project_definition_source WHERE preference_id=?",Integer.class,original.preferenceId()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_user_semantic_preference_usage WHERE preference_id=?",Integer.class,original.preferenceId()));
        assertEquals(2,jdbc.queryForObject("SELECT source_revision FROM qw_personal_definition_document WHERE preference_id=?",Integer.class,original.preferenceId()));
    }
    @Test void compatibleDefinitionAndOtherUsersOrUnrelatedQuestionDoNotPrompt() {
        current=old.detachedCopy();current.getMetrics().get(0).setDescription("Only a display update");
        assertTrue(service.next(project,project,"alice","我的金额","unused").isEmpty());
        assertTrue(service.next(project,project,"bob","我的金额","unused").isEmpty());
        assertTrue(service.next(project,project,"alice","完全无关的问题","unused").isEmpty());
    }
    @Test void explicitLongerPublicMetricDoesNotTriggerAGenericPersonalUpdateButSeparatePersonalRequestDoes() {
        current.getMetrics().add(SemanticCatalogSnapshot.Metric.builder().metricCode("explicit_amount").modelCode("orders")
            .businessName("我的金额总计").expression("SUM(amount)").status(SemanticAssetStatus.ENABLED).build());
        assertTrue(service.next(project,project,"alice","查看我的金额总计","unused").isEmpty());
        assertTrue(service.next(project,project,"alice","分别查看我的金额和我的金额总计","unused").isPresent());
        assertEquals(original,definitions.current(original.preferenceId()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_personal_public_choice WHERE preference_id=?",Integer.class,original.preferenceId()));
    }
    @Test void stalePublicAnswerAndImpersonationLeaveNoNewRevisionOrReceipt() {
        var q=question();current.getMetrics().get(0).setExpression("SUM(amount) * 3");
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->tx.executeWithoutResult(ignored->service.answer(q,answer("ADOPT_PUBLIC"))));
        current=catalog("SUM(amount) * 2");
        var forged=new RuntimeClarificationService.AnswerCommand(0,"fake","ADOPT_PUBLIC",null,SemanticBindingScope.QUERY,"bob");
        assertThrows(SecurityException.class,()->tx.executeWithoutResult(ignored->service.answer(q,forged)));
        assertEquals(1,definitions.current(original.preferenceId()).revision());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_personal_public_choice WHERE preference_id=?",Integer.class,original.preferenceId()));
    }
    @Test void cancellationAndTransactionFailureCannotCommitChoiceOrUpdate() {
        var q=question();tx.executeWithoutResult(ignored->service.answer(q,answer("CANCEL")));
        tx.executeWithoutResult(status->{service.answer(q,answer("ADOPT_PUBLIC"));status.setRollbackOnly();});
        assertEquals(original,definitions.current(original.preferenceId()));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM qw_personal_public_choice WHERE preference_id=?",Integer.class,original.preferenceId()));
        assertTrue(service.next(project,project,"alice","我的金额",q.runId()).isPresent());
    }
    @Test void existingDefinitionChoiceCannotSmuggleSharingOrNewText() {
        var q=question();
        assertThrows(IllegalArgumentException.class,()->tx.executeWithoutResult(ignored->service.answer(q,
            new RuntimeClarificationService.AnswerCommand(0,"share","ADOPT_PUBLIC",null,SemanticBindingScope.PROJECT,"alice"))));
        assertThrows(IllegalArgumentException.class,()->tx.executeWithoutResult(ignored->service.answer(q,
            new RuntimeClarificationService.AnswerCommand(0,"text","KEEP_PERSONAL","Different formula",SemanticBindingScope.QUERY,"alice"))));
        assertEquals(original,definitions.current(original.preferenceId()));
    }
    @Test void twoTabsCannotAdoptOverANewerOwnRevision() throws Exception {
        var a=question();var b=question();var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> first=()->{start.await();try{tx.executeWithoutResult(ignored->service.answer(a,answer("ADOPT_PUBLIC")));return true;}catch(org.springframework.web.server.ResponseStatusException stale){return false;}};
            Callable<Boolean> second=()->{start.await();try{tx.executeWithoutResult(ignored->service.answer(b,answer("ADOPT_PUBLIC")));return true;}catch(org.springframework.web.server.ResponseStatusException stale){return false;}};
            var one=pool.submit(first);var two=pool.submit(second);start.countDown();assertNotEquals(one.get(15,TimeUnit.SECONDS),two.get(15,TimeUnit.SECONDS));
            assertEquals(2,definitions.current(original.preferenceId()).revision());
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM qw_personal_public_choice WHERE preference_id=?",Integer.class,original.preferenceId()));
        } finally{pool.shutdownNow();}
    }
    @Test void keptCalculationExecutesOldFormulaButCannotBypassChangedPhysicalScope() {
        var frozen=FrozenPersonalMetric.project(original,current);
        assertEquals("p_"+original.preferenceId()+"_1",frozen.getMetricCode());
        assertEquals(new java.math.BigDecimal("30.00"),jdbc.queryForObject("SELECT "+frozen.getExpression()+" FROM reminder_orders",java.math.BigDecimal.class));
        assertEquals(new java.math.BigDecimal("60.00"),jdbc.queryForObject("SELECT "+current.getMetrics().get(0).getExpression()+" FROM reminder_orders",java.math.BigDecimal.class));
        current.getColumns().get(0).setAllowSendToLlm(false);
        assertThrows(SecurityException.class,()->FrozenPersonalMetric.project(original,current));
        current= catalog("SUM(amount) * 2");current.getModels().get(0).setPhysicalTable("other_orders");
        assertThrows(SecurityException.class,()->FrozenPersonalMetric.project(original,current));
    }
    @Test void seenSnapshotsAreImmutable() {
        var q=question();tx.executeWithoutResult(ignored->service.answer(q,answer("KEEP_PERSONAL")));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE qw_personal_public_question SET public_definition_text='changed' WHERE clarification_id=?",q.clarificationId()));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("DELETE FROM qw_personal_public_choice WHERE clarification_id=?",q.clarificationId()));
    }
}
