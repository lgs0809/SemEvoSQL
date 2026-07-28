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
package cn.lgs.semevosql.properties;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "semevosql.model-client")
public class ModelClientProperties {

	/** Number of retries after an initial pre-response WebClient connection failure. */
	@Min(0)
	private int connectionMaxRetries = 3;

	private Duration connectionInitialBackoff = Duration.ofMillis(250);

	private Duration connectionMaxBackoff = Duration.ofSeconds(2);

	@DecimalMin("0.0")
	@DecimalMax("1.0")
	private double connectionRetryJitter = 0.2d;

	/** Default request timeout when a persisted model configuration does not override it. */
	private Duration requestTimeout = Duration.ofSeconds(60);

	/** How long a successful connectivity validation may be treated as current readiness evidence. */
	private Duration validationFreshness = Duration.ofHours(6);

}
