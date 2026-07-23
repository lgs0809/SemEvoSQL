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
package cn.lgs.semevosql.task;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.lgs.semevosql.run.QueryExecutionExplanationService;
import cn.lgs.semevosql.run.SemanticPlanSnapshotService;
import cn.lgs.semevosql.util.JsonUtil;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;

/** Disposable PG fixtures exercise result/plan association; no acceptance database state is manufactured. */
@Testcontainers
class QueryTaskAnswerPostgresIT {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;
    QueryTaskAnswerService service;
    String run;
    @BeforeAll static void migrate() {
        var ds = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").load().migrate();
        jdbc = new JdbcTemplate(ds);
    }
    @BeforeEach void setup() {
        service = new QueryTaskAnswerService(jdbc,
            new QueryExecutionExplanationService(jdbc, mock(SemanticPlanSnapshotService.class)));
        run = createRun();
    }
    String createRun() {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO qw_query_run(run_id,run_type,status,idempotency_key) VALUES (?,'INTERACTIVE_QUERY','SUCCEEDED',?)",id,id);
        return id;
    }
    void task(String id, int ordinal, String value, String state, String review) throws Exception {
        String month = "2026-%02d-01T00:00".formatted(ordinal+1);
        String plan = JsonUtil.getObjectMapper().writeValueAsString(Map.of(
            "metrics",List.of(Map.of("metricCode","amount","businessName","金额")),
            "models",List.of(Map.of("modelCode","orders","businessName","订单")),
            "timeRange",Map.of("startInclusive",month,"endExclusive","2026-%02d-01T00:00".formatted(ordinal+2))));
        String result = JsonUtil.getObjectMapper().writeValueAsString(Map.of("resultPayload",
            JsonUtil.getObjectMapper().writeValueAsString(Map.of("resultSet",Map.of("column",List.of("amount"),"data",List.of(Map.of("amount",value)))))));
        jdbc.update("""
            INSERT INTO qw_query_task(run_id,task_id,ordinal_no,question,status,semantic_plan_json,result_summary_json,review_json)
            VALUES (?,?,?, ?,?,CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS jsonb))
            """,id,"task-"+(ordinal+1),ordinal,"第"+(ordinal+1)+"个月的金额",state,plan,result,"{\"decision\":\""+review+"\"}");
    }
    @Test void threeSameSchemaResultsKeepTheirOwnTimeRangeAndAmountInOrder() throws Exception {
        task(run,2,"360","DONE","PASS");task(run,0,"270","DONE","PASS");task(run,1,"300","DONE","PASS");
        var answers=service.acceptedAnswers(run);
        assertEquals(List.of("270","300","360"),answers.stream().map(a->a.rows().get(0).get("amount")).toList());
        for(int i=0;i<3;i++) {
            var a=answers.get(i); assertNull(a.error());assertEquals(i,a.ordinal());
            assertEquals("2026-%02d-01T00:00".formatted(i+1),a.explanation().time().get("startInclusive"));
            assertEquals("金额",a.explanation().resultColumns().get(0).get("label"));
            assertEquals(a.question(),a.explanation().understoodQuery());
            assertTrue(a.explanation().sqlExecutions().isEmpty());
        }
    }
    @Test void pendingAndRejectedResultsAreNotPresentedAsAccepted() throws Exception {
        task(run,0,"270","DONE","PASS");task(run,1,"999","ACTIVE","PASS");task(run,2,"999","DONE","RETRY_SQL");
        var answers=service.acceptedAnswers(run);assertEquals(1,answers.size());assertEquals("270",answers.get(0).rows().get(0).get("amount"));
    }
    @Test void sameTaskIdInAnotherRunCannotReplaceThisRunsResult() throws Exception {
        task(run,0,"270","DONE","PASS");task(createRun(),0,"999","DONE","PASS");
        assertEquals("270",service.acceptedAnswers(run).get(0).rows().get(0).get("amount"));
    }
    @Test void corruptAcceptedPayloadRetainsExplicitErrorWithoutBorrowingAnotherResult() throws Exception {
        task(run,0,"270","DONE","PASS");task(run,1,"300","DONE","PASS");
        jdbc.update("UPDATE qw_query_task SET result_summary_json = '{}'::jsonb WHERE run_id=? AND ordinal_no=1",run);
        var answers=service.acceptedAnswers(run);assertEquals(2,answers.size());
        assertNotNull(answers.get(1).error());assertTrue(answers.get(1).rows().isEmpty());assertNull(answers.get(1).explanation());
    }
    @Test void emptyAcceptedTableIsNotAReadFailure() throws Exception {
        task(run,0,"270","DONE","PASS");
        jdbc.update("UPDATE qw_query_task SET result_summary_json = '{\"column\":[\"amount\"],\"data\":[]}'::jsonb WHERE run_id=?",run);
        var answer=service.acceptedAnswers(run).get(0);assertNull(answer.error());assertTrue(answer.rows().isEmpty());assertEquals(List.of("amount"),answer.columns());
    }
}
