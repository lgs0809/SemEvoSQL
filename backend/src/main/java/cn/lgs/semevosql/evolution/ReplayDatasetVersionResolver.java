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
package cn.lgs.semevosql.evolution;

import java.util.Objects;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Resolves the frozen dataset mounted for FIXTURE replay and enforces exact version
 * matching.
 */
@Component
public class ReplayDatasetVersionResolver {

	private final Environment environment;

	public ReplayDatasetVersionResolver(Environment environment) {
		this.environment = environment;
	}

	public String requireMatch(Long projectId, String expectedDatasetVersion) {
		if (!StringUtils.hasText(expectedDatasetVersion)) {
			throw new IllegalStateException("FIXTURE Golden Case requires datasetVersion");
		}
		String projectKey = projectId == null ? null
				: environment.getProperty("semevosql.replay.fixture-dataset-versions." + projectId);
		String active = StringUtils.hasText(projectKey) ? projectKey
				: environment.getProperty("semevosql.replay.fixture-dataset-version");
		if (!StringUtils.hasText(active)) {
			throw new IllegalStateException("No active FIXTURE dataset version is configured for project " + projectId);
		}
		if (!Objects.equals(expectedDatasetVersion.trim(), active.trim())) {
			throw new IllegalStateException(
					"FIXTURE datasetVersion mismatch: expected=" + expectedDatasetVersion + ", active=" + active);
		}
		return active.trim();
	}

}
