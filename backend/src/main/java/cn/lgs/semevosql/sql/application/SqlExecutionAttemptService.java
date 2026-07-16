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

import cn.lgs.semevosql.bo.DbConfigBO;
import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.connector.*;
import cn.lgs.semevosql.connector.accessor.Accessor;
import cn.lgs.semevosql.run.RunExecutionFenceService;
import cn.lgs.semevosql.util.JsonUtil;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Short metadata transactions around one real database statement, including preflight statements.
 * A replay reuses a sanitized result or reconciles the old session; it never resets its deadline.
 */
@Service
public class SqlExecutionAttemptService {
    private final JdbcTemplate jdbc;
    private final RunExecutionFenceService fence;
    private final TransactionTemplate tx;
    private final String instance=UUID.randomUUID().toString();

    public SqlExecutionAttemptService(JdbcTemplate jdbc,PlatformTransactionManager manager,RunExecutionFenceService fence) {
        this.jdbc=jdbc;this.fence=fence;this.tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public record Context(String runId,String graphAttemptId,String scopeKey,Integer datasourceId) {}
    public Context context(String runId,String graphAttemptId,String scopeKey,Integer datasourceId) {
        if(runId==null || graphAttemptId==null || graphAttemptId.isBlank() || scopeKey==null || scopeKey.isBlank()) return null;
        return new Context(runId,graphAttemptId,scopeKey,datasourceId);
    }
    public boolean hasRecorded(String runId,String scopeKey) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM qw_sql_execution_attempt WHERE run_id=? AND scope_key=?)",Boolean.class,runId,scopeKey));
    }
    public ResultSetBO execute(Accessor accessor,DbConfigBO config,DbQueryParameter parameter,Context context,
            String phase,Consumer<ResultSetBO> sanitize) throws Exception {
        if(context==null) {
            ResultSetBO result=accessor.executeSqlAndReturnObject(config,parameter);
            if(sanitize!=null)sanitize.accept(result);
            return result;
        }
        parameter.setExecutionDelegate(connection -> executeOnConnection(connection,parameter,context,phase,sanitize));
        try{return accessor.executeSqlAndReturnObject(config,parameter);}
        finally{parameter.setExecutionDelegate(null);}
    }
    public ResultSetBO executeOnConnection(Connection connection,DbQueryParameter p,Context c,String phase,
            Consumer<ResultSetBO> sanitize) throws Exception {
        fence.assertActive(c.runId(),c.graphAttemptId());
        String hash=hash(cn.lgs.semevosql.util.CanonicalJson.write(Arrays.asList(p.getSql(),p.getParameters(),p.getSchema(),p.getMaxRows())));
        String token=UUID.randomUUID().toString();
        Map<String,Object> row=tx.execute(status -> {
            fence.assertActiveAndLock(c.runId(),c.graphAttemptId());
            jdbc.update("""
                INSERT INTO qw_sql_execution_attempt(sql_attempt_id,run_id,graph_attempt_id,owner_instance,scope_key,phase,input_hash,datasource_id,status)
                VALUES (?,?,?,?,?,?,?,?,'PREPARED') ON CONFLICT(run_id,scope_key,phase) DO NOTHING
                """,token,c.runId(),c.graphAttemptId(),instance,c.scopeKey(),phase,hash,c.datasourceId());
            return jdbc.queryForMap("SELECT * FROM qw_sql_execution_attempt WHERE run_id=? AND scope_key=? AND phase=?",
                c.runId(),c.scopeKey(),phase);
        });
        String id=row.get("sql_attempt_id").toString();
        boolean created=token.equals(id);
        if(!hash.equals(row.get("input_hash")))
            throw new SqlAttemptReplayException("SQL_ATTEMPT_INPUT_CHANGED",null,0,false,false,0);
        if(!created) {
            String status=row.get("status").toString();
            if("SUCCEEDED".equals(status))return JsonUtil.getObjectMapper().readValue(row.get("result_json").toString(),ResultSetBO.class);
            if("FAILED".equals(status))throw replay(row);
            if(!"UNCERTAIN".equals(status) && instance.equals(row.get("owner_instance")) && c.graphAttemptId().equals(row.get("graph_attempt_id")))
                throw new SqlAttemptReplayException("SQL_ATTEMPT_IN_PROGRESS",null,0,false,false,0);
            reconcile(connection,row);
            var reconciled=require(id);
            if("SUCCEEDED".equals(reconciled.get("status")))
                return JsonUtil.getObjectMapper().readValue(reconciled.get("result_json").toString(),ResultSetBO.class);
            throw replay(reconciled);
        }
        // A different SQL revision must not bypass an unresolved execution from the same Run/source.
        for(var previous:jdbc.queryForList("""
            SELECT * FROM qw_sql_execution_attempt WHERE run_id=? AND datasource_id=? AND sql_attempt_id<>?
              AND status IN ('PREPARED','RUNNING','RECOVERING','UNCERTAIN') ORDER BY create_time
            """,c.runId(),c.datasourceId(),id)) {
            if(!"UNCERTAIN".equals(previous.get("status")) && instance.equals(previous.get("owner_instance")) && c.graphAttemptId().equals(previous.get("graph_attempt_id"))) {
                recordFailure(id,new SqlAttemptReplayException("SQL_PREVIOUS_ATTEMPT_UNRESOLVED",null,0,false,false,0));
                throw new SqlAttemptReplayException("SQL_PREVIOUS_ATTEMPT_UNRESOLVED",null,0,false,false,0);
            }
            try{reconcile(connection,previous);}catch(Exception failure){recordFailure(id,failure);throw failure;}
        }
        try(var ownership=new JdbcAttemptSessionLock(connection,id)) {
            if(!ownership.acquire())throw new JdbcQueryCleanupException(new SQLException("SQL session ownership unavailable"));
            int seconds=p.getQueryTimeoutSeconds()==null || p.getQueryTimeoutSeconds()<=0?30:Math.min(30,p.getQueryTimeoutSeconds());
            long deadline=System.currentTimeMillis()+seconds*1000L;
            int started=jdbc.update("""
                UPDATE qw_sql_execution_attempt SET status='RUNNING',deadline_epoch_ms=?,backend_session_id=?,update_time=CURRENT_TIMESTAMP
                WHERE sql_attempt_id=? AND status='PREPARED' AND owner_instance=? AND graph_attempt_id=?
                """,deadline,ownership.ownSessionId(),id,instance,c.graphAttemptId());
            if(started!=1)throw new JdbcQueryCleanupException(new SQLException("SQL attempt was superseded before submission"));
            fence.assertActive(c.runId(),c.graphAttemptId());
            ResultSetBO result=SqlExecutor.executeSqlAndReturnObject(connection,p.getSchema(),p.getSql(),p.getParameters(),
                p.getMaxRows(),seconds,p.getCancellationKey(),deadline);
            if(sanitize!=null)sanitize.accept(result);
            String serialized=JsonUtil.getObjectMapper().writeValueAsString(result);
            int completed=jdbc.update("""
                UPDATE qw_sql_execution_attempt SET status='SUCCEEDED',result_json=?::jsonb,update_time=CURRENT_TIMESTAMP
                WHERE sql_attempt_id=? AND status='RUNNING' AND owner_instance=? AND graph_attempt_id=?
                """,serialized,id,instance,c.graphAttemptId());
            if(completed!=1)throw new JdbcQueryCleanupException(new SQLException("SQL completion lost its execution fence"));
            return result;
        }catch(Exception failure) {
            recordFailure(id,failure);
            throw failure;
        }
    }
    private void reconcile(Connection connection,Map<String,Object> previous) throws Exception {
        String id=previous.get("sql_attempt_id").toString();
        Exception failure=tx.execute(status -> {
            var current=jdbc.queryForMap("SELECT * FROM qw_sql_execution_attempt WHERE sql_attempt_id=? FOR UPDATE",id);
            if("SUCCEEDED".equals(current.get("status")) || "FAILED".equals(current.get("status")))return null;
            try{reconcileLocked(connection,current);return null;}catch(Exception error){return error;}
        });
        if(failure!=null)throw failure;
    }
    private void reconcileLocked(Connection connection,Map<String,Object> previous) throws Exception {
        String id=previous.get("sql_attempt_id").toString();
        // Revoke submission before inspecting the database. A delayed former process cannot attach/complete afterwards.
        jdbc.update("UPDATE qw_sql_execution_attempt SET status='RECOVERING',update_time=CURRENT_TIMESTAMP WHERE sql_attempt_id=? AND status IN ('PREPARED','RUNNING','RECOVERING','UNCERTAIN')",id);
        try(var ownership=new JdbcAttemptSessionLock(connection,id)) {
            ownership.reconcile(previous.get("backend_session_id") instanceof Number n?n.longValue():null);
            Map<String,Object> error=new LinkedHashMap<>();
            Long deadline=previous.get("deadline_epoch_ms") instanceof Number n?n.longValue():null;
            error.put("errorCategory",deadline!=null && System.currentTimeMillis()>=deadline?"SQL_TIMEOUT":"SQL_RECOVERED_INTERRUPTED");
            error.put("sqlState","57014");error.put("vendorCode",0);error.put("retryAllowed",true);
            error.put("cleanupStatus","SESSION_TERMINATION_CONFIRMED");
            // Never reset the stored deadline, and never rerun this attempt's SQL here.
            jdbc.update("UPDATE qw_sql_execution_attempt SET status='FAILED',error_json=?::jsonb,update_time=CURRENT_TIMESTAMP WHERE sql_attempt_id=? AND status='RECOVERING'",json(error),id);
        }catch(Exception failure) {
            jdbc.update("UPDATE qw_sql_execution_attempt SET status='UNCERTAIN',update_time=CURRENT_TIMESTAMP WHERE sql_attempt_id=? AND status='RECOVERING'",id);
            if(failure instanceof JdbcQueryCleanupException)throw failure;
            throw new JdbcQueryCleanupException(new SQLException("Unable to confirm old SQL cleanup",failure));
        }
    }
    private void recordFailure(String id,Exception failure) {
        var validation=new SqlValidationClassifier().classify(failure,0);
        Map<String,Object> facts=new LinkedHashMap<>(SqlFailureEvidence.capture(failure));
        facts.put("retryAllowed",validation.retryAllowed());
        boolean uncertain="SQL_CLEANUP_UNCONFIRMED".equals(validation.errorType())
            || (validation.retryAllowed() && !"ROLLBACK_CONFIRMED".equals(facts.get("cleanupStatus")));
        String status=uncertain?"UNCERTAIN":"FAILED";
        try {
            jdbc.update("""
                UPDATE qw_sql_execution_attempt SET status=?,error_json=?::jsonb,update_time=CURRENT_TIMESTAMP
                WHERE sql_attempt_id=? AND status IN ('PREPARED','RUNNING') AND owner_instance=?
                """,status,json(facts),id,instance);
        }catch(RuntimeException persistence){failure.addSuppressed(persistence);}
    }
    private Map<String,Object> require(String id){return jdbc.queryForMap("SELECT * FROM qw_sql_execution_attempt WHERE sql_attempt_id=?",id);}
    private SqlAttemptReplayException replay(Map<String,Object> row) throws Exception {
        var error=JsonUtil.getObjectMapper().readTree(Objects.toString(row.get("error_json"),"{}"));
        String cleanup=error.path("cleanupStatus").asText();
        return new SqlAttemptReplayException(error.path("errorCategory").asText("SQL_OUTCOME_UNAVAILABLE"),
            error.path("sqlState").asText(null),error.path("vendorCode").asInt(),error.path("retryAllowed").asBoolean(),
            cleanup,error.path("queryElapsedMs").asLong());
    }
    private String json(Object value){try{return JsonUtil.getObjectMapper().writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
