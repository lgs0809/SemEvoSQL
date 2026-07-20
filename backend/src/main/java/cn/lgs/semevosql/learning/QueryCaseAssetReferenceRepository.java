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
package cn.lgs.semevosql.learning;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Persistence boundary for Query Case asset evidence. */
@Repository
public class QueryCaseAssetReferenceRepository {

	private final JdbcTemplate jdbc;

	public QueryCaseAssetReferenceRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<QueryCaseAssetReference> findByCaseId(String queryCaseId) {
		return jdbc.queryForList("""
				SELECT * FROM qw_query_example_asset_ref WHERE query_example_id = ?
				ORDER BY asset_type, asset_key
				""", queryCaseId).stream().map(this::map).toList();
	}

	public List<Map<String, Object>> findRowsByCaseId(String queryCaseId) {
		return jdbc.queryForList("""
				SELECT asset_type, asset_key, asset_fingerprint
				FROM qw_query_example_asset_ref WHERE query_example_id = ?
				ORDER BY asset_type, asset_key
				""", queryCaseId);
	}

	/**
	 * Trusted Query reuse requires explicit governed asset evidence bound to the exact catalog hash.
	 * A hash match alone is not enough for legacy rows that never captured fingerprints.
	 */
	public boolean fingerprintEvidenceCompatible(String queryCaseId, String catalogHash) {
		if (queryCaseId == null || queryCaseId.isBlank() || catalogHash == null || catalogHash.isBlank()) {
			return false;
		}
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT asset_fingerprint, catalog_hash
				FROM qw_query_example_asset_ref
				WHERE query_example_id = ?
				""", queryCaseId);
		return !rows.isEmpty() && rows.stream().allMatch(row -> catalogHash.equals(Objects.toString(row.get("catalog_hash"), "").trim())
				&& !Objects.toString(row.get("asset_fingerprint"), "").isBlank());
	}

	@Transactional
	public void replace(String queryCaseId, String catalogHash, List<ReferenceValue> references) {
		jdbc.update("DELETE FROM qw_query_example_asset_ref WHERE query_example_id = ?", queryCaseId);
		for (ReferenceValue reference : references.stream().distinct().toList()) {
			insert(queryCaseId, catalogHash, reference);
		}
	}

	public void insertIfAbsent(String queryCaseId, String catalogHash, ReferenceValue reference) {
        int inserted = insert(queryCaseId, catalogHash, reference, true);
        if (inserted == 0) {
            Boolean same = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM qw_query_example_asset_ref
                    WHERE query_example_id=? AND asset_type=? AND asset_key=?
                      AND asset_fingerprint=? AND catalog_hash=?)
                """, Boolean.class, queryCaseId, reference.assetType(), reference.assetKey(),
                reference.assetFingerprint(), catalogHash);
            if (!Boolean.TRUE.equals(same)) throw new IllegalStateException("Query case asset evidence changed during idempotent capture");
        }
	}

    private void insert(String queryCaseId, String catalogHash, ReferenceValue reference) {
        insert(queryCaseId, catalogHash, reference, false);
    }

	private int insert(String queryCaseId, String catalogHash, ReferenceValue reference, boolean idempotent) {
		return jdbc.update("""
				INSERT INTO qw_query_example_asset_ref
				(id, query_example_id, asset_type, asset_key, asset_fingerprint, catalog_hash, create_time)
				VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
				""" + (idempotent ? " ON CONFLICT (query_example_id, asset_type, asset_key) DO NOTHING" : ""),
                UUID.randomUUID().toString(), queryCaseId, reference.assetType(), reference.assetKey(),
				reference.assetFingerprint(), catalogHash);
	}

	private QueryCaseAssetReference map(Map<String, Object> row) {
		return new QueryCaseAssetReference(Objects.toString(row.get("id"), null),
				Objects.toString(row.get("query_example_id"), null), Objects.toString(row.get("asset_type"), null),
				Objects.toString(row.get("asset_key"), null), Objects.toString(row.get("asset_fingerprint"), null),
				Objects.toString(row.get("catalog_hash"), null), row);
	}

	public record ReferenceValue(String assetType, String assetKey, String assetFingerprint) {
	}

}
