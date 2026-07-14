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
package cn.lgs.semevosql.common;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * One-way startup migration for model credentials written before encryption was
 * introduced.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@RequiredArgsConstructor
@ConditionalOnProperty(name = "semevosql.secrets.migrate-plaintext", havingValue = "true", matchIfMissing = true)
public class ModelCredentialEncryptionMigration implements ApplicationRunner {

	private static final String ENCRYPTED_PREFIX = "enc:v1:";

	private final JdbcTemplate jdbc;

	private final SecretCipher secretCipher;

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		if (!secretCipher.isEnabled()) {
			return;
		}
		List<CredentialRow> rows = jdbc.query("SELECT id, api_key, proxy_password FROM model_config", (rs,
				rowNum) -> new CredentialRow(rs.getInt("id"), rs.getString("api_key"), rs.getString("proxy_password")));
		int migrated = 0;
		for (CredentialRow row : rows) {
			String apiKey = encryptLegacy(row.apiKey());
			String proxyPassword = encryptLegacy(row.proxyPassword());
			if (!same(row.apiKey(), apiKey) || !same(row.proxyPassword(), proxyPassword)) {
				jdbc.update(
						"UPDATE model_config SET api_key = ?, proxy_password = ?, updated_time = CURRENT_TIMESTAMP WHERE id = ?",
						apiKey, proxyPassword, row.id());
				migrated++;
			}
		}
		if (migrated > 0) {
			log.info("Encrypted {} legacy model credential record(s)", migrated);
		}
	}

	private String encryptLegacy(String value) {
		return StringUtils.hasText(value) && !value.startsWith(ENCRYPTED_PREFIX) ? secretCipher.encrypt(value) : value;
	}

	private boolean same(String left, String right) {
		return left == null ? right == null : left.equals(right);
	}

	private record CredentialRow(Integer id, String apiKey, String proxyPassword) {
	}

}
