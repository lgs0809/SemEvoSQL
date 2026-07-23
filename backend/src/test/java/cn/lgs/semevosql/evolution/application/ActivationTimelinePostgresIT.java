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
package cn.lgs.semevosql.evolution.application;

import cn.lgs.semevosql.project.domain.*;
import java.time.LocalDateTime;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual immutable activity rows; publication is never evidence of activation. */
@Testcontainers
class ActivationTimelinePostgresIT {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
        DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    @Test void resolvesManualAndEvolutionHistoryWithoutInventingActivationOrLeakingAnotherProject() {
        var ds = new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").load().migrate();
        var jdbc = new JdbcTemplate(ds);
        jdbc.update("INSERT INTO qw_project(id,project_code,name,business_domain,status,created_by) VALUES (91,'timeline-test','Synthetic timeline','test','ACTIVE','test'),(92,'timeline-other','Other scope','test','ACTIVE','test')");
        for(long id:List.of(91L,92L,93L))
            jdbc.update("INSERT INTO qw_project_version(id,project_id,version_no,version_number,status,analysis_status,semantic_major,semantic_minor,semantic_patch) VALUES (?,91,?,?,'PUBLISHED','COMPLETED',1,0,?)",id,id,"1.0."+(id-91),id-91);
        jdbc.update("INSERT INTO qw_project_version_activity(project_id,project_version_id,activity_type,operator_name,operator_role,request_id,idempotency_key,create_time) VALUES (91,91,'ACTIVATED','test','LOCAL_OPERATOR','a','a','2026-01-02'),(91,91,'ACTIVATED','test','LOCAL_OPERATOR','b','b','2026-01-03'),(91,92,'PUBLISHED','test','LOCAL_OPERATOR','c','c','2026-01-01'),(92,92,'ACTIVATED','test','LOCAL_OPERATOR','d','d','2026-01-04')");
        var manual=SemanticProjectVersion.builder().id(91L).publishedTime(LocalDateTime.parse("2026-01-01T00:00:00")).build();
        var never=SemanticProjectVersion.builder().id(92L).publishedTime(LocalDateTime.parse("2026-01-01T00:00:00")).build();
        var evolution=SemanticProjectVersion.builder().id(93L).activatedTime(LocalDateTime.parse("2026-01-05T00:00:00")).build();
        var repo=mock(SemanticProjectRepository.class);
        when(repo.findProject(91L)).thenReturn(Optional.of(SemanticProject.builder().id(91L).activeVersionId(91L).build()));
        when(repo.findVersions(91L)).thenReturn(List.of(manual,never,evolution));
        var service=new SemanticGovernanceApplicationService(jdbc,repo,null,null,null,null,null);
        var view=service.timeline(91L);
        assertEquals(91L,view.activeVersionId());
        assertEquals(LocalDateTime.parse("2026-01-03T00:00:00"),manual.getActivatedTime());
        assertNull(never.getActivatedTime());
        assertEquals(LocalDateTime.parse("2026-01-05T00:00:00"),evolution.getActivatedTime());
    }
}
