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

import cn.lgs.semevosql.connector.JdbcQueryExecutionException;
import java.sql.SQLException;
import java.util.*;

/** Structural error facts only: no credential, connection URL, literal or raw server message. */
public final class SqlFailureEvidence {
    private SqlFailureEvidence() {}
    public static Map<String,Object> capture(Throwable error) {
        var facts=new LinkedHashMap<String,Object>();
        String cleanup="UNKNOWN_OR_NOT_STARTED";
        Set<Throwable> visited=Collections.newSetFromMap(new IdentityHashMap<>());
        var pending=new ArrayDeque<Throwable>();pending.add(error);
        while(!pending.isEmpty()) {
            Throwable current=pending.removeFirst();if(!visited.add(current))continue;
            if(current instanceof SQLException sql) {
                if(sql.getSQLState()!=null)facts.putIfAbsent("sqlState",sql.getSQLState());
                facts.putIfAbsent("vendorCode",sql.getErrorCode());
            }
            if(current instanceof JdbcQueryExecutionException execution) {
                cleanup=execution.cleanupConfirmed()?"ROLLBACK_CONFIRMED":"UNCONFIRMED";
                facts.put("queryElapsedMs",execution.elapsedMillis());
            }
            if(current instanceof cn.lgs.semevosql.connector.SqlAttemptReplayException stored) {
                cleanup=stored.cleanupStatus();
                facts.put("queryElapsedMs",stored.elapsedMillis());
            }
            if(current.getCause()!=null)pending.add(current.getCause());
            pending.addAll(List.of(current.getSuppressed()));
        }
        String type=new SqlValidationClassifier().classify(error,0).errorType();
        if("SQL_CLEANUP_UNCONFIRMED".equals(type))cleanup="UNCONFIRMED";
        facts.put("cleanupStatus",cleanup);facts.put("errorCategory",Objects.toString(type,"UNKNOWN"));
        return Map.copyOf(facts);
    }
}
