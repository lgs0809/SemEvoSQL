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

import cn.lgs.semevosql.concurrency.CapacityRejectedException;
import cn.lgs.semevosql.concurrency.SemEvoSQLConcurrencyProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.springframework.stereotype.Component;

/** Hierarchical SQL capacity isolation plus datasource circuit breaking. */
@Component
public class SqlExecutionAdmissionControl {

	private final SemEvoSQLConcurrencyProperties.SqlLimits limits;

	private final Semaphore globalLimit;

	private final Map<Integer, Semaphore> datasourceLimits = new ConcurrentHashMap<>();

	private final Map<Long, Semaphore> projectLimits = new ConcurrentHashMap<>();

	private final Map<String, Semaphore> userLimits = new ConcurrentHashMap<>();

	private final Map<Integer, Circuit> circuits = new ConcurrentHashMap<>();

	public SqlExecutionAdmissionControl(SemEvoSQLConcurrencyProperties properties) {
		this.limits = properties.getSqlLimits();
		this.globalLimit = semaphore(limits.getGlobalMaxConcurrent());
	}

	public Permit acquire(Long projectId, Integer datasourceId) {
		return acquire(projectId, datasourceId, "anonymous");
	}

	public Permit acquire(Long projectId, Integer datasourceId, String userId) {
		if (projectId == null || datasourceId == null) {
			throw new IllegalArgumentException("projectId and datasourceId are required for SQL admission");
		}
		String effectiveUser = userId == null || userId.isBlank() ? "anonymous" : userId;
		Circuit circuit = circuits.computeIfAbsent(datasourceId, ignored -> new Circuit());
		Instant now = Instant.now();
		if (circuit.openUntil != null && circuit.openUntil.isAfter(now)) {
			throw CapacityRejectedException.serviceUnavailable("datasource",
					retryAfterSeconds(circuit.openUntil.toEpochMilli() - now.toEpochMilli()),
					"Datasource execution circuit is open");
		}

		Semaphore datasourceLimit = datasourceLimits.computeIfAbsent(datasourceId,
				ignored -> semaphore(limits.getDefaultPerDatasource()));
		Semaphore projectLimit = projectLimits.computeIfAbsent(projectId,
				ignored -> semaphore(limits.getDefaultPerProject()));
		Semaphore userLimit = userLimits.computeIfAbsent(effectiveUser,
				ignored -> semaphore(limits.getDefaultPerUser()));
		long timeoutNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(0, limits.getAcquireTimeoutMs()));
		long deadline = System.nanoTime() + timeoutNanos;
		CapacityScope blocked = null;
		while (true) {
			if (Thread.currentThread().isInterrupted()) {
				throw CapacityRejectedException.serviceUnavailable(blocked == null ? "global" : blocked.scope(),
						retryAfterSeconds(limits.getAcquireTimeoutMs()),
						"Interrupted while waiting for SQL execution capacity");
			}
			Instant retryNow = Instant.now();
			if (circuit.openUntil != null && circuit.openUntil.isAfter(retryNow)) {
				throw CapacityRejectedException.serviceUnavailable("datasource",
						retryAfterSeconds(circuit.openUntil.toEpochMilli() - retryNow.toEpochMilli()),
						"Datasource execution circuit is open");
			}
			List<Semaphore> acquired = new ArrayList<>(4);
			blocked = tryAcquire(globalLimit, acquired, "global", false);
			if (blocked == null) {
				blocked = tryAcquire(datasourceLimit, acquired, "datasource", false);
			}
			if (blocked == null) {
				blocked = tryAcquire(projectLimit, acquired, "project", true);
			}
			if (blocked == null) {
				blocked = tryAcquire(userLimit, acquired, "user", true);
			}
			if (blocked == null) {
				return new Permit(acquired, circuit, limits);
			}
			release(acquired);
			if (Thread.currentThread().isInterrupted()) {
				throw CapacityRejectedException.serviceUnavailable(blocked.scope(),
						retryAfterSeconds(limits.getAcquireTimeoutMs()),
						"Interrupted while waiting for SQL execution capacity");
			}
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0) {
				throw rejected(blocked);
			}
			LockSupport.parkNanos(Math.min(remaining, TimeUnit.MICROSECONDS.toNanos(250)));
		}
	}

	private CapacityScope tryAcquire(Semaphore semaphore, List<Semaphore> acquired, String scope, boolean clientLimit) {
		try {
			if (!semaphore.tryAcquire(0, TimeUnit.NANOSECONDS)) {
				return new CapacityScope(scope, clientLimit);
			}
			acquired.add(semaphore);
			return null;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return new CapacityScope(scope, clientLimit);
		}
	}

	private CapacityRejectedException rejected(CapacityScope blocked) {
		long retryAfter = retryAfterSeconds(limits.getAcquireTimeoutMs());
		if (blocked.clientLimit()) {
			return CapacityRejectedException.tooManyRequests(blocked.scope(), retryAfter,
					blocked.scope() + " SQL concurrency limit reached");
		}
		return CapacityRejectedException.serviceUnavailable(blocked.scope(), retryAfter,
				blocked.scope() + " SQL execution capacity is exhausted");
	}

	private static long retryAfterSeconds(long millis) {
		return Math.max(1, (Math.max(0, millis) + 999) / 1000);
	}

	private static Semaphore semaphore(int permits) {
		return new Semaphore(Math.max(1, permits), true);
	}

	private static void release(List<Semaphore> semaphores) {
		List<Semaphore> reversed = new ArrayList<>(semaphores);
		Collections.reverse(reversed);
		reversed.forEach(Semaphore::release);
	}

	public static final class Permit implements AutoCloseable {

		private final List<Semaphore> semaphores;

		private final Circuit circuit;

		private final SemEvoSQLConcurrencyProperties.SqlLimits limits;

		private boolean closed;

		private Permit(List<Semaphore> semaphores, Circuit circuit, SemEvoSQLConcurrencyProperties.SqlLimits limits) {
			this.semaphores = List.copyOf(semaphores);
			this.circuit = circuit;
			this.limits = limits;
		}

		public void success() {
			circuit.failures.set(0);
			circuit.openUntil = null;
		}

		public void failure() {
			if (circuit.failures.incrementAndGet() >= limits.getFailureThreshold()) {
				circuit.openUntil = Instant.now().plusMillis(limits.getCircuitOpenMs());
			}
		}

		@Override
		public synchronized void close() {
			if (!closed) {
				closed = true;
				release(semaphores);
			}
		}

	}

	private record CapacityScope(String scope, boolean clientLimit) {
	}

	private static final class Circuit {

		private final AtomicInteger failures = new AtomicInteger();

		private volatile Instant openUntil;

	}

}
