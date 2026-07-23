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
package cn.lgs.semevosql;

import cn.lgs.semevosql.worker.CodeExecutionWorkerApplication;
import java.util.Arrays;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@MapperScan(basePackages = "cn.lgs.semevosql", annotationClass = Mapper.class)
@SpringBootApplication
public class SemEvoSQLApplication {

	public static void main(String[] args) {
		if ("execution-worker".equals(runtimeRole(args))) {
			CodeExecutionWorkerApplication.run(args);
			return;
		}
		SpringApplication.run(SemEvoSQLApplication.class, args);
	}

	private static String runtimeRole(String[] args) {
		return runtimeRole(args, System.getProperty("semevosql.runtime-role"),
				System.getenv("SEMEVOSQL_RUNTIME_ROLE"));
	}

	static String runtimeRole(String[] args, String systemRole, String environmentRole) {
		String role = systemRole;
		if (role == null || role.isBlank()) {
			role = environmentRole;
		}
		if (role == null || role.isBlank()) {
			role = Arrays.stream(args)
				.filter(arg -> arg.startsWith("--semevosql.runtime-role="))
				.map(arg -> arg.substring("--semevosql.runtime-role=".length()))
				.findFirst()
				.orElse("application");
		}
		return role.trim();
	}

}
