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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Unique authority for every persisted JSON payload type and current schema version. */
@Component
public class JsonPayloadRegistry {

	public static final String SEMANTIC_QUERY_PLAN = "SEMANTIC_QUERY_PLAN";

	public static final String SEMANTIC_PATCH = "SEMANTIC_PATCH";

	public static final String MULTI_SOURCE_POLICY_PATCH = "MULTI_SOURCE_POLICY_PATCH";

	public static final String QUERY_CASE_QUALITY_PROOF = "QUERY_CASE_QUALITY_PROOF";

	public static final String GOLDEN_CASE_EXPECTED = "GOLDEN_CASE_EXPECTED";

	public static final String EXECUTION_SNAPSHOT = "EXECUTION_SNAPSHOT";

	public static final String EVALUATION_CHECKPOINT = "EVALUATION_CHECKPOINT";

	public static final String REPLAY_SUMMARY = "REPLAY_SUMMARY";

	private final Map<String, PayloadDefinition> definitions = new LinkedHashMap<>();

	private final Map<UpcastKey, JsonUpcaster> upcasters = new LinkedHashMap<>();

	public JsonPayloadRegistry() {
		this(List.of());
	}

	@Autowired
	public JsonPayloadRegistry(List<JsonUpcaster> configuredUpcasters) {
		register(SEMANTIC_QUERY_PLAN, 1);
		register(SEMANTIC_PATCH, 1);
		register(MULTI_SOURCE_POLICY_PATCH, 1);
		register(QUERY_CASE_QUALITY_PROOF, 1);
		register(GOLDEN_CASE_EXPECTED, 1);
		register(EXECUTION_SNAPSHOT, 1);
		register(EVALUATION_CHECKPOINT, 1);
		register(REPLAY_SUMMARY, 1);
		for (JsonUpcaster upcaster : configuredUpcasters) {
			register(upcaster);
		}
	}

	public synchronized void register(String type, int currentVersion) {
		if (!StringUtils.hasText(type) || currentVersion < 1) {
			throw new IllegalArgumentException("Persistent JSON type and positive currentVersion are required");
		}
		PayloadDefinition prior = definitions.putIfAbsent(type, new PayloadDefinition(type, currentVersion));
		if (prior != null) {
			throw new IllegalStateException("Persistent JSON payload type is already registered: " + type);
		}
	}

	public synchronized void register(JsonUpcaster upcaster) {
		Objects.requireNonNull(upcaster, "upcaster");
		definition(upcaster.type());
		if (upcaster.fromVersion() < 0 || upcaster.toVersion() != upcaster.fromVersion() + 1) {
			throw new IllegalArgumentException("JSON upcasters must advance exactly one schema version");
		}
		UpcastKey key = new UpcastKey(upcaster.type(), upcaster.fromVersion());
		if (upcasters.putIfAbsent(key, upcaster) != null) {
			throw new IllegalStateException("Duplicate JSON upcaster registration: " + key);
		}
	}

	public PayloadDefinition definition(String type) {
		PayloadDefinition definition = definitions.get(type);
		if (definition == null) {
			throw new IllegalArgumentException("Unknown persistent JSON payload type: " + type);
		}
		return definition;
	}

	public JsonUpcaster upcaster(String type, int fromVersion) {
		return upcasters.get(new UpcastKey(type, fromVersion));
	}

	public Map<String, PayloadDefinition> definitions() {
		return Map.copyOf(definitions);
	}

	public record PayloadDefinition(String type, int currentVersion) {
	}

	private record UpcastKey(String type, int fromVersion) {
	}

}
