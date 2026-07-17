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
package cn.lgs.semevosql.service.graph.Context;

import static org.junit.jupiter.api.Assertions.*;
import cn.lgs.semevosql.service.graph.Context.ConversationTurnRepository.ConversationTurn;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Real PostgreSQL concurrency; synthetic conversation rows, no model success is claimed. */
@Testcontainers
class ConversationCompactionPostgresIT {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static JdbcTemplate jdbc;
    static DriverManagerDataSource ds;
    String thread;
    ConversationTurnRepository turns;
    ConversationContextCompactionRepository repo;
    @BeforeAll static void migrate() {
        ds=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").load().migrate();
        jdbc=new JdbcTemplate(ds);
    }
    @BeforeEach void setup() {
        thread="synthetic-"+UUID.randomUUID(); turns=new ConversationTurnRepository(jdbc);
        repo=new ConversationContextCompactionRepository(jdbc);
        for(int i=1;i<=12;i++) add(i);
    }
    void add(int seq) {
        jdbc.update("INSERT INTO qw_conversation_turn(id,run_id,thread_id,turn_sequence,user_question,status,context_summary_json) VALUES (?,?,?,?,?,'COMPLETED','{}')",
                UUID.randomUUID().toString(),UUID.randomUUID().toString(),thread,seq,"合成并发测试 "+seq);
    }
    List<ConversationTurn> batch(long after,int count) {return turns.compactionBatch(thread,after,6,count);}
    ConversationContextCompactionSnapshot next(int through,String text) {
        return new ConversationContextCompactionSnapshot(thread,through,"{\"summary\":\""+text+"\"}",text,1,LocalDateTime.now(),LocalDateTime.now());
    }
    boolean commit(ConversationContextCompactionRepository store,ConversationContextCompactionSnapshot expected,
            ConversationContextCompactionSnapshot next,List<ConversationTurn> sources) {
        return Boolean.TRUE.equals(new TransactionTemplate(new DataSourceTransactionManager(ds))
                .execute(status->store.compareAndSet(expected,next,sources)));
    }
    @Test void twoInstancesCannotOverwriteTheWinnerWithAnOlderInitialSummary() throws Exception {
        var a=batch(0,4); var b=batch(0,6); var barrier=new CyclicBarrier(2);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->{barrier.await();return commit(new ConversationContextCompactionRepository(jdbc),null,next(4,"older"),a);});
            var second=pool.submit(()->{barrier.await();return commit(new ConversationContextCompactionRepository(jdbc),null,next(6,"newer"),b);});
            assertNotEquals(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
        var winner=repo.find(thread).orElseThrow(); assertEquals(1,winner.revision());assertTrue(winner.valid());
        assertFalse(commit(repo,null,next(4,"late"),a));
        assertEquals(winner,repo.find(thread).orElseThrow());
    }
    @Test void aNewTailDoesNotInvalidateTheCapturedPrefixButAnOldBaselineCannotReplaceANewerSummary() {
        assertTrue(commit(repo,null,next(4,"first"),batch(0,4)));
        var expected=repo.find(thread).orElseThrow(); var captured=batch(4,2);
        add(13);
        assertTrue(commit(repo,expected,next(6,"second"),captured));
        assertFalse(commit(repo,expected,next(6,"late"),captured));
        assertEquals(6,repo.find(thread).orElseThrow().coveredThroughSequence());
        assertEquals(13,jdbc.queryForObject("SELECT count(*) FROM qw_conversation_turn WHERE thread_id=?",Integer.class,thread));
    }
    @Test void changedUncoveredSourcesRejectThePendingModelOutput() {
        var captured=batch(0,4);
        jdbc.update("UPDATE qw_conversation_turn SET context_summary_json='{"+"\"corrected\":true}' WHERE thread_id=? AND turn_sequence=2",thread);
        assertFalse(commit(repo,null,next(4,"stale"),captured));
        assertTrue(repo.find(thread).isEmpty());
        assertTrue(commit(repo,null,next(4,"corrected"),batch(0,4)));
    }
    @Test void correctionInvalidatesCoveredSummaryRetainsEvidenceAndBlocksAnAlreadyRunningContinuation() {
        assertTrue(commit(repo,null,next(4,"before-correction"),batch(0,4)));
        var expected=repo.find(thread).orElseThrow();var continuation=batch(4,2);
        jdbc.update("UPDATE qw_conversation_turn SET user_question='合成纠正' WHERE thread_id=? AND turn_sequence=1",thread);
        var invalid=repo.find(thread).orElseThrow();assertFalse(invalid.valid());assertTrue(invalid.revision()>expected.revision());
        assertEquals(expected.summaryJson(),invalid.summaryJson());
        assertFalse(commit(repo,expected,next(6,"stale-continuation"),continuation));
        assertTrue(commit(repo,invalid,next(6,"rebuilt"),batch(0,6)));
        assertTrue(repo.find(thread).orElseThrow().valid());
        assertTrue(new ConversationContextCompactionRepository(new JdbcTemplate(ds)).find(thread).orElseThrow().valid());
    }
    @Test void boundedReadExcludesNewestSixAndDoesNotCrossConversation() {
        for(int i=13;i<=1000;i++) add(i);
        var first=batch(0,20);assertEquals(20,first.size());assertEquals(20,first.get(19).turnSequence());
        var tail=batch(990,200);assertEquals(List.of(991L,992L,993L,994L),tail.stream().map(ConversationTurn::turnSequence).toList());
        assertTrue(turns.compactionBatch(thread+"other",0,6,20).isEmpty());
        assertThrows(IllegalArgumentException.class,()->commit(repo,null,next(20,"wrong"),List.of(first.get(0))));
    }
}
