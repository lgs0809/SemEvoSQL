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

/** The model returned content that cannot be consumed by a governed parser. */
public class ModelOutputInvalidException extends SemEvoSQLException {

	public ModelOutputInvalidException(String publicMessage, Throwable cause) {
		super(ApiErrorCode.MODEL_OUTPUT_INVALID, publicMessage, true, cause);
	}
}
