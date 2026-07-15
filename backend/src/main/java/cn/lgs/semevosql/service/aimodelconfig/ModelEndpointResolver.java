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
package cn.lgs.semevosql.service.aimodelconfig;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.util.StringUtils;

/** Resolves model Base URLs and endpoint paths without losing an optional Base-URL context path. */
final class ModelEndpointResolver {

	private ModelEndpointResolver() {
	}

	static Endpoint resolve(String baseUrl, String configuredPath, String defaultPath) {
		if (!StringUtils.hasText(baseUrl)) {
			throw new IllegalArgumentException("baseUrl must not be empty");
		}
		if (!StringUtils.hasText(defaultPath)) {
			throw new IllegalArgumentException("defaultPath must not be empty");
		}

		URI base = parseBaseUrl(baseUrl.trim());
		String path = StringUtils.hasText(configuredPath) ? configuredPath.trim() : defaultPath.trim();
		validateEndpointPath(path);

		String origin = base.getScheme().toLowerCase(Locale.ROOT) + "://" + base.getRawAuthority();
		String mergedPath = mergePaths(base.getRawPath(), path);
		return new Endpoint(origin, mergedPath);
	}

	private static URI parseBaseUrl(String value) {
		URI uri;
		try {
			uri = URI.create(value);
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("baseUrl must be a valid HTTP(S) URL", ex);
		}
		String scheme = uri.getScheme();
		if (!StringUtils.hasText(scheme)
				|| !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
			throw new IllegalArgumentException("baseUrl must use http or https");
		}
		if (!StringUtils.hasText(uri.getRawAuthority())) {
			throw new IllegalArgumentException("baseUrl must include a host");
		}
		if (uri.getRawUserInfo() != null) {
			throw new IllegalArgumentException("baseUrl must not contain user information");
		}
		if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
			throw new IllegalArgumentException("baseUrl must not contain a query or fragment");
		}
		return uri;
	}

	private static void validateEndpointPath(String value) {
		if (value.contains("://")) {
			throw new IllegalArgumentException("model endpoint path must be a path, not an absolute URL");
		}
		if (value.indexOf('?') >= 0 || value.indexOf('#') >= 0) {
			throw new IllegalArgumentException("model endpoint path must not contain a query or fragment");
		}
	}

	private static String mergePaths(String basePath, String endpointPath) {
		List<String> base = segments(basePath);
		List<String> endpoint = segments(endpointPath);
		int overlap = maximumOverlap(base, endpoint);
		List<String> merged = new ArrayList<>(base.size() + endpoint.size() - overlap);
		merged.addAll(base);
		merged.addAll(endpoint.subList(overlap, endpoint.size()));
		return merged.isEmpty() ? "/" : "/" + String.join("/", merged);
	}

	private static int maximumOverlap(List<String> base, List<String> endpoint) {
		int maximum = Math.min(base.size(), endpoint.size());
		for (int length = maximum; length > 0; length--) {
			if (base.subList(base.size() - length, base.size()).equals(endpoint.subList(0, length))) {
				return length;
			}
		}
		return 0;
	}

	private static List<String> segments(String path) {
		if (!StringUtils.hasText(path) || "/".equals(path.trim())) {
			return List.of();
		}
		return Arrays.stream(path.trim().split("/+"))
			.filter(StringUtils::hasText)
			.toList();
	}

	record Endpoint(String baseUrl, String path) {
	}

}
