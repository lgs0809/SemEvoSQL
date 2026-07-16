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
package cn.lgs.semevosql.operations;

import cn.lgs.semevosql.project.domain.ProjectVersionStatus;
import cn.lgs.semevosql.project.domain.SemanticProjectRepository;
import cn.lgs.semevosql.project.domain.SemanticProjectVersion;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogRepository;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

/** Published Catalog cache with Caffeine single-flight loading and bounded memory. */
@Service
public class SemanticCatalogCache {

	private final SemanticCatalogRepository repository;

	private final SemanticProjectRepository projectRepository;

	private final Cache<Long, SemanticCatalogSnapshot> cache = Caffeine.newBuilder()
		.maximumSize(100)
		.expireAfterAccess(Duration.ofMinutes(30))
		.recordStats()
		.build();

	private final AtomicInteger loading = new AtomicInteger();

	public SemanticCatalogCache(SemanticCatalogRepository repository, SemanticProjectRepository projectRepository) {
		this.repository = repository;
		this.projectRepository = projectRepository;
	}

	public SemanticCatalogSnapshot get(Long projectId, Long projectVersionId) {
		SemanticProjectVersion version = projectRepository.findVersion(projectVersionId)
			.orElseThrow(() -> new IllegalArgumentException("Project version not found: " + projectVersionId));
		if (!Objects.equals(projectId, version.getProjectId())) {
			throw new IllegalArgumentException("Project version does not belong to project: " + projectVersionId);
		}
		if (version.getStatus() != ProjectVersionStatus.PUBLISHED) {
			return load(projectId, projectVersionId);
		}
		return cache.get(projectVersionId, ignored -> load(projectId, projectVersionId));
	}


	public SemanticCatalogSnapshot warm(Long projectId, Long projectVersionId) {
		return get(projectId, projectVersionId);
	}

	public void invalidate(Long projectVersionId) {
		cache.invalidate(projectVersionId);
	}

	public SemanticCatalogCache.CacheStats stats() {
		cache.cleanUp();
		com.github.benmanes.caffeine.cache.stats.CacheStats stats = cache.stats();
		return new SemanticCatalogCache.CacheStats((int) cache.estimatedSize(), stats.hitCount(), stats.missCount(),
				loading.get(), stats.loadFailureCount(), stats.evictionCount(), stats.totalLoadTime());
	}

	private SemanticCatalogSnapshot load(Long projectId, Long projectVersionId) {
		loading.incrementAndGet();
		try {
			return repository.loadCatalog(projectId, projectVersionId);
		}
		finally {
			loading.decrementAndGet();
		}
	}

	public record CacheStats(int entries, long hits, long misses, int loading, long loadFailures, long evictions,
			long totalLoadTimeNanos) {

		public CacheStats(int entries, long hits, long misses, int loading) {
			this(entries, hits, misses, loading, 0, 0, 0);
		}
	}

}
