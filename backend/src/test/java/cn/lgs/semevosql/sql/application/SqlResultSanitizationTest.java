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
package cn.lgs.semevosql.sql.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.lgs.semevosql.bo.DbConfigBO;
import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.connector.DbQueryParameter;
import cn.lgs.semevosql.connector.accessor.Accessor;
import cn.lgs.semevosql.run.RunExecutionFenceService;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class SqlResultSanitizationTest {
    @Test void executionWithoutDurableContextStillSanitizesBeforeReturning() throws Exception {
        var service=new SqlExecutionAttemptService(mock(JdbcTemplate.class),mock(PlatformTransactionManager.class),mock(RunExecutionFenceService.class));
        var config=DbConfigBO.builder().build();var parameter=new DbQueryParameter();var accessor=mock(Accessor.class);
        var row=new LinkedHashMap<String,String>();row.put("secret","private-value");
        var result=ResultSetBO.builder().column(List.of("secret")).data(List.of(row)).build();
        when(accessor.executeSqlAndReturnObject(config,parameter)).thenReturn(result);
        var calls=new AtomicInteger();
        var returned=service.execute(accessor,config,parameter,null,"QUERY",value->{
            calls.incrementAndGet();value.getData().get(0).put("secret","MASKED");
        });
        assertEquals("MASKED",returned.getData().get(0).get("secret"));assertEquals(1,calls.get());
        assertNull(parameter.getExecutionDelegate());verify(accessor).executeSqlAndReturnObject(config,parameter);
    }
}
