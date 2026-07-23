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
package cn.lgs.semevosql.trajectory;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/semevosql")
@RequiredArgsConstructor
public class TrajectoryController {

	private final TrajectoryAnalysisService service;

	@PostMapping("/operations/episodes/{episodeId}/trajectory/analyze")
	public List<Map<String, Object>> analyze(@PathVariable String episodeId) {
		return service.analyzeEpisode(episodeId);
	}

	@GetMapping("/projects/{projectId}/trajectory/patterns")
	public List<Map<String, Object>> patterns(@PathVariable Long projectId,
			@RequestParam(required = false) Long projectVersionId,
			@RequestParam(defaultValue = "50") @Min(1) @Max(500) int limit) {
		return service.listPatterns(projectId, projectVersionId, limit);
	}

	@GetMapping("/trajectory/patterns/{patternId}")
	public Map<String, Object> pattern(@PathVariable String patternId) {
		return service.pattern(patternId);
	}

	@GetMapping("/trajectory/patterns/{patternId}/paths")
	public List<Map<String, Object>> paths(@PathVariable String patternId,
			@RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
		return service.listPaths(patternId, limit);
	}

	@PostMapping("/trajectory/patterns/{patternId}/recompute")
	public Map<String, Object> recompute(@PathVariable String patternId) {
		return service.recomputePattern(patternId);
	}

	@GetMapping("/projects/{projectId}/trajectory/detours")
	public List<Map<String, Object>> detours(@PathVariable Long projectId,
			@RequestParam(required = false) String status,
			@RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
		return service.listDetours(projectId, status, limit);
	}

}
