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
package cn.lgs.semevosql.dto;

import cn.lgs.semevosql.annotation.InEnum;
import cn.lgs.semevosql.enums.ModelType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelConfigDTO {

	private Integer id;

	@NotBlank(message = "provider must not be empty")
	private String provider; // e.g. "openai", "deepseek"

	private String apiKey;

	private Boolean apiKeyConfigured;

	private String apiKeyHint;

	@NotBlank(message = "baseUrl must not be empty")
	private String baseUrl;

	@NotBlank(message = "modelName must not be empty")
	private String modelName;

	@NotBlank(message = "modelType must not be empty")
	@InEnum(value = ModelType.class, message = "CHAT/EMBEDDING/RERANK 之一")
	private String modelType;

	// 仅当厂商路径非标准时填写，例如 "/custom/chat"
	private String completionsPath;

	// 仅当厂商路径非标准时填写
	private String embeddingsPath;

	@Min(1)
	@Max(65536)
	private Integer embeddingDimensions;

	// Rerank 服务路径；留空时使用常见的 /v1/rerank
	private String rerankPath;

	@Min(value = 1, message = "requestTimeoutSeconds must be at least 1")
	@Max(value = 600, message = "requestTimeoutSeconds must not exceed 600")
	@Builder.Default
	private Integer requestTimeoutSeconds = 60;

	@Builder.Default
	private Double temperature = 0.0;

	@Builder.Default
	private Integer maxTokens = 2000;

	@Builder.Default
	private Boolean isActive = false;

	// 模型代理配置，默认关闭（使用直连）
	@Builder.Default
	private Boolean proxyEnabled = false;

	private String proxyHost;

	private Integer proxyPort;

	private String proxyUsername;

	private String proxyPassword;

	private Boolean proxyPasswordConfigured;

	private String validationStatus;

	private java.time.LocalDateTime lastValidationTime;

}
