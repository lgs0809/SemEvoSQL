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

import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Enforces field projection and masking before a result can enter an LLM or Episode. */
@Component
public class SensitiveResultSanitizer {

	public void sanitize(ResultSetBO result, SemanticCatalogSnapshot catalog) {
		if (result == null || result.getColumn() == null || result.getData() == null || catalog == null) {
			return;
		}
		Map<String, SemanticCatalogSnapshot.Column> policies = catalog.getColumns()
			.stream()
			.filter(column -> column.getStatus() == SemanticAssetStatus.ENABLED)
			.collect(Collectors.toMap(column -> normalize(column.getColumnName()), Function.identity(),
					(left, right) -> stricter(left, right), LinkedHashMap::new));
		for (String outputColumn : result.getColumn()) {
			SemanticCatalogSnapshot.Column policy = policies.get(normalize(outputColumn));
			if (policy == null) {
				continue;
			}
			if (Boolean.FALSE.equals(policy.getAllowProjection())) {
				throw new SqlGuardViolationException("Projection of governed column is forbidden: " + outputColumn);
			}
			if (Boolean.FALSE.equals(policy.getAllowSendToLlm()) || requiresMasking(policy.getMaskingPolicy())) {
				for (Map<String, String> row : result.getData()) {
					if (row.containsKey(outputColumn)) {
						row.put(outputColumn, mask(row.get(outputColumn), policy.getMaskingPolicy()));
					}
				}
			}
		}
	}

	private SemanticCatalogSnapshot.Column stricter(SemanticCatalogSnapshot.Column left,
			SemanticCatalogSnapshot.Column right) {
		if (Boolean.FALSE.equals(left.getAllowProjection()) || Boolean.FALSE.equals(left.getAllowSendToLlm())
				|| requiresMasking(left.getMaskingPolicy())) {
			return left;
		}
		return right;
	}

	private boolean requiresMasking(String policy) {
		return policy != null && !policy.isBlank() && !"NONE".equalsIgnoreCase(policy);
	}

	private String mask(String value, String policy) {
		if (value == null) {
			return null;
		}
		String normalized = policy == null ? "REDACT" : policy.trim().toUpperCase(Locale.ROOT);
		return switch (normalized) {
			case "LAST4" ->
				value.length() <= 4 ? "****" : "*".repeat(value.length() - 4) + value.substring(value.length() - 4);
			case "SHA256" -> sha256(value);
			default -> "***";
		};
	}

	private String sha256(String value) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to mask governed result", ex);
		}
	}

	private String normalize(String value) {
		if (value == null) {
			return "";
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		int qualifier = normalized.lastIndexOf('.');
		return qualifier < 0 ? normalized : normalized.substring(qualifier + 1);
	}

}
