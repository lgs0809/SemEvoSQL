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
package cn.lgs.semevosql.exception;

import cn.lgs.semevosql.vo.ApiErrorCode;

/** Base exception for stable, user-safe SemEvoSQL runtime failures. */
public abstract class SemEvoSQLException extends RuntimeException {

	private final ApiErrorCode errorCode;

	private final String publicMessage;

	private final boolean retryable;

	protected SemEvoSQLException(ApiErrorCode errorCode, String publicMessage, boolean retryable) {
		super(publicMessage);
		this.errorCode = errorCode;
		this.publicMessage = publicMessage;
		this.retryable = retryable;
	}

	protected SemEvoSQLException(ApiErrorCode errorCode, String publicMessage, boolean retryable, Throwable cause) {
		super(publicMessage, cause);
		this.errorCode = errorCode;
		this.publicMessage = publicMessage;
		this.retryable = retryable;
	}

	public ApiErrorCode getErrorCode() {
		return errorCode;
	}

	public String getPublicMessage() {
		return publicMessage;
	}

	public boolean isRetryable() {
		return retryable;
	}
}
