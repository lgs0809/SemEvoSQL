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
package cn.lgs.semevosql.controller;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lgs.semevosql.vo.ApiErrorCode;
import cn.lgs.semevosql.vo.ApiResponse;
import org.junit.jupiter.api.Test;

class GlobalExceptionHandlerSafetyTest {

	private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

	@Test
	void illegalStateDoesNotExposeTechnicalStateDetails() {
		ApiResponse<Object> response = handler
			.handleIllegalState(new IllegalStateException("catalog hash missing for source subrun artifact-42"));

		assertThat(response.getErrorCode()).isEqualTo(ApiErrorCode.INVALID_STATE);
		assertThat(response.getMessage()).isEqualTo("当前操作无法在此状态下完成，请刷新后重试。");
		assertThat(response.getMessage()).doesNotContain("catalog", "subrun", "artifact-42");
	}

	@Test
	void governedOperationExceptionDoesNotExposeInternalStateDetails() {
		ApiResponse<Object> response = handler
			.handleSecurityException(new SecurityException("Local operation is not allowed in the current governed state"));

		assertThat(response.getErrorCode()).isEqualTo(ApiErrorCode.FORBIDDEN);
		assertThat(response.getMessage()).isEqualTo("当前操作没有足够权限。");
		assertThat(response.getMessage()).doesNotContain("governed state", "Local operation");
	}

	@Test
	void genericIllegalArgumentDoesNotExposeInternalDialectDetails() {
		ApiResponse<Object> response = handler
			.handleIllegalArgument(new IllegalArgumentException("Unsupported SQL dialect: secret-engine"));

		assertThat(response.getErrorCode()).isEqualTo(ApiErrorCode.INVALID_ARGUMENT);
		assertThat(response.getMessage()).isEqualTo("请求参数无效，请检查后重试。");
		assertThat(response.getMessage()).doesNotContain("secret-engine", "SQL dialect");
	}

}
