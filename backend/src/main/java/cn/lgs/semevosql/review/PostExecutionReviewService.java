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
package cn.lgs.semevosql.review;

import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.review.PostExecutionReview.Decision;
import cn.lgs.semevosql.review.PostExecutionReview.IssueType;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.sql.application.SqlResultValidator;
import cn.lgs.semevosql.sql.application.SqlResultValidator.ValidationMode;
import cn.lgs.semevosql.sql.application.SqlResultValidator.ValidationResult;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Deterministic-first result acceptance followed by an optional constrained semantic reviewer. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostExecutionReviewService {

	private final SqlResultValidator resultValidator;

	private final SemanticResultReviewer semanticReviewer;

	private final PostExecutionReviewProperties properties;

	public PostExecutionReview review(String question, SemanticBlueprint plan, String sql, ResultSetBO resultSet,
			int configuredMaxRows) {
		return review(question, plan, sql, resultSet, configuredMaxRows, "", ReviewMode.CONFIGURED);
	}

	public PostExecutionReview review(String question, SemanticBlueprint plan, String sql, ResultSetBO resultSet,
			int configuredMaxRows, ReviewMode mode) {
		return review(question, plan, sql, resultSet, configuredMaxRows, "", mode);
	}

	public PostExecutionReview review(String question, SemanticBlueprint plan, String sql, ResultSetBO resultSet,
			int configuredMaxRows, String executionPlan, ReviewMode mode) {
		return review(question, plan, sql, resultSet, configuredMaxRows, executionPlan, mode,
				ValidationMode.STRICT_SEMANTIC_PLAN);
	}

	public PostExecutionReview review(String question, SemanticBlueprint plan, String sql, ResultSetBO resultSet,
			int configuredMaxRows, String executionPlan, ReviewMode mode, ValidationMode validationMode) {
		return review(question, plan, sql, resultSet, configuredMaxRows, executionPlan, mode, validationMode, List.of());
	}

	public PostExecutionReview review(String question, SemanticBlueprint plan, String sql, ResultSetBO resultSet,
			int configuredMaxRows, String executionPlan, ReviewMode mode, ValidationMode validationMode,
			List<String> contextualWarnings) {
		return review(question, plan, sql, resultSet, configuredMaxRows, executionPlan, mode, validationMode,
				contextualWarnings, null);
	}

	public PostExecutionReview review(String question, SemanticBlueprint plan, String sql, ResultSetBO resultSet,
			int configuredMaxRows, String executionPlan, ReviewMode mode, ValidationMode validationMode,
			List<String> contextualWarnings, Long runDeadlineEpochMillis) {
		ValidationResult deterministic = resultValidator.validate(resultSet, plan, configuredMaxRows, validationMode);
		List<String> warnings = new ArrayList<>(deterministic.warnings());
		if (contextualWarnings != null) {
			contextualWarnings.stream().filter(value -> value != null && !value.isBlank()).forEach(warnings::add);
		}
		warnings = List.copyOf(new java.util.LinkedHashSet<>(warnings));
		PostExecutionReview preliminary = deterministic.valid() ? PostExecutionReview.deterministicPass(warnings)
				: PostExecutionReview.deterministicRetry(deterministic.errors(), warnings);
		// Known-invalid data cannot be accepted by a model. Repair it before spending
		// another model call; a valid replacement still receives every required semantic review.
		if (!deterministic.valid()) {
			return preliminary;
		}
		boolean contextualReviewRequired = contextualWarnings != null && !contextualWarnings.isEmpty();
        // A generated/adjusted SQL may retain an originally deterministic Blueprint.
        // Its actual execution path, rather than the original lowering hint, determines review admission.
        boolean required = validationMode == ValidationMode.ADVANCED_EXECUTION
            || contextualReviewRequired || shouldRunSemanticReviewer(
            mode == ReviewMode.DETERMINISTIC_ONLY ? ReviewMode.CONFIGURED : mode, deterministic, plan);
        if (mode == ReviewMode.DETERMINISTIC_ONLY && required) {
            throw new IllegalStateException("Required semantic review cannot be bypassed by deterministic-only mode");
        }
		if (!required) {
			return preliminary;
		}
		try {
			PostExecutionReview reviewed = runDeadlineEpochMillis == null
					? semanticReviewer.review(question, plan, sql, resultSet, executionPlan, deterministic.errors(), warnings,
							validationMode == ValidationMode.ADVANCED_EXECUTION)
					: semanticReviewer.review(question, plan, sql, resultSet, executionPlan, deterministic.errors(), warnings,
							validationMode == ValidationMode.ADVANCED_EXECUTION, runDeadlineEpochMillis);
			PostExecutionReview normalized = normalize(reviewed, deterministic, warnings, validationMode, question,
					executionPlan, sql);
			return normalizeGovernedEmptyMerge(normalized, deterministic, warnings, plan, resultSet);
		}
		catch (RuntimeException ex) {
            if (ex instanceof cn.lgs.semevosql.run.RunDeadlineExceededException
                    || ex instanceof cn.lgs.semevosql.run.LateRunResultDroppedException) throw ex;
            throw new IllegalStateException("Required semantic post-execution review is unavailable or invalid; result is not accepted", ex);
		}
	}

	public boolean shouldRunSemanticReviewer(ReviewMode mode, ValidationResult deterministic, SemanticBlueprint plan) {
		ReviewMode effective = mode == null ? ReviewMode.CONFIGURED : mode;
		if (effective == ReviewMode.DETERMINISTIC_ONLY) {
			return false;
		}
		if (effective == ReviewMode.SEMANTIC_ALWAYS || generatedSql(plan)) {
			return true;
		}
		if (!properties.isSemanticEnabled()) {
			return false;
		}
		if (properties.isAlwaysSemanticReview() || !deterministic.valid() || !deterministic.warnings().isEmpty()) {
			return true;
		}
		return complexPlan(plan);
	}

	private boolean complexPlan(SemanticBlueprint plan) {
		if (plan == null) {
			return false;
		}
		return plan.getMetrics().size() > 1 || plan.getDimensions().size() > 1 || !plan.getRelationships().isEmpty()
				|| !plan.getRules().isEmpty() || plan.getSourceSubPlans().size() > 1;
	}

    private boolean generatedSql(SemanticBlueprint plan) {
        return plan != null && ("CONSTRAINED_GENERATION".equalsIgnoreCase(plan.getCompilerMode())
            || "SEMANTIC_SQL".equalsIgnoreCase(plan.getCompilerMode()));
    }

	private PostExecutionReview normalizeGovernedEmptyMerge(PostExecutionReview reviewed, ValidationResult deterministic,
			List<String> warnings, SemanticBlueprint plan, ResultSetBO resultSet) {
		boolean emptyResult = resultSet != null && (resultSet.getData() == null || resultSet.getData().isEmpty());
		boolean governedMultiSourceMerge = plan != null && plan.getMergePlan() != null && plan.getSourceSubPlans() != null
				&& plan.getSourceSubPlans().size() > 1;
		boolean emptyMergeRepairObjection = reviewed.decision() == Decision.RETRY_SQL
				&& reviewed.issueType() == IssueType.SQL_REPAIRABLE;
		boolean emptyMergeShapeObjection = reviewed.decision() == Decision.REPLAN_EXECUTION
				&& reviewed.issueType() == IssueType.RESULT_SHAPE_MISMATCH;
		if (!deterministic.valid() || !emptyResult || !governedMultiSourceMerge
				|| (!emptyMergeRepairObjection && !emptyMergeShapeObjection)) {
			return reviewed;
		}
		List<String> normalizedWarnings = new ArrayList<>(warnings);
		normalizedWarnings.add(
				"Governed multi-source merge returned no matching relationship keys; deterministic final-result validation passed, so the empty result was retained instead of repair/replan");
		return new PostExecutionReview(Decision.PASS, IssueType.NONE, reviewed.confidence(), SetSupport.empty(),
				reviewed.evidence(), deterministic.errors(), List.copyOf(normalizedWarnings), reviewed.semanticReviewerUsed(),
				reviewed.modelEvidence());
	}

	private PostExecutionReview normalize(PostExecutionReview reviewed, ValidationResult deterministic, List<String> warnings,
			ValidationMode validationMode, String question, String executionPlan, String sql) {
		if (!deterministic.valid() && reviewed.decision() == Decision.PASS) {
			return PostExecutionReview.deterministicRetry(deterministic.errors(), warnings);
		}
		if (validationMode == ValidationMode.ADVANCED_EXECUTION && deterministic.valid()
				&& reviewed.decision() == Decision.REPLAN_EXECUTION
				&& reviewed.issueType() == IssueType.RESULT_SHAPE_MISMATCH
				&& requiredAdvancedShapeIsObservable((question == null ? "" : question) + "\n"
						+ (executionPlan == null ? "" : executionPlan), sql)) {
			List<String> normalizedWarnings = new ArrayList<>(warnings);
			normalizedWarnings.add("Semantic reviewer shape objection ignored because deterministic validation passed and the planner-required advanced SQL structure is observable");
			return new PostExecutionReview(Decision.PASS, IssueType.NONE, reviewed.confidence(), SetSupport.empty(),
					reviewed.evidence(), deterministic.errors(), List.copyOf(normalizedWarnings), true, reviewed.modelEvidence());
		}
		if (reviewed.decision() == Decision.PASS && reviewed.issueType() != IssueType.NONE) {
			return new PostExecutionReview(Decision.PASS, IssueType.NONE, reviewed.confidence(), SetSupport.empty(),
					reviewed.evidence(), deterministic.errors(), warnings, true, reviewed.modelEvidence());
		}
		return reviewed;
	}

	static boolean requiredAdvancedShapeIsObservable(String executionPlan, String sql) {
		String plan = executionPlan == null ? "" : executionPlan.toUpperCase(java.util.Locale.ROOT);
		String statement = sql == null ? "" : sql.toUpperCase(java.util.Locale.ROOT);
		boolean requiresKnownAdvancedOperator = false;
		for (String operator : List.of("LAG", "LEAD", "ROW_NUMBER", "RANK", "DENSE_RANK")) {
			if (plan.contains(operator)) {
				requiresKnownAdvancedOperator = true;
				if (!statement.contains(operator + "(")) {
					return false;
				}
			}
		}
		if (plan.contains("PARTITION BY")) {
			requiresKnownAdvancedOperator = true;
			if (!statement.contains("PARTITION BY")) {
				return false;
			}
		}
		if (plan.contains("WINDOW")) {
			requiresKnownAdvancedOperator = true;
			if (!statement.contains(" OVER ") && !statement.contains("OVER(")) {
				return false;
			}
		}
		return requiresKnownAdvancedOperator;
	}

	public enum ReviewMode {
		CONFIGURED,
		DETERMINISTIC_ONLY,
		SEMANTIC_ALWAYS
	}

	private static final class SetSupport {
		private static java.util.Set<String> empty() {
			return java.util.Set.of();
		}
	}

}
