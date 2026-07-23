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

/** Forked disposable-test process: the parent kills it while a real database statement is active. */
public final class SqlAttemptCrashProbe {
    public static void main(String[] args) throws Exception {
        var ds=new org.springframework.jdbc.datasource.DriverManagerDataSource(args[0],args[1],args[2]);
        var service=new SqlExecutionAttemptService(new org.springframework.jdbc.core.JdbcTemplate(ds),
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds),
            org.mockito.Mockito.mock(cn.lgs.semevosql.run.RunExecutionFenceService.class));
        var p=new cn.lgs.semevosql.connector.DbQueryParameter().setSql(args[5]).setMaxRows(10).setQueryTimeoutSeconds(30).setParameters(java.util.List.of()).setCancellationKey(args[3]);
        try(var c=ds.getConnection()) {
            service.executeOnConnection(c,p,new SqlExecutionAttemptService.Context(args[3],args[4],"process-crash",1),"QUERY",null);
        }
    }
}
