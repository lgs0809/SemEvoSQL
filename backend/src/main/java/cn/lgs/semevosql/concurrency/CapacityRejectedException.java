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
package cn.lgs.semevosql.concurrency;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class CapacityRejectedException extends RuntimeException {

	private final HttpStatus status;

	private final long retryAfterSeconds;

	private final String scope;

	public CapacityRejectedException(HttpStatus status, long retryAfterSeconds, String scope, String message) {
		super(message);
		this.status = status;
		this.retryAfterSeconds = retryAfterSeconds;
		this.scope = scope;
	}

	public static CapacityRejectedException tooManyRequests(String scope, long retryAfterSeconds, String message) {
		return new CapacityRejectedException(HttpStatus.TOO_MANY_REQUESTS, Math.max(1, retryAfterSeconds), scope,
				message);
	}

	public static CapacityRejectedException serviceUnavailable(String scope, long retryAfterSeconds, String message) {
		return new CapacityRejectedException(HttpStatus.SERVICE_UNAVAILABLE, Math.max(1, retryAfterSeconds), scope,
				message);
	}

}
