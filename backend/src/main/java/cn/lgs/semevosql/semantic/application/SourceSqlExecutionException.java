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

/** Retains the exact failed SQL and trace identity for a bounded adjustment, not another blind compilation. */
public final class SourceSqlExecutionException extends Exception {
    private final String sql;
    private final String traceReference;
    public SourceSqlExecutionException(String sql, String traceReference, Throwable cause) {
        super("Source SQL execution failed", cause);
        this.sql = sql; this.traceReference = traceReference;
    }
    public String sql() { return sql; }
    public String traceReference() { return traceReference; }
}
