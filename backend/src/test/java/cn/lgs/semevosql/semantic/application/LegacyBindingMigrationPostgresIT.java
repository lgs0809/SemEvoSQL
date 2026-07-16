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

import cn.lgs.semevosql.semantic.domain.*;
import cn.lgs.semevosql.semantic.infrastructure.*;
import java.util.*;
import org.apache.ibatis.session.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

class LegacyBindingMigrationPostgresIT {
    @Test void upgradingPublishedCatalogPreservesHashRowsAndIndependentDuplicateNames() throws Exception {
        try(var pg=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))) {
            pg.start();var ds=new DriverManagerDataSource(pg.getJdbcUrl()+"&stringtype=unspecified",pg.getUsername(),pg.getPassword());
            Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").target("50").load().migrate();var jdbc=new JdbcTemplate(ds);
            jdbc.update("INSERT INTO datasource(id,name,type,host,port,database_name,username,password) VALUES(17,'Synthetic','postgresql','localhost',5432,'fixture','fixture','fixture')");
            jdbc.update("INSERT INTO qw_project(id,project_code,name,business_domain,status,created_by) VALUES(501,'legacy-migration','Synthetic legacy','fixture','ACTIVE','initializer')");
            jdbc.update("INSERT INTO qw_project_version(id,project_id,version_no,version_number,status,analysis_status,semantic_major,semantic_minor,semantic_patch) VALUES(501,501,1,'1.0.0','DRAFT','COMPLETED',1,0,0)");
            for(String model:List.of("order","paid_order")) {
                jdbc.update("INSERT INTO qw_semantic_model(project_id,project_version_id,datasource_id,model_code,physical_table,business_name,status) VALUES(501,501,17,?,'shop.orders','相同名称','ENABLED')",model);
                jdbc.update("INSERT INTO qw_semantic_column(project_id,project_version_id,model_code,column_name,business_name,data_type,role,status) VALUES(501,501,?,'id','标识','integer','IDENTIFIER','ENABLED')",model);
                jdbc.update("INSERT INTO qw_semantic_metric(project_id,project_version_id,model_code,metric_code,business_name,expression,aggregation,unit,status) VALUES(501,501,?,?,'相同指标名称','COUNT(*)','EXPRESSION','次','ENABLED')",model,model+"_count");
            }
            var configuration=new Configuration();configuration.setMapUnderscoreToCamelCase(true);configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
            var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(configuration);
            factory.setTransactionFactory(new org.mybatis.spring.transaction.SpringManagedTransactionFactory());var sql=factory.getObject();sql.getConfiguration().addMapper(SemEvoSQLSemanticCatalogMapper.class);
            var mapper=new SqlSessionTemplate(sql).getMapper(SemEvoSQLSemanticCatalogMapper.class);
            var before=SemanticCatalogSnapshot.builder().projectId(501L).projectVersionId(501L).models(mapper.findModels(501L,501L))
                .columns(mapper.findColumns(501L,501L)).metrics(mapper.findMetrics(501L,501L)).dimensions(mapper.findDimensions(501L,501L))
                .relationships(mapper.findRelationships(501L,501L)).grains(mapper.findGrains(501L,501L)).enumValues(mapper.findEnumValues(501L,501L)).rules(mapper.findRules(501L,501L)).build();
            String hash=SemanticCatalogFingerprint.fingerprint(before);jdbc.update("UPDATE qw_project_version SET status='PUBLISHED',catalog_hash=? WHERE id=501",hash);
            Flyway.configure().dataSource(ds).locations("classpath:db/migration/postgresql").load().migrate();
            var repository=new MybatisSemanticCatalogRepository(mapper);var after=repository.loadCatalog(501L,501L);
            assertEquals(hash,SemanticCatalogFingerprint.fingerprint(after));assertEquals(hash,jdbc.queryForObject("SELECT catalog_hash FROM qw_project_version WHERE id=501",String.class));
            assertEquals(4,after.getLegacyModelBindings().size());assertTrue(after.getSharedDefinitions().isEmpty());
            assertEquals(4,jdbc.queryForObject("SELECT count(DISTINCT definition_code) FROM qw_semantic_model_asset_binding WHERE project_version_id=501",Integer.class));
            var metric=after.getMetrics().get(0);var reference=SemanticDefinitionBinding.resolve(after,"METRIC",metric.getModelCode(),metric.getMetricCode(),metric.getDefinitionBinding());
            assertNotNull(reference);assertEquals(metric.getModelCode(),reference.modelCode());
            var copy=after.detachedCopy();
            assertEquals(reference,SemanticDefinitionBinding.resolve(copy,"METRIC",metric.getModelCode(),metric.getMetricCode(),metric.getDefinitionBinding()));
            assertEquals(hash,SemanticCatalogFingerprint.fingerprint(copy));
            copy.getMetrics().clear();assertEquals(2,after.getMetrics().size());
            assertEquals(2,repository.loadModelSlice(501L,501L,Set.of("order")).getLegacyModelBindings().size());
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE qw_semantic_metric SET unit='其他' WHERE project_version_id=501"));
            assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("DELETE FROM qw_semantic_version_definition WHERE project_version_id=501"));
        }
    }
}
