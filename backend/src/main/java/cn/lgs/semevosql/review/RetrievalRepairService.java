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
package cn.lgs.semevosql.review;

import cn.lgs.semevosql.review.PostExecutionReview.Decision;
import cn.lgs.semevosql.review.PostExecutionReview.IssueType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Builds a bounded retrieval-only repair query from observable review evidence.
 *
 * <p>The returned text is used only to retrieve a new candidate set. Semantic planning continues to
 * receive the original business question, so this service cannot silently rewrite the user's intent or
 * manufacture a semantic binding.</p>
 */
@Service
public class RetrievalRepairService {

	private static final int MAX_FACTS = 12;

	private static final int MAX_EVIDENCE = 12;

	private static final int MAX_ITEM_LENGTH = 240;

	public RepairQuery build(String originalQuery, Collection<String> acceptedFacts, PostExecutionReview review) {
		String query = required(originalQuery, "originalQuery");
		if (review == null || review.decision() != Decision.RERETRIEVE || review.issueType() != IssueType.RETRIEVAL_MISS) {
			throw new IllegalArgumentException("Retrieval repair requires RERETRIEVE + RETRIEVAL_MISS");
		}

		List<String> facts = bounded(acceptedFacts, MAX_FACTS);
		List<String> evidence = bounded(review.evidence(), MAX_EVIDENCE);
		List<String> suspected = bounded(review.suspectedAssetKeys(), MAX_EVIDENCE);

		List<String> hints = new ArrayList<>();
		hints.addAll(evidence);
		if (!suspected.isEmpty()) {
			hints.add("Previously suspected governed assets: " + String.join(", ", suspected));
		}
		if (hints.isEmpty()) {
			hints.add("Search for governed definitions and assets that can explain the semantic mismatch observed after execution.");
		}

		StringBuilder repaired = new StringBuilder(query);
		if (!facts.isEmpty()) {
			repaired.append("\nValidated context: ").append(String.join("; ", facts));
		}
		repaired.append("\nRetrieval repair hint: ").append(String.join("; ", hints));
		String repairedQuery = repaired.toString();
		if (repairedQuery.equals(query)) {
			throw new IllegalStateException("Retrieval repair query must differ from the original query");
		}
		return new RepairQuery(repairedQuery, String.join("; ", hints));
	}

	private List<String> bounded(Collection<?> values, int limit) {
		if (values == null || values.isEmpty()) {
			return List.of();
		}
		return values.stream()
			.map(value -> Objects.toString(value, "").trim())
			.filter(StringUtils::hasText)
			.map(value -> value.length() <= MAX_ITEM_LENGTH ? value : value.substring(0, MAX_ITEM_LENGTH))
			.distinct()
			.limit(limit)
			.toList();
	}

	private String required(String value, String field) {
		if (!StringUtils.hasText(value)) {
			throw new IllegalArgumentException(field + " is required");
		}
		return value.trim();
	}

	public record RepairQuery(String query, String hint) {
	}
}
