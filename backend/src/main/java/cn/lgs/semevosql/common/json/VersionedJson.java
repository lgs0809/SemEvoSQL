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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Reads legacy v0 JSON, writes v1 envelopes and fails closed on unknown future schemas.
 */
@Component
public class VersionedJson {

	private final JsonPayloadRegistry registry;

	private final CanonicalJson canonicalJson;

	private final ObjectMapper mapper = JsonUtil.getObjectMapper();

	public VersionedJson() {
		this(new JsonPayloadRegistry(), new CanonicalJson());
	}

	@Autowired
	public VersionedJson(JsonPayloadRegistry registry, CanonicalJson canonicalJson) {
		this.registry = registry;
		this.canonicalJson = canonicalJson;
	}

	public String write(String type, Object payload) {
		JsonPayloadRegistry.PayloadDefinition definition = registry.definition(type);
		return canonicalJson.write(new JsonEnvelope<>(definition.currentVersion(), type, payload));
	}

	public <T> T read(String raw, String expectedType, Class<T> targetType) {
		try {
			return mapper.treeToValue(payload(raw, expectedType), targetType);
		}
		catch (RuntimeException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Unable to decode persistent JSON type " + expectedType, ex);
		}
	}

	public <T> T read(String raw, String expectedType, TypeReference<T> targetType) {
		try {
			return mapper.readerFor(targetType).readValue(payload(raw, expectedType));
		}
		catch (RuntimeException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Unable to decode persistent JSON type " + expectedType, ex);
		}
	}

	public JsonNode payload(String raw, String expectedType) {
		if (!StringUtils.hasText(raw)) {
			throw new IllegalArgumentException("Persistent JSON payload is required for " + expectedType);
		}
		JsonPayloadRegistry.PayloadDefinition definition = registry.definition(expectedType);
		try {
			JsonNode root = mapper.readTree(raw);
			int version;
			JsonNode value;
			if (isEnvelope(root)) {
				version = requiredVersion(root);
				String actualType = root.path("type").asText();
				if (!expectedType.equals(actualType)) {
					throw new IllegalArgumentException(
							"Persistent JSON type mismatch: expected=" + expectedType + ", actual=" + actualType);
				}
				value = root.get("payload");
			}
			else {
				version = 0;
				value = root;
			}
			if (version > definition.currentVersion()) {
				throw new IllegalStateException("Unsupported future persistent JSON schema version " + version
						+ " for type " + expectedType + "; current=" + definition.currentVersion());
			}
			while (version < definition.currentVersion()) {
				JsonUpcaster upcaster = registry.upcaster(expectedType, version);
				if (upcaster != null) {
					value = upcaster.upcast(value);
					version = upcaster.toVersion();
				}
				else if (version == 0) {
					// Legacy v0 data used the current payload shape without an envelope.
					version = 1;
				}
				else {
					throw new IllegalStateException("No JSON upcaster registered for " + expectedType + " v" + version);
				}
			}
			return value;
		}
		catch (RuntimeException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalArgumentException("Invalid persistent JSON for type " + expectedType, ex);
		}
	}

	public Map<String, Object> readMap(String raw, String expectedType) {
		return read(raw, expectedType, new TypeReference<>() {
		});
	}

	private boolean isEnvelope(JsonNode root) {
		return root != null && root.isObject() && root.has("schemaVersion") && root.has("type") && root.has("payload");
	}

	private int requiredVersion(JsonNode root) {
		JsonNode value = root.get("schemaVersion");
		if (value == null || !value.canConvertToInt() || value.intValue() < 1) {
			throw new IllegalArgumentException("Persistent JSON envelope has an invalid schemaVersion");
		}
		return value.intValue();
	}

}
