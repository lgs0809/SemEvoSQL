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
package cn.lgs.semevosql.clarification;

import cn.lgs.semevosql.run.QueryRun;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Resolves one stable runtime principal for Project Chat, direct NL2SQL and multi-source
 * runs.
 */
@Service
public class RuntimePrincipalResolver {

	public static final String ANONYMOUS = "anonymous";

	private final JdbcTemplate jdbc;

	public RuntimePrincipalResolver(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public String resolve(QueryRun run) {
		if (run == null) {
			return ANONYMOUS;
		}
		String principal = principalFromPayload(run.requestPayload());
		if (hasText(principal)) {
			return principal.trim();
		}
		if (hasText(run.threadId())) {
			List<String> creators = jdbc.query("""
					SELECT created_by FROM qw_project_conversation
					WHERE conversation_id = ? AND project_id = ? AND status <> 'DELETED'
					LIMIT 1
					""", (rs, rowNum) -> rs.getString("created_by"), run.threadId(), run.projectId());
			if (!creators.isEmpty() && hasText(creators.get(0))) {
				return creators.get(0).trim();
			}
		}
		return ANONYMOUS;
	}

	private String principalFromPayload(String payload) {
        return storedPrincipal(payload, null);
    }

    /** Shared attribution rule for durable query receipts and project contribution evidence. */
    public static String storedPrincipal(String payload, String conversationCreator) {
		if (!hasText(payload)) {
			return hasText(conversationCreator) ? conversationCreator.trim() : null;
		}
		try {
			JsonNode root = JsonUtil.getObjectMapper().readTree(payload);
			for (String field : List.of("principalId", "userId", "createdBy")) {
				String value = root.path(field).asText("").trim();
				if (!value.isBlank()) {
					return value;
				}
			}
			return hasText(conversationCreator) ? conversationCreator.trim() : null;
		}
		catch (Exception ignored) {
			return ANONYMOUS;
		}
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

}
