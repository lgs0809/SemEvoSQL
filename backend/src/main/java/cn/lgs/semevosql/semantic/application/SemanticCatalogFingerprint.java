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
package cn.lgs.semevosql.semantic.application;

import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Canonical Semantic Catalog hash shared by release validation and derived retrieval
 * artifacts.
 */
public final class SemanticCatalogFingerprint {

	private SemanticCatalogFingerprint() {
	}

	public static String fingerprint(SemanticCatalogSnapshot snapshot) {
		try {
			ObjectMapper mapper = JsonUtil.getObjectMapper().copy();
			mapper.setConfig(mapper.getSerializationConfig().with(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY));
			mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(snapshot)));
		}
		catch (Exception ex) {
			throw new IllegalStateException("Unable to fingerprint semantic catalog", ex);
		}
	}

}
