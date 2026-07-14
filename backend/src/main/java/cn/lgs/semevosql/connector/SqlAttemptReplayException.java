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

import java.sql.SQLException;

/** A durable attempt may be replayed as an outcome, never silently executed again. */
public final class SqlAttemptReplayException extends SQLException {
    private final String category;
    private final boolean retryAllowed;
    private final String cleanupStatus;
    private final long elapsedMillis;
    public SqlAttemptReplayException(String category, String sqlState, int vendorCode,
            boolean retryAllowed, boolean cleanupConfirmed, long elapsedMillis) {
        this(category,sqlState,vendorCode,retryAllowed,cleanupConfirmed?"CONFIRMED":"UNCONFIRMED",elapsedMillis);
    }
    public SqlAttemptReplayException(String category,String sqlState,int vendorCode,boolean retryAllowed,String cleanupStatus,long elapsedMillis) {
        super("Recorded SQL outcome: " + category, sqlState, vendorCode);
        this.category=category; this.retryAllowed=retryAllowed; this.cleanupStatus=cleanupStatus;
        this.elapsedMillis=elapsedMillis;
    }
    public String category(){return category;}
    public boolean retryAllowed(){return retryAllowed;}
    public boolean cleanupConfirmed(){return java.util.Set.of("CONFIRMED","ROLLBACK_CONFIRMED","SESSION_TERMINATION_CONFIRMED").contains(cleanupStatus);}
    public String cleanupStatus(){return cleanupStatus;}
    public long elapsedMillis(){return elapsedMillis;}
}
