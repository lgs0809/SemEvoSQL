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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic lexical features shared by Query Case indexing and ranking. */
final class QueryCaseTextFeatures {

	private QueryCaseTextFeatures() {
	}

	static String normalize(String value) {
		return Objects.toString(value, "").trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
	}

	static List<String> tokens(String value) {
		String normalized = normalize(value);
		List<String> tokens = new ArrayList<>();
		StringBuilder latin = new StringBuilder();
		for (int offset = 0; offset < normalized.length();) {
			int codePoint = normalized.codePointAt(offset);
			offset += Character.charCount(codePoint);
			if (Character.isLetterOrDigit(codePoint) && codePoint < 128) {
				latin.appendCodePoint(codePoint);
				continue;
			}
			flushLatin(tokens, latin);
			if (!Character.isWhitespace(codePoint) && !isPunctuation(codePoint)) {
				tokens.add(new String(Character.toChars(codePoint)));
			}
		}
		flushLatin(tokens, latin);
		List<String> expanded = new ArrayList<>(tokens);
		for (int index = 0; index + 1 < tokens.size(); index++) {
			String left = tokens.get(index);
			String right = tokens.get(index + 1);
			if (isCjkToken(left) && isCjkToken(right)) {
				expanded.add(left + right);
			}
		}
		return expanded;
	}

	static Map<String, Integer> termFrequency(String value) {
		Map<String, Integer> frequency = new LinkedHashMap<>();
		for (String token : tokens(value)) {
			frequency.merge(token, 1, Integer::sum);
		}
		return frequency;
	}

	static List<String> queryTerms(String value, int limit) {
		Set<String> ordered = new LinkedHashSet<>();
		List<String> tokens = tokens(value);
		tokens.stream().filter(token -> token.codePointCount(0, token.length()) > 1).forEach(ordered::add);
		tokens.forEach(ordered::add);
		return ordered.stream().limit(Math.max(1, limit)).toList();
	}

	private static void flushLatin(List<String> tokens, StringBuilder latin) {
		if (!latin.isEmpty()) {
			tokens.add(latin.toString());
			latin.setLength(0);
		}
	}

	private static boolean isPunctuation(int codePoint) {
		int type = Character.getType(codePoint);
		return type == Character.CONNECTOR_PUNCTUATION || type == Character.DASH_PUNCTUATION
				|| type == Character.START_PUNCTUATION || type == Character.END_PUNCTUATION
				|| type == Character.INITIAL_QUOTE_PUNCTUATION || type == Character.FINAL_QUOTE_PUNCTUATION
				|| type == Character.OTHER_PUNCTUATION || type == Character.MATH_SYMBOL
				|| type == Character.CURRENCY_SYMBOL || type == Character.MODIFIER_SYMBOL
				|| type == Character.OTHER_SYMBOL;
	}

	private static boolean isCjkToken(String value) {
		return value.codePointCount(0, value.length()) == 1
				&& value.codePoints().allMatch(codePoint -> codePoint >= 0x2E80);
	}

}
