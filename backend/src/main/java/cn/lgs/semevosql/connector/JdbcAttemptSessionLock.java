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
package cn.lgs.semevosql.connector;

import com.alibaba.druid.pool.DruidPooledConnection;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;

/** Session ownership survives process loss and fences late submissions during recovery. */
public final class JdbcAttemptSessionLock implements AutoCloseable {
    private final Connection connection;
    private final Connection control;
    private final String name;
    private final boolean pg;
    private final int high;
    private final int low;
    private boolean acquired;

    public JdbcAttemptSessionLock(Connection connection,String attemptId) throws SQLException {
        this.connection=connection;this.name="qw_sql_"+attemptId;
        String dialect=connection.getMetaData().getDatabaseProductName();
        this.pg="PostgreSQL".equalsIgnoreCase(dialect);
        if(!pg && !"MySQL".equalsIgnoreCase(dialect)) throw new SQLException("Durable SQL attempts require PostgreSQL or MySQL");
        // Fixed, parameterized ownership/control commands are system operations. Druid's user-SQL wall
        // denies pg_database and KILL. Keep it enabled on the original pooled connection for every query.
        this.control=connection instanceof DruidPooledConnection
            ? (pg?connection.unwrap(org.postgresql.jdbc.PgConnection.class):connection.unwrap(com.mysql.cj.jdbc.JdbcConnection.class))
            :connection;
        try {
            var bytes=ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8)));
            high=bytes.getInt();low=bytes.getInt();
        } catch(Exception e){throw new SQLException("Cannot derive SQL ownership key",e);}
    }
    public boolean acquire() throws SQLException {
        acquired=pg?Boolean.TRUE.equals(scalar("SELECT pg_try_advisory_lock(?,?)",high,low))
            : "1".equals(String.valueOf(scalar("SELECT GET_LOCK(?,0)",name)));
        return acquired;
    }
    public long ownSessionId() throws SQLException {
        return ((Number)scalar(pg?"SELECT pg_backend_pid()":"SELECT CONNECTION_ID()")).longValue();
    }
    /** Exact ownership token, not a SQL-text or username substring. Null means no owning session. */
    public Long owner() throws SQLException {
        Object value=pg?scalar("""
            SELECT pid FROM pg_locks WHERE locktype='advisory' AND granted AND objsubid=2
              AND database=(SELECT oid FROM pg_database WHERE datname=current_database())
              AND classid::bigint=? AND objid::bigint=?
            """,Integer.toUnsignedLong(high),Integer.toUnsignedLong(low))
            :scalar("SELECT IS_USED_LOCK(?)",name);
        return value instanceof Number n?n.longValue():null;
    }
    /** Only terminate a session still owning this attempt. Then prove ownership is gone. */
    public void reconcile(Long recordedSession) throws SQLException {
        Long pid=owner();
        if(pid!=null) {
            if(pid==ownSessionId() || (recordedSession!=null && !recordedSession.equals(pid)))
                throw new JdbcQueryCleanupException(new SQLException("SQL session ownership changed"));
            if(pg) {
                // Nonzero timeout waits for actual termination; a sent signal alone is insufficient.
                if(!Boolean.TRUE.equals(scalar("SELECT pg_terminate_backend(?,3000)",Math.toIntExact(pid))))
                    throw new JdbcQueryCleanupException(new SQLException("SQL session termination not confirmed"));
            } else {
                try(var s=control.createStatement()){s.setQueryTimeout(5);s.execute("KILL CONNECTION "+pid);}
            }
        }
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        while(!acquire()) {
            if(System.nanoTime()>=until) throw new JdbcQueryCleanupException(new SQLException("SQL ownership is still held"));
            try{Thread.sleep(25);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new SQLException("SQL recovery interrupted",e);}
        }
    }
    private Object scalar(String sql,Object... values) throws SQLException {
        try(var s=control.prepareStatement(sql)) {
            s.setQueryTimeout(5); for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);
            try(var r=s.executeQuery()){return r.next()?r.getObject(1):null;}
        }
    }
    @Override public void close() throws SQLException {
        if(!acquired)return;
        try {
            Object released=pg?scalar("SELECT pg_advisory_unlock(?,?)",high,low):scalar("SELECT RELEASE_LOCK(?)",name);
            if(!(pg?Boolean.TRUE.equals(released):"1".equals(String.valueOf(released))))
                throw new SQLException("SQL ownership release not confirmed");
            acquired=false;
        }catch(SQLException failure){discard(connection,failure);throw new JdbcQueryCleanupException(failure);}
    }
    public static void discard(Connection connection,Throwable reason) {
        try {
            if(connection instanceof DruidPooledConnection pooled) {
                pooled.getConnectionHolder().getDataSource().discardConnection(pooled.getConnectionHolder());
                pooled.disable(reason);
            }else connection.abort(Runnable::run);
        }catch(Exception abort){reason.addSuppressed(abort);}
    }
}
