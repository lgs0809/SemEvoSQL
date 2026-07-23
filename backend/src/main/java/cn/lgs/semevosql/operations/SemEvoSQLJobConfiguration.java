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
package cn.lgs.semevosql.operations;

import cn.lgs.semevosql.common.OperatorContextProperties;
import cn.lgs.semevosql.common.SecretEncryptionProperties;
import cn.lgs.semevosql.concurrency.SemEvoSQLConcurrencyProperties;
import cn.lgs.semevosql.learning.QueryCaseGovernanceProperties;
import cn.lgs.semevosql.retention.SemEvoSQLRetentionProperties;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableConfigurationProperties({ SemEvoSQLConcurrencyProperties.class, QueryCaseGovernanceProperties.class,
		SemEvoSQLRetentionProperties.class, OperatorContextProperties.class, SecretEncryptionProperties.class })
public class SemEvoSQLJobConfiguration {

	@Bean("semEvoSQLInteractiveExecutor")
	public Executor interactiveExecutor(SemEvoSQLConcurrencyProperties properties) {
		return executor("semevosql-interactive-", properties.getInteractiveQuery());
	}

	@Bean("semEvoSQLInitializationExecutor")
	public Executor initializationExecutor(SemEvoSQLConcurrencyProperties properties) {
		return executor("semevosql-initialization-", properties.getInitialization());
	}

	@Bean({ "semEvoSQLEvaluationExecutor", "semEvoSQLJobExecutor" })
	public Executor evaluationExecutor(SemEvoSQLConcurrencyProperties properties) {
		return executor("semevosql-evaluation-", properties.getEvaluation());
	}

    @Bean("semEvoSQLIndexExecutor")
    public Executor indexExecutor() {
        return backgroundIndexExecutor("semevosql-index-");
    }

    @Bean("semEvoSQLCaseIndexExecutor")
    public Executor caseIndexExecutor() {
        return backgroundIndexExecutor("semevosql-case-index-");
    }

    @Bean("semEvoSQLPersonalDefinitionExecutor")
    public Executor personalDefinitionExecutor(){return backgroundIndexExecutor("semevosql-personal-definition-");}

    @Bean("semEvoSQLPersonalIndexExecutor")
    public Executor personalIndexExecutor(){return backgroundIndexExecutor("semevosql-personal-index-");}

    @Bean("semEvoSQLProjectDefinitionIndexExecutor")
    public Executor projectDefinitionIndexExecutor(){return backgroundIndexExecutor("semevosql-project-definition-index-");}

    @Bean("semEvoSQLProjectDefinitionAssessmentExecutor")
    public Executor projectDefinitionAssessmentExecutor(){return backgroundIndexExecutor("semevosql-project-definition-assessment-");}

    @Bean("semEvoSQLProjectDefinitionPublicationExecutor")
    public Executor projectDefinitionPublicationExecutor(){return backgroundIndexExecutor("semevosql-project-definition-publication-");}

    @Bean("semEvoSQLDefinitionRecallExecutor")
    public Executor definitionRecallExecutor() {
        ThreadPoolTaskExecutor executor=new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);executor.setMaxPoolSize(2);executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("semevosql-definition-recall-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());executor.initialize();return executor;
    }

    @Bean("semEvoSQLModelValidationExecutor")
    public Executor modelValidationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("semevosql-model-validation-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    private Executor backgroundIndexExecutor(String prefix) {
        ThreadPoolTaskExecutor executor=new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);executor.setMaxPoolSize(1);executor.setQueueCapacity(0);
        executor.setThreadNamePrefix(prefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();return executor;
    }

	@Bean("semEvoSQLSqlExecutor")
	public Executor sqlExecutor(SemEvoSQLConcurrencyProperties properties) {
		return executor("semevosql-sql-", properties.getSqlExecution());
	}

	private Executor executor(String prefix, SemEvoSQLConcurrencyProperties.Pool pool) {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(pool.getMaxConcurrent());
		executor.setMaxPoolSize(pool.getMaxConcurrent());
		executor.setQueueCapacity(pool.getQueueCapacity());
		executor.setThreadNamePrefix(prefix);
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(30);
		executor.initialize();
		return executor;
	}

}
