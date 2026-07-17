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
package cn.lgs.semevosql.service.graph.Context;

import org.springframework.stereotype.Component;

/**
 * Conservative model-independent token estimator. CJK code points count as one token,
 * contiguous ASCII text is estimated at four characters per token, and punctuation counts
 * as one token. It is deliberately approximate because SemEvoSQL supports multiple
 * providers and tokenizers.
 */
@Component
public class ApproximateTokenCounter {

	public int estimate(String value) {
		if (value == null || value.isBlank()) {
			return 0;
		}
		int tokens = 0;
		int asciiRun = 0;
		for (int offset = 0; offset < value.length();) {
			int codePoint = value.codePointAt(offset);
			offset += Character.charCount(codePoint);
			if (Character.isWhitespace(codePoint)) {
				tokens += asciiTokens(asciiRun);
				asciiRun = 0;
			}
			else if (codePoint <= 0x7F && Character.isLetterOrDigit(codePoint)) {
				asciiRun++;
			}
			else {
				tokens += asciiTokens(asciiRun);
				asciiRun = 0;
				tokens++;
			}
		}
		return tokens + asciiTokens(asciiRun);
	}

	private int asciiTokens(int characters) {
		return characters == 0 ? 0 : Math.max(1, (characters + 3) / 4);
	}

}
