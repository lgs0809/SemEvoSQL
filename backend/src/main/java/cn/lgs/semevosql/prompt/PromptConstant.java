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
package cn.lgs.semevosql.prompt;

import org.springframework.ai.chat.prompt.PromptTemplate;

/**
 * Prompt constant class, dynamically loads prompt files
 *
 */
public class PromptConstant {

	public static PromptTemplate getQueryEnhancementPromptTemplate() {
		return new PromptTemplate(PromptLoader.loadPrompt("query-enhancement"));
	}

	// feasibility-assessment
	public static PromptTemplate getFeasibilityAssessmentPromptTemplate() {
		return new PromptTemplate(PromptLoader.loadPrompt("feasibility-assessment"));
	}

	public static PromptTemplate getMixSelectorPromptTemplate() {
		return new PromptTemplate(PromptLoader.loadPrompt("mix-selector"));
	}

	public static PromptTemplate getSemanticConsistencyPromptTemplate() {
		return governedSqlTemplate("semantic-consistency");
	}

	public static PromptTemplate getNewSqlGeneratorPromptTemplate() {
		return governedSqlTemplate("new-sql-generate");
	}

	public static PromptTemplate getPlannerPromptTemplate() {
		return governedSqlTemplate("planner");
	}

	public static PromptTemplate getReportGeneratorPlainPromptTemplate() {
		return new PromptTemplate(PromptLoader.loadPrompt("report-generator-plain"));
	}

	public static PromptTemplate getSqlErrorFixerPromptTemplate() {
		return governedSqlTemplate("sql-error-fixer");
	}

    private static PromptTemplate governedSqlTemplate(String name) {
        return new PromptTemplate(PromptLoader.loadPrompt(name) + "\n\n"
            + PromptLoader.loadPrompt("sql-structure-policy"));
    }

	public static PromptTemplate getPythonGeneratorPromptTemplate() {
		return new PromptTemplate(PromptLoader.loadPrompt("python-generator"));
	}

	public static PromptTemplate getPythonAnalyzePromptTemplate() {
		return new PromptTemplate(PromptLoader.loadPrompt("python-analyze"));
	}

	public static PromptTemplate getSemanticCatalogPromptTemplate() {
		return new PromptTemplate(PromptLoader.loadPrompt("semantic-catalog"));
	}

	public static PromptTemplate getJsonFixPromptTemplate() {
		return new PromptTemplate(PromptLoader.loadPrompt("json-fix"));
	}

	public static PromptTemplate getDataViewAnalyzePromptTemplate() {
		return new PromptTemplate(PromptLoader.loadPrompt("data-view-analyze"));
	}

}
