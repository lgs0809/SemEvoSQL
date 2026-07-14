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
package cn.lgs.semevosql.connector.pool;

import cn.lgs.semevosql.bo.DbConfigBO;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidDataSourceFactory;
import cn.lgs.semevosql.enums.BizDataSourceTypeEnum;
import cn.lgs.semevosql.enums.ErrorCodeEnum;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public abstract class AbstractDBConnectionPool implements DBConnectionPool {

	/**
	 * DataSource cache to ensure that each configuration creates DataSource only once.
	 */
	private static final ConcurrentHashMap<String, DataSource> DATA_SOURCE_CACHE = new ConcurrentHashMap<>();

	/**
	 * Driver
	 */
	public abstract String getDriver();

	/**
	 * Error message mapping
	 */
	public abstract ErrorCodeEnum errorMapping(String sqlState);

	public ErrorCodeEnum ping(DbConfigBO config) {
        try (Connection connection = DriverManager.getConnection(config.getUrl(), config.getUsername(), config.getPassword())) {
            if ("postgresql".equalsIgnoreCase(config.getDialectType())
                    || BizDataSourceTypeEnum.isPgDialect(config.getConnectionType())) {
                String schema = config.getSchema();
                if (schema == null || schema.isBlank()) schema = connection.getSchema();
                try (var statement = connection.prepareStatement(
                        "SELECT count(*) FROM information_schema.schemata WHERE schema_name = ?")) {
                    statement.setString(1, schema);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next() || result.getInt(1) == 0) return ErrorCodeEnum.SCHEMA_NOT_EXIST_3D070;
                    }
                }
            }
            return ErrorCodeEnum.SUCCESS;
        } catch (SQLException error) {
            log.warn("Database connection test failed. connectionType={}, sqlState={}",config.getConnectionType(),error.getSQLState());
            return errorMapping(error.getSQLState());
        }
    }

	public Connection getConnection(DbConfigBO config) {

		String jdbcUrl = config.getUrl();
		int maxRetries = 3;
		int retryDelay = 1000; // 1 second

		for (int attempt = 1; attempt <= maxRetries; attempt++) {
			try {
				// Generate cache key based on connection parameters
				String cacheKey = generateCacheKey(jdbcUrl, config.getUsername(), config.getPassword());

				// Use computeIfAbsent to ensure thread safety and avoid duplicate
				// DataSource
				// creation
				DataSource dataSource = DATA_SOURCE_CACHE.computeIfAbsent(cacheKey, key -> {
					try {
						log.debug("Creating new database connection pool. connectionType={}",
								config.getConnectionType());
						return createdDataSource(jdbcUrl, config.getUsername(), config.getPassword());
					}
					catch (Exception e) {
						log.error("Failed to create database connection pool. connectionType={}, errorType={}",
								config.getConnectionType(), e.getClass().getSimpleName());
						throw new RuntimeException("Failed to create DataSource", e);
					}
				});

				// 记录连接池状态
				if (dataSource instanceof DruidDataSource druidDataSource) {
					log.debug("Connection pool status - Active: {}, Idle: {}, Total: {}, WaitCount: {}",
							druidDataSource.getActiveCount(), druidDataSource.getPoolingCount(),
							druidDataSource.getActiveCount() + druidDataSource.getPoolingCount(),
							druidDataSource.getWaitThreadCount());
				}

				return dataSource.getConnection();
			}
			catch (Exception e) {
				log.warn("Database connection attempt {} failed. connectionType={}, errorType={}", attempt,
						config.getConnectionType(), e.getClass().getSimpleName());

				if (attempt == maxRetries) {
					log.error("Failed to get database connection after {} attempts. connectionType={}, errorType={}",
							maxRetries, config.getConnectionType(), e.getClass().getSimpleName());
					throw new RuntimeException("Failed to get database connection after " + maxRetries + " attempts",
							e);
				}

				// Wait before retry with exponential backoff
				try {
					Thread.sleep((long) retryDelay * attempt);
				}
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw new RuntimeException("Interrupted while retrying database connection", interrupted);
				}
			}
		}
		return null;
	}

	/**
	 * Generate cache key based on connection parameters.
	 * @param url the database URL
	 * @param username the database username
	 * @param password the database password
	 * @return the cache key
	 */
	private String generateCacheKey(String url, String username, String password) {
		return url + "|" + username + "|" + Objects.hashCode(password);
	}

	@Override
	public void close() {
		DATA_SOURCE_CACHE.values().forEach(dataSource -> {
			if (dataSource instanceof DruidDataSource) {
				((DruidDataSource) dataSource).close();
			}
		});
		DATA_SOURCE_CACHE.clear();
		log.info("DataSource cache cleared");
	}

	/**
	 * Clear DataSource cache and close all cached DataSource instances. This method is
	 * useful for resource cleanup in special scenarios.
	 */

	public DataSource createdDataSource(String url, String username, String password) throws Exception {

		String driver = getDriver();

		String filters = "wall,stat";
		if (driver != null && driver.toLowerCase().contains("dm.jdbc.driver.dmdriver")) {
			filters = "stat";
		}

		java.util.Map<String, String> props = new java.util.HashMap<>();
		props.put(DruidDataSourceFactory.PROP_DRIVERCLASSNAME, driver);
		props.put(DruidDataSourceFactory.PROP_URL, url);
		props.put(DruidDataSourceFactory.PROP_USERNAME, username);
		props.put(DruidDataSourceFactory.PROP_PASSWORD, password);
		props.put(DruidDataSourceFactory.PROP_INITIALSIZE, "5");
		props.put(DruidDataSourceFactory.PROP_MINIDLE, "5");
		props.put(DruidDataSourceFactory.PROP_MAXACTIVE, "20");
		props.put(DruidDataSourceFactory.PROP_MAXWAIT, "10000");
		props.put(DruidDataSourceFactory.PROP_TIMEBETWEENEVICTIONRUNSMILLIS, "60000");
		props.put(DruidDataSourceFactory.PROP_VALIDATIONQUERY, validationQuery(driver));
		props.put(DruidDataSourceFactory.PROP_TESTWHILEIDLE, "true");
		props.put(DruidDataSourceFactory.PROP_TESTONBORROW, "false");
		props.put(DruidDataSourceFactory.PROP_TESTONRETURN, "false");
		props.put(DruidDataSourceFactory.PROP_FILTERS, filters);

		DruidDataSource dataSource = (DruidDataSource) DruidDataSourceFactory.createDataSource(props);
		if (driver != null && driver.toLowerCase(java.util.Locale.ROOT).contains("postgresql")) {
			var guardedFilters=new java.util.ArrayList<>(dataSource.getProxyFilters());
			guardedFilters.replaceAll(filter -> filter instanceof com.alibaba.druid.wall.WallFilter
					? new PostgreSqlExplainWallFilter() : filter);
			// Druid's setter appends; replace the pre-init list in place to avoid retaining the old wall parser.
			dataSource.getProxyFilters().clear();
			dataSource.setProxyFilters(guardedFilters);
		}
		dataSource.setBreakAfterAcquireFailure(Boolean.TRUE);
		dataSource.setConnectionErrorRetryAttempts(2);

		// 记录数据源创建信息
		log.info(
				"Created new DataSource with optimized parameters - InitialSize: 5, MinIdle: 5, MaxActive: 20, MaxWait: 10000ms");

		return dataSource;
	}

	private String validationQuery(String driver) {
		String normalized = driver == null ? "" : driver.toLowerCase(java.util.Locale.ROOT);
		if (normalized.contains("oracle")) {
			return "SELECT 1 FROM DUAL";
		}
		if (normalized.contains("db2")) {
			return "SELECT 1 FROM SYSIBM.SYSDUMMY1";
		}
		return "SELECT 1";
	}

}
