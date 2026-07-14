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

import com.alibaba.druid.pool.DruidDataSource;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Creates the metadata datasource with health validation configured before first use. */
@Configuration(proxyBeanMethods = false)
public class MetadataDataSourceConfiguration {

	@Bean(destroyMethod = "close")
	@Primary
	@ConfigurationProperties("spring.datasource")
	public DataSource dataSource(DataSourceProperties properties) {
		DruidDataSource dataSource = new DruidDataSource();
		dataSource.setUrl(properties.determineUrl());
		dataSource.setUsername(properties.determineUsername());
		dataSource.setPassword(properties.determinePassword());
		dataSource.setDriverClassName(properties.determineDriverClassName());
		dataSource.setValidationQuery(validationQuery(properties.determineDriverClassName()));
		dataSource.setTestWhileIdle(true);
		dataSource.setTestOnBorrow(false);
		dataSource.setTestOnReturn(false);
		return dataSource;
	}

	private String validationQuery(String driverClassName) {
		String driver = driverClassName == null ? "" : driverClassName.toLowerCase(Locale.ROOT);
		if (driver.contains("oracle")) {
			return "SELECT 1 FROM DUAL";
		}
		if (driver.contains("db2")) {
			return "SELECT 1 FROM SYSIBM.SYSDUMMY1";
		}
		return "SELECT 1";
	}

}
