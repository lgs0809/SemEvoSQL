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
package cn.lgs.semevosql.config;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class YamlConfigurationTest {

	@ParameterizedTest
	@ValueSource(strings = { "application.yml", "application-local.yml", "application-prod.yml" })
	void configurationFilesRejectDuplicateKeys(String resource) {
		assertThatCode(() -> parse(resource)).doesNotThrowAnyException();
	}

	private void parse(String resource) throws IOException {
		LoaderOptions options = new LoaderOptions();
		options.setAllowDuplicateKeys(false);
		Yaml yaml = new Yaml(new SafeConstructor(options));
		try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
			if (input == null) {
				throw new IOException("Missing classpath resource " + resource);
			}
			yaml.loadAll(input).forEach(document -> {
				// Parsing with duplicate keys disabled is the assertion.
			});
		}
	}
}
