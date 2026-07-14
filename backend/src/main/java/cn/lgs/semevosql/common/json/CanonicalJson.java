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
package cn.lgs.semevosql.common.json;

import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Stable JSON representation used exclusively for fingerprints and persistent envelopes.
 */
@Component
public class CanonicalJson {

	private final ObjectMapper mapper = JsonUtil.getObjectMapper().copy();

	public String write(Object value) {
		try {
			return mapper.writeValueAsString(canonicalNode(normalizeCollections(value)));
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Unable to create canonical JSON", ex);
		}
	}

	public byte[] bytes(Object value) {
		return write(value).getBytes(StandardCharsets.UTF_8);
	}

	public String hash(Object value) {
		try {
			return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(value)));
		}
		catch (Exception ex) {
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	public JsonNode canonicalNode(Object value) {
		return canonicalize(value instanceof JsonNode node ? node : mapper.valueToTree(value));
	}

	private Object normalizeCollections(Object value) {
		if (value == null || value instanceof CharSequence || value instanceof Boolean || value instanceof Number
				|| value instanceof Enum<?>) {
			return value instanceof BigDecimal decimal ? decimal.stripTrailingZeros() : value;
		}
		if (value instanceof Map<?, ?> source) {
			Map<String, Object> sorted = new TreeMap<>();
			source.forEach((key, item) -> sorted.put(String.valueOf(key), normalizeCollections(item)));
			return sorted;
		}
		if (value instanceof Set<?> source) {
			List<Object> sorted = source.stream()
				.map(this::normalizeCollections)
				.collect(java.util.stream.Collectors.toList());
			sorted.sort(Comparator.comparing(this::stableSortKey));
			return sorted;
		}
		if (value instanceof Collection<?> source) {
			List<Object> ordered = new ArrayList<>();
			source.forEach(item -> ordered.add(normalizeCollections(item)));
			return ordered;
		}
		if (value.getClass().isArray()) {
			int length = java.lang.reflect.Array.getLength(value);
			List<Object> ordered = new ArrayList<>(length);
			for (int index = 0; index < length; index++) {
				ordered.add(normalizeCollections(java.lang.reflect.Array.get(value, index)));
			}
			return ordered;
		}
		return value;
	}

	private String stableSortKey(Object value) {
		return mapper.valueToTree(value).toString();
	}

	private JsonNode canonicalize(JsonNode node) {
		if (node == null || node.isNull()) {
			return JsonNodeFactory.instance.nullNode();
		}
		if (node.isObject()) {
			ObjectNode result = JsonNodeFactory.instance.objectNode();
			List<String> names = new ArrayList<>();
			node.fieldNames().forEachRemaining(names::add);
			names.stream().sorted().forEach(name -> result.set(name, canonicalize(node.get(name))));
			return result;
		}
		if (node.isArray()) {
			ArrayNode result = JsonNodeFactory.instance.arrayNode();
			node.forEach(item -> result.add(canonicalize(item)));
			return result;
		}
		if (node.isFloatingPointNumber() || node.isBigDecimal()) {
			return DecimalNode.valueOf(node.decimalValue().stripTrailingZeros());
		}
		return node.deepCopy();
	}

}
