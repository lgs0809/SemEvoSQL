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
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Application-wide registry for JDBC statements that belong to a durable run.
 *
 * <p>
 * Cancellation is prefix based so a parent run can cancel all single-source,
 * multi-source, preflight and freshness statements. A short-lived cancellation tombstone
 * closes the race where the cancel request arrives before a statement is registered.
 * </p>
 */
public final class JdbcStatementCancellationRegistry {

	private static final Duration CANCELLATION_TTL = Duration.ofHours(1);

	private static final Map<String, Set<Statement>> ACTIVE = new ConcurrentHashMap<>();

	private static final Map<String, Long> CANCELLED_PREFIXES = new ConcurrentHashMap<>();

	private JdbcStatementCancellationRegistry() {
	}

	public static Registration register(String cancellationKey, Statement statement) throws SQLException {
		if (cancellationKey == null || cancellationKey.isBlank() || statement == null) {
			return Registration.noop();
		}
		purgeExpiredTombstones();
		if (isCancellationRequested(cancellationKey)) {
			throw new SqlQueryCancelledException(null);
		}
		Set<Statement> statements = ACTIVE.computeIfAbsent(cancellationKey, ignored -> ConcurrentHashMap.newKeySet());
		statements.add(statement);
		if (isCancellationRequested(cancellationKey)) {
			unregister(cancellationKey, statement);
			throw new SqlQueryCancelledException(null);
		}
		return new Registration(cancellationKey, statement);
	}

    public static void assertNotCancelled(String cancellationKey) throws SQLException {
        if (isCancellationRequested(cancellationKey)) {
            throw new SqlQueryCancelledException(null);
        }
    }

	public static CancellationResult cancelPrefix(String prefix) {
		if (prefix == null || prefix.isBlank()) {
			return new CancellationResult(0, 0, List.of());
		}
		purgeExpiredTombstones();
		CANCELLED_PREFIXES.put(prefix, System.currentTimeMillis());
		int matched = 0;
		int cancelled = 0;
		List<String> errors = new ArrayList<>();
		for (Map.Entry<String, Set<Statement>> entry : ACTIVE.entrySet()) {
			if (!entry.getKey().startsWith(prefix)) {
				continue;
			}
			for (Statement statement : List.copyOf(entry.getValue())) {
				matched++;
				try {
					statement.cancel();
					cancelled++;
				}
				catch (SQLException ex) {
					errors.add(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
				}
			}
		}
		return new CancellationResult(matched, cancelled, List.copyOf(errors));
	}

	public static int activeCount(String prefix) {
		if (prefix == null || prefix.isBlank()) {
			return 0;
		}
		return ACTIVE.entrySet()
			.stream()
			.filter(entry -> entry.getKey().startsWith(prefix))
			.mapToInt(entry -> entry.getValue().size())
			.sum();
	}

	public static boolean isCancellationRequested(String cancellationKey) {
		if (cancellationKey == null || cancellationKey.isBlank()) {
			return false;
		}
		purgeExpiredTombstones();
		return CANCELLED_PREFIXES.keySet().stream().anyMatch(cancellationKey::startsWith);
	}

	public static void clearPrefix(String prefix) {
		if (prefix == null || prefix.isBlank()) {
			return;
		}
		CANCELLED_PREFIXES.remove(prefix);
	}

	private static void unregister(String cancellationKey, Statement statement) {
		Set<Statement> statements = ACTIVE.get(cancellationKey);
		if (statements == null) {
			return;
		}
		statements.remove(statement);
		if (statements.isEmpty()) {
			ACTIVE.remove(cancellationKey, statements);
		}
	}

	private static void purgeExpiredTombstones() {
		long cutoff = System.currentTimeMillis() - CANCELLATION_TTL.toMillis();
		CANCELLED_PREFIXES.entrySet().removeIf(entry -> entry.getValue() < cutoff);
	}

	public record CancellationResult(int matchedStatements, int cancelledStatements, List<String> errors) {
	}

	public static final class Registration implements AutoCloseable {

		private static final Registration NOOP = new Registration(null, null);

		private final String cancellationKey;

		private final Statement statement;

		private final AtomicBoolean closed = new AtomicBoolean();

		private Registration(String cancellationKey, Statement statement) {
			this.cancellationKey = cancellationKey;
			this.statement = statement;
		}

		private static Registration noop() {
			return NOOP;
		}

		@Override
		public void close() {
			if (statement != null && closed.compareAndSet(false, true)) {
				unregister(cancellationKey, statement);
			}
		}

	}

}
