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
package cn.lgs.semevosql.retention;

import cn.lgs.semevosql.common.json.CanonicalJson;
import cn.lgs.semevosql.observability.SemEvoSQLMetrics;
import cn.lgs.semevosql.retention.QueryRunRetentionRepository.RetentionBatch;
import cn.lgs.semevosql.retention.QueryRunRetentionRepository.RunArchive;
import cn.lgs.semevosql.retention.QueryRunRetentionRepository.RunArchiveSource;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class QueryRunRetentionService {

	private static final String LEASE_NAME = "run-retention";

	private static final String RETENTION_VERSION = "run-retention-v2";

	private static final int MAX_ERROR_SUMMARY = 4000;

	private final QueryRunRetentionRepository repository;

	private final SemEvoSQLRetentionProperties properties;

	private final CanonicalJson canonicalJson;

	private final TransactionTemplate transactionTemplate;

	private final String instanceId;

	private final SemEvoSQLMetrics metrics;

	private final Clock clock;

	@Autowired
	public QueryRunRetentionService(QueryRunRetentionRepository repository, SemEvoSQLRetentionProperties properties,
			CanonicalJson canonicalJson, PlatformTransactionManager transactionManager,
			@Value("${semevosql.instance-id:local}") String instanceId, SemEvoSQLMetrics metrics) {
		this(repository, properties, canonicalJson, transactionManager, instanceId, metrics, Clock.systemUTC());
	}

	public QueryRunRetentionService(QueryRunRetentionRepository repository, SemEvoSQLRetentionProperties properties,
			CanonicalJson canonicalJson, PlatformTransactionManager transactionManager, String instanceId) {
		this(repository, properties, canonicalJson, transactionManager, instanceId, SemEvoSQLMetrics.noop(),
				Clock.systemUTC());
	}

	QueryRunRetentionService(QueryRunRetentionRepository repository, SemEvoSQLRetentionProperties properties,
			CanonicalJson canonicalJson, PlatformTransactionManager transactionManager, String instanceId,
			Clock clock) {
		this(repository, properties, canonicalJson, transactionManager, instanceId, SemEvoSQLMetrics.noop(), clock);
	}

	QueryRunRetentionService(QueryRunRetentionRepository repository, SemEvoSQLRetentionProperties properties,
			CanonicalJson canonicalJson, PlatformTransactionManager transactionManager, String instanceId,
			SemEvoSQLMetrics metrics, Clock clock) {
		this.repository = repository;
		this.properties = properties;
		this.canonicalJson = canonicalJson;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.instanceId = instanceId;
		this.metrics = metrics;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${semevosql.retention.scan-delay-ms:3600000}")
	public void scheduledRetention() {
		if (!properties.isEnabled()) {
			return;
		}
		String idempotencyKey = "scheduled:" + UUID.randomUUID();
		try {
			execute(idempotencyKey, properties.isDryRun());
		}
		catch (RuntimeException ex) {
			log.error("Scheduled SemEvoSQL run retention failed", ex);
		}
	}

	public RetentionBatch execute(String idempotencyKey, Boolean dryRunOverride) {
		String normalizedKey = normalizeIdempotencyKey(idempotencyKey);
		boolean dryRun = dryRunOverride == null ? properties.isDryRun() : dryRunOverride;
		var existing = repository.findBatchByIdempotencyKey(normalizedKey);
		if (existing.isPresent() && !"RUNNING".equals(existing.get().status())) {
			return requireCompatibleReplay(existing.get(), dryRun);
		}
		LocalDateTime now = LocalDateTime.now(clock);
		String leaseToken = UUID.randomUUID().toString();
		boolean acquired = Boolean.TRUE
			.equals(transactionTemplate.execute(status -> repository.tryAcquireLease(LEASE_NAME, instanceId, leaseToken,
					now, now.plusSeconds(properties.getLeaseDurationSeconds()))));
		if (!acquired) {
			var concurrent = repository.findBatchByIdempotencyKey(normalizedKey);
			if (concurrent.isPresent()) {
				return requireCompatibleReplay(concurrent.get(), dryRun);
			}
			throw new RetentionLeaseUnavailableException("Another retention batch owns the maintenance lease");
		}
		try {
			transactionTemplate.executeWithoutResult(status -> repository.abandonRunningBatches(now));
			return executeUnderLease(normalizedKey, dryRun, now, leaseToken);
		}
		finally {
			transactionTemplate.executeWithoutResult(status -> repository.releaseLease(LEASE_NAME, leaseToken));
		}
	}

	public RetentionBatch findBatch(String batchId) {
		return repository.findBatch(batchId)
			.orElseThrow(() -> new IllegalArgumentException("Retention batch not found: " + batchId));
	}

	public RunArchive findArchive(String runId) {
		return repository.findArchive(runId)
			.orElseThrow(() -> new IllegalArgumentException("Run archive not found: " + runId));
	}

	private RetentionBatch executeUnderLease(String idempotencyKey, boolean dryRun, LocalDateTime startTime,
			String leaseToken) {
		var existing = repository.findBatchByIdempotencyKey(idempotencyKey);
		if (existing.isPresent()) {
			return requireCompatibleReplay(existing.get(), dryRun);
		}
		LocalDateTime batchAuditCutoff = startTime.minusDays(properties.getBatchAuditDays());
		transactionTemplate.executeWithoutResult(status -> repository.deleteFinishedBatchesBefore(batchAuditCutoff));
		LocalDateTime cutoff = startTime.minusDays(properties.getTerminalRunDays());
		String batchId = UUID.randomUUID().toString();
		RetentionBatch started = new RetentionBatch(batchId, idempotencyKey, instanceId, cutoff, dryRun, "RUNNING", 0,
				0, 0, 0, null, startTime, null);
		try {
			repository.insertBatch(started);
		}
		catch (DuplicateKeyException ex) {
			RetentionBatch concurrent = repository.findBatchByIdempotencyKey(idempotencyKey).orElseThrow(() -> ex);
			return requireCompatibleReplay(concurrent, dryRun);
		}

		List<String> candidateIds = List.of();
		int archivedCount = 0;
		int deletedCount = 0;
		List<String> failures = new ArrayList<>();
		try {
			candidateIds = repository.findEligibleRunIds(cutoff, properties.getBatchSize());
			if (dryRun) {
				repository.finishBatch(batchId, "DRY_RUN", candidateIds.size(), 0, 0, 0, null,
						LocalDateTime.now(clock));
				return recordCompletedBatch(batchId);
			}

			for (String runId : candidateIds) {
				renewLease(leaseToken);
				try {
					PurgeResult result = transactionTemplate
						.execute(status -> archiveAndPurge(runId, cutoff, leaseToken));
					if (result != null && result.archived()) {
						archivedCount++;
						deletedCount++;
					}
				}
				catch (RuntimeException ex) {
					failures.add(runId + ":" + safeMessage(ex));
					log.error("Unable to archive and purge SemEvoSQL run {}", runId, ex);
				}
			}
			String finalStatus = failures.isEmpty() ? "SUCCEEDED" : archivedCount > 0 ? "PARTIAL" : "FAILED";
			String errorSummary = truncate(String.join("; ", failures), MAX_ERROR_SUMMARY);
			repository.finishBatch(batchId, finalStatus, candidateIds.size(), archivedCount, deletedCount,
					failures.size(), errorSummary, LocalDateTime.now(clock));
			return recordCompletedBatch(batchId);
		}
		catch (RuntimeException ex) {
			String errorSummary = truncate(safeMessage(ex), MAX_ERROR_SUMMARY);
			boolean finished = repository.finishBatch(batchId, "FAILED", candidateIds.size(), archivedCount,
					deletedCount, failures.size() + 1, errorSummary, LocalDateTime.now(clock));
			if (finished) {
				recordRetentionMetrics(findBatch(batchId));
			}
			throw ex;
		}
	}

	private RetentionBatch recordCompletedBatch(String batchId) {
		RetentionBatch batch = findBatch(batchId);
		recordRetentionMetrics(batch);
		return batch;
	}

	private void recordRetentionMetrics(RetentionBatch batch) {
		metrics.retentionBatch(batch.status(), batch.dryRun(), batch.candidateCount(), batch.archivedCount(),
				batch.failureCount());
	}

	private void renewLease(String leaseToken) {
		LocalDateTime newExpiry = LocalDateTime.now(clock).plusSeconds(properties.getLeaseDurationSeconds());
		boolean renewed = Boolean.TRUE
			.equals(transactionTemplate.execute(status -> repository.renewLease(LEASE_NAME, leaseToken, newExpiry)));
		if (!renewed) {
			throw new RetentionLeaseUnavailableException("Run-retention lease was lost during batch execution");
		}
	}

	private PurgeResult archiveAndPurge(String runId, LocalDateTime cutoff, String leaseToken) {
		if (!repository.lockLease(LEASE_NAME, leaseToken, LocalDateTime.now(clock))) {
			throw new RetentionLeaseUnavailableException("Run-retention lease was lost before purge commit");
		}
		if (!repository.lockRun(runId)) {
			return PurgeResult.skipped();
		}
		RunArchiveSource source = repository.findEligibleRun(runId, cutoff).orElse(null);
		if (source == null) {
			return PurgeResult.skipped();
		}
		LocalDateTime archivedTime = LocalDateTime.now(clock);
		Map<String, Object> payloadManifest = new LinkedHashMap<>();
		payloadManifest.put("requestPayload", source.requestPayload());
		payloadManifest.put("recoveryPayload", source.recoveryPayload());
		payloadManifest.put("executionSnapshot", source.executionSnapshot());
		payloadManifest.put("errorMessage", source.errorMessage());
		payloadManifest.put("lastEventSequence", source.lastEventSequence());
		payloadManifest.put("eventCount", source.eventCount());
		String errorMessageHash = source.errorMessage() == null ? null : canonicalJson.hash(source.errorMessage());
		RunArchive archive = new RunArchive(source.runId(), source.runType(), source.projectId(),
				source.projectVersionId(), source.threadId(), source.status(), source.requestId(),
				source.idempotencyKey(), source.startTime(), source.finishTime(), source.errorCode(), errorMessageHash,
				source.lastEventSequence(), source.eventCount(), source.nodeEffectCount(), source.clarificationCount(),
				source.sourceSubRunCount(), source.artifactCount(), canonicalJson.hash(payloadManifest),
				RETENTION_VERSION, archivedTime);
		repository.insertArchive(archive);
		int deletedRows = repository.purgeRunDetails(runId, source.episodeId(), source.attemptId());
		if (deletedRows < 1) {
			throw new IllegalStateException("Run disappeared before retention purge: " + runId);
		}
		return new PurgeResult(true, deletedRows);
	}

	private RetentionBatch requireCompatibleReplay(RetentionBatch existing, boolean requestedDryRun) {
		if (existing.dryRun() != requestedDryRun) {
			throw new RetentionIdempotencyConflictException(
					"Idempotency-Key was already used with a different dryRun value");
		}
		return existing;
	}

	private String normalizeIdempotencyKey(String value) {
		if (!StringUtils.hasText(value)) {
			throw new IllegalArgumentException("Idempotency-Key is required");
		}
		String normalized = value.trim();
		if (normalized.length() > 160) {
			throw new IllegalArgumentException("Idempotency-Key must not exceed 160 characters");
		}
		return normalized;
	}

	private String safeMessage(RuntimeException ex) {
		return ex.getClass().getSimpleName();
	}

	private String truncate(String value, int maxLength) {
		if (value == null || value.length() <= maxLength) {
			return value;
		}
		return value.substring(0, maxLength);
	}

	record PurgeResult(boolean archived, int deletedRows) {

		static PurgeResult skipped() {
			return new PurgeResult(false, 0);
		}

	}

	public static class RetentionIdempotencyConflictException extends IllegalStateException {

		public RetentionIdempotencyConflictException(String message) {
			super(message);
		}

	}

	public static class RetentionLeaseUnavailableException extends IllegalStateException {

		public RetentionLeaseUnavailableException(String message) {
			super(message);
		}

	}

}
