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

import cn.lgs.semevosql.multisource.MultiSourceRunService;
import cn.lgs.semevosql.multisource.MultiSourceRunService.ResultArtifact;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/semevosql/runs/{runId}/artifacts")
@RequiredArgsConstructor
public class RunArtifactController {

	private final MultiSourceRunService resultArtifactService;

	@GetMapping("/{artifactId}")
	public ResultArtifact get(@PathVariable String runId, @PathVariable String artifactId) {
		ResultArtifact artifact = resultArtifactService.requireArtifact(artifactId);
		if (!runId.equals(artifact.runId())) {
			throw new IllegalArgumentException("Result artifact does not belong to run: " + runId);
		}
		return artifact;
	}
}
