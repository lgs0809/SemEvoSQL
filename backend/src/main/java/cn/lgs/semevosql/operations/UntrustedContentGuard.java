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

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Neutralizes instruction-like text in evidence. This never changes executable policies,
 * tool permissions, datasource scope or graph routing.
 */
@Component
public class UntrustedContentGuard {

	private static final Pattern INSTRUCTION = Pattern
		.compile("(?i)(ignore\\s+(all\\s+)?previous|system\\s+prompt|developer\\s+message|绕过|忽略.{0,12}(限制|指令)|"
				+ "调用.{0,12}(工具|tool)|查询.{0,12}(密码|password)|disable.{0,12}(guard|policy))");

	public String sanitizeEvidence(String value) {
		if (value == null || value.isBlank()) {
			return value;
		}
		return INSTRUCTION.matcher(value).replaceAll("[UNTRUSTED_INSTRUCTION_REMOVED]");
	}

	public String wrapEvidence(String value) {
		return "<untrusted-semantic-evidence>\n" + sanitizeEvidence(value) + "\n</untrusted-semantic-evidence>";
	}

	public boolean containsInstructionLikeText(String value) {
		return value != null && INSTRUCTION.matcher(value).find();
	}

}
