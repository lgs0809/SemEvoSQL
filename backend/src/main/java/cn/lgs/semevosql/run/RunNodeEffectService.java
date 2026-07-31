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
package cn.lgs.semevosql.run;

import cn.lgs.semevosql.run.RunNodeEffectRepository.RunNodeEffect;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RunNodeEffectService {

	private final RunNodeEffectRepository repository;

	private final RunExecutionFenceService executionFence;

	public Optional<String> completedPayload(String runId, String nodeKey, String inputHash) {
		if (runId == null || runId.isBlank()) {
			return Optional.empty();
		}
		return repository.findCompleted(runId, nodeKey, inputHash).map(RunNodeEffect::resultJson);
	}

	@Transactional
	public void recordCompleted(String runId, String nodeKey, String inputHash, String resultJson) {
		recordCompleted(runId, null, nodeKey, inputHash, resultJson);
	}

	@Transactional
	public void recordCompleted(String runId, String attemptId, String nodeKey, String inputHash, String resultJson) {
		if (runId == null || runId.isBlank()) {
			return;
		}
		repository.lockRun(runId);
		if (attemptId != null && !attemptId.isBlank()) {
			// The run row is already locked, so the status/attempt check and this effect write are one atomic
			// transaction. A terminal transition cannot slip between the check and the insert/replace below.
			executionFence.assertActive(runId, attemptId);
		}
		RunNodeEffect existing = repository.find(runId, nodeKey).orElse(null);
		if (existing == null) {
			repository.insertCompleted(new RunNodeEffect(UUID.randomUUID().toString(), runId, nodeKey, inputHash,
					"COMPLETED", resultJson, null, null, null));
			return;
		}
		if ("COMPLETED".equals(existing.status()) && inputHash.equals(existing.inputHash())) {
			return;
		}
		if (repository.replaceCompleted(runId, nodeKey, inputHash, resultJson) != 1) {
			throw new IllegalStateException("Unable to persist completed node effect: " + runId + "/" + nodeKey);
		}
	}

	public String inputHash(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

}
