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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.lgs.semevosql.bo.schema.ResultSetBO;
import cn.lgs.semevosql.review.PostExecutionReviewService.ReviewMode;
import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.sql.application.SqlResultValidator;
import cn.lgs.semevosql.sql.application.SqlResultValidator.ValidationMode;
import cn.lgs.semevosql.sql.application.SqlResultValidator.ValidationResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PostExecutionReviewServiceTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ReviewMode.class)
    void deterministicRejectionReturnsRepairWithoutWaitingForAnUnavailableModel(ReviewMode mode) {
        var reviewer=mock(SemanticResultReviewer.class);
        var service=new PostExecutionReviewService(new SqlResultValidator(),reviewer,new PostExecutionReviewProperties());
        var plan=SemanticBlueprint.builder().compilerMode("CONSTRAINED_GENERATION")
            .expectedResult(SemanticBlueprint.ExpectedResultShape.builder().columns(List.of("confirmed_output_1")).build()).build();
        var wrong=ResultSetBO.builder().column(List.of("confirmed_output"))
            .data(List.of(Map.of("confirmed_output","50.00"))).build();
        var decision=service.review("query",plan,"SELECT 50.00 AS confirmed_output",wrong,100,
            "plan",mode,ValidationMode.ADVANCED_EXECUTION,List.of());
        assertThat(decision.decision()).isEqualTo(PostExecutionReview.Decision.RETRY_SQL);
        assertThat(decision.deterministicErrors()).contains("Expected result column is missing: confirmed_output_1");
        assertThat(decision.semanticReviewerUsed()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(reviewer);
    }

    @Test void generatedRepairOfOriginallyDeterministicPlanRequiresReviewEvenWhenOptionalReviewIsDisabled() {
        var validator=mock(SqlResultValidator.class);var reviewer=mock(SemanticResultReviewer.class);
        var properties=new PostExecutionReviewProperties();properties.setSemanticEnabled(false);
        var service=new PostExecutionReviewService(validator,reviewer,properties);
        for(String mode:List.of("SEMANTIC_SQL","CONSTRAINED_GENERATION"))
            assertThat(service.shouldRunSemanticReviewer(ReviewMode.CONFIGURED,ValidationResult.accepted(List.of()),
                SemanticBlueprint.builder().compilerMode(mode).build())).isTrue();
        var originalPlan=SemanticBlueprint.builder().compilerMode("DETERMINISTIC").build();
        var result=ResultSetBO.builder().column(List.of("ordered_amount")).data(List.of(Map.of("ordered_amount","420.00"))).build();
        when(validator.validate(any(),any(),anyInt(),any())).thenReturn(ValidationResult.accepted(List.of()));
        when(reviewer.review(any(),any(),any(),any(),any(),any(),any(),anyBoolean()))
            .thenReturn(new PostExecutionReview(PostExecutionReview.Decision.PASS,PostExecutionReview.IssueType.NONE,
                1,java.util.Set.of(),List.of(),List.of(),List.of(),true,null));
        assertThat(service.review("sum all January orders",originalPlan,"SELECT SUM(amount) AS ordered_amount FROM orders",
            result,1000,"original approved plan",ReviewMode.CONFIGURED,ValidationMode.ADVANCED_EXECUTION,List.of())
            .semanticReviewerUsed()).isTrue();
        verify(reviewer).review(any(),eq(originalPlan),any(),eq(result),any(),eq(List.of()),eq(List.of()),eq(true));
        assertThatThrownBy(()->service.review("sum all January orders",originalPlan,"SELECT SUM(amount) AS ordered_amount FROM orders",
            result,1000,"original approved plan",ReviewMode.DETERMINISTIC_ONLY,ValidationMode.ADVANCED_EXECUTION,List.of()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot be bypassed");
    }

    @Test void singleConfirmedTextMeasureStillRequiresSemanticReviewOfGeneratedSql() {
        var validator=mock(SqlResultValidator.class);var reviewer=mock(SemanticResultReviewer.class);
        var service=new PostExecutionReviewService(validator,reviewer,new PostExecutionReviewProperties());
        var clean=ValidationResult.accepted(List.of());
        var plan=SemanticBlueprint.builder().compilerMode("CONSTRAINED_GENERATION").build();
        assertThat(service.shouldRunSemanticReviewer(ReviewMode.CONFIGURED,clean,plan)).isTrue();
        assertThat(service.shouldRunSemanticReviewer(ReviewMode.CONFIGURED,clean,
            SemanticBlueprint.builder().compilerMode("DETERMINISTIC").build())).isFalse();
        when(validator.validate(any(),any(),anyInt(),any())).thenReturn(clean);
        var result=ResultSetBO.builder().column(List.of("personal_amount")).data(List.of(Map.of("personal_amount","336"))).build();
        when(reviewer.review(any(),any(),eq("SELECT SUM(amount) * 0.8 AS personal_amount FROM orders"),any(),any(),any(),any(),anyBoolean()))
            .thenReturn(new PostExecutionReview(PostExecutionReview.Decision.PASS,PostExecutionReview.IssueType.NONE,
                1.0d,java.util.Set.of(),List.of(),List.of(),List.of(),true,null));
        assertThat(service.review("personal amount",plan,"SELECT SUM(amount) * 0.8 AS personal_amount FROM orders",result,
            1000,"plan",ReviewMode.CONFIGURED,ValidationMode.STRICT_SEMANTIC_PLAN,List.of()).semanticReviewerUsed()).isTrue();
        verify(reviewer).review(any(),any(),eq("SELECT SUM(amount) * 0.8 AS personal_amount FROM orders"),any(),any(),any(),any(),anyBoolean());
    }

	@Test
	void advancedWindowShapeCanBeVerifiedFromPlannerRequirementAndSql() {
		assertThat(PostExecutionReviewService.requiredAdvancedShapeIsObservable(
				"Use LAG and PARTITION BY created_at_month to get previous paid week",
				"SELECT LAG(amount) OVER (PARTITION BY created_at_month ORDER BY paid_at_week) FROM t"))
			.isTrue();
		assertThat(PostExecutionReviewService.requiredAdvancedShapeIsObservable(
				"Use LAG and PARTITION BY created_at_month to get previous paid week",
				"SELECT amount FROM t"))
			.isFalse();
	}

	@Test
	void deterministicOnlyModeCannotAcceptResultWithRequiredSemanticWarnings() {
		SqlResultValidator validator = mock(SqlResultValidator.class);
		SemanticResultReviewer reviewer = mock(SemanticResultReviewer.class);
		PostExecutionReviewProperties properties = new PostExecutionReviewProperties();
		PostExecutionReviewService service = new PostExecutionReviewService(validator, reviewer, properties);
		SemanticBlueprint plan = new SemanticBlueprint();
		ResultSetBO resultSet = ResultSetBO.builder().column(List.of("value")).data(List.of(Map.of("value", "1"))).build();
		when(validator.validate(any(ResultSetBO.class), any(SemanticBlueprint.class), anyInt(), any(ValidationMode.class)))
			.thenReturn(ValidationResult.accepted(List.of()));

		assertThatThrownBy(() -> service.review("question", plan, "select 1", resultSet, 1000,
                "execution plan", ReviewMode.DETERMINISTIC_ONLY, ValidationMode.ADVANCED_EXECUTION,
                List.of("NULLABLE_COLUMN_REFERENCED model=orders column=paid_at role=TIME")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot be bypassed");
		verify(reviewer, never()).review(any(), any(), any(), any(), any(), any(), any(), anyBoolean());
	}

	@Test
	void governedMultiSourceEmptyResultIsDataFactNotSqlRepair() {
		SqlResultValidator validator = mock(SqlResultValidator.class);
		SemanticResultReviewer reviewer = mock(SemanticResultReviewer.class);
		PostExecutionReviewProperties properties = new PostExecutionReviewProperties();
		PostExecutionReviewService service = new PostExecutionReviewService(validator, reviewer, properties);
		SemanticBlueprint plan = SemanticBlueprint.builder()
			.sourceSubPlans(List.of(
					SemanticBlueprint.SourceSubPlan.builder().datasourceId(1).modelCodes(List.of("left")).build(),
					SemanticBlueprint.SourceSubPlan.builder().datasourceId(2).modelCodes(List.of("right")).build()))
			.mergePlan(SemanticBlueprint.MergePlan.builder().policyCode("governed_lookup").build())
			.build();
		ResultSetBO resultSet = ResultSetBO.builder().column(List.of("value")).data(List.of()).build();
		when(validator.validate(any(ResultSetBO.class), any(SemanticBlueprint.class), anyInt(), any(ValidationMode.class)))
			.thenReturn(ValidationResult.accepted(List.of("SQL completed successfully but returned no rows")));
		when(reviewer.review(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
			.thenReturn(new PostExecutionReview(PostExecutionReview.Decision.RETRY_SQL,
					PostExecutionReview.IssueType.SQL_REPAIRABLE, 0.97d, java.util.Set.of("relationship:governed"),
					List.of("The governed merge returned no rows"), List.of(),
					List.of("SQL completed successfully but returned no rows"), true, null));

		PostExecutionReview review = service.review("question", plan, "source sql", resultSet, 1000,
				"governed merge", ReviewMode.CONFIGURED, ValidationMode.STRICT_SEMANTIC_PLAN, List.of());

		assertThat(review.decision()).isEqualTo(PostExecutionReview.Decision.PASS);
		assertThat(review.issueType()).isEqualTo(PostExecutionReview.IssueType.NONE);
		assertThat(review.deterministicWarnings())
			.anyMatch(warning -> warning.contains("empty result was retained instead of repair/replan"));
		assertThat(review.semanticReviewerUsed()).isTrue();
	}

	@Test
	void governedMultiSourceEmptyResultDoesNotReplanForIntermediateShape() {
		SqlResultValidator validator = mock(SqlResultValidator.class);
		SemanticResultReviewer reviewer = mock(SemanticResultReviewer.class);
		PostExecutionReviewProperties properties = new PostExecutionReviewProperties();
		PostExecutionReviewService service = new PostExecutionReviewService(validator, reviewer, properties);
		SemanticBlueprint plan = SemanticBlueprint.builder()
			.sourceSubPlans(List.of(
					SemanticBlueprint.SourceSubPlan.builder().datasourceId(1).modelCodes(List.of("left")).build(),
					SemanticBlueprint.SourceSubPlan.builder().datasourceId(2).modelCodes(List.of("right")).build()))
			.mergePlan(SemanticBlueprint.MergePlan.builder().policyCode("governed_lookup").build())
			.build();
		ResultSetBO resultSet = ResultSetBO.builder()
			.column(List.of("effective_paid_amount", "order_count"))
			.data(List.of())
			.build();
		when(validator.validate(any(ResultSetBO.class), any(SemanticBlueprint.class), anyInt(), any(ValidationMode.class)))
			.thenReturn(ValidationResult.accepted(List.of("SQL completed successfully but returned no rows")));
		when(reviewer.review(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
			.thenReturn(new PostExecutionReview(PostExecutionReview.Decision.REPLAN_EXECUTION,
					PostExecutionReview.IssueType.RESULT_SHAPE_MISMATCH, 0.96d,
					java.util.Set.of("relationship:governed"),
					List.of("Source-level merge keys are grouped before the governed merge"), List.of(),
					List.of("SQL completed successfully but returned no rows"), true, null));

		PostExecutionReview review = service.review("question", plan, "source sql", resultSet, 1000,
				"governed cross-source merge", ReviewMode.CONFIGURED, ValidationMode.STRICT_SEMANTIC_PLAN, List.of());

		assertThat(review.decision()).isEqualTo(PostExecutionReview.Decision.PASS);
		assertThat(review.issueType()).isEqualTo(PostExecutionReview.IssueType.NONE);
		assertThat(review.deterministicWarnings())
			.anyMatch(warning -> warning.contains("deterministic final-result validation passed"));
	}
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"timeout","invalid JSON","provider unavailable"})
    void requiredReviewerFailureCannotBecomeDeterministicPass(String failure) {
        var validator=mock(SqlResultValidator.class);var reviewer=mock(SemanticResultReviewer.class);
        var service=new PostExecutionReviewService(validator,reviewer,new PostExecutionReviewProperties());
        when(validator.validate(any(),any(),anyInt(),any())).thenReturn(ValidationResult.accepted(List.of()));
        when(reviewer.review(any(),any(),any(),any(),any(),any(),any(),anyBoolean()))
            .thenThrow(new IllegalStateException(failure));
        assertThatThrownBy(()->service.review("question",new SemanticBlueprint(),"select 1",new ResultSetBO(),1000,
            "plan",ReviewMode.SEMANTIC_ALWAYS,ValidationMode.STRICT_SEMANTIC_PLAN,List.of()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("result is not accepted");
    }

    @Test void consecutiveSqlVersionsBothReceiveRequiredSemanticReview() {
        var validator=mock(SqlResultValidator.class);var reviewer=mock(SemanticResultReviewer.class);
        var service=new PostExecutionReviewService(validator,reviewer,new PostExecutionReviewProperties());
        when(validator.validate(any(),any(),anyInt(),any())).thenReturn(ValidationResult.accepted(List.of()));
        when(reviewer.review(any(),any(),any(),any(),any(),any(),any(),anyBoolean()))
            .thenReturn(new PostExecutionReview(PostExecutionReview.Decision.PASS,PostExecutionReview.IssueType.NONE,
                1,java.util.Set.of(),List.of(),List.of(),List.of(),true,null));
        var result=new ResultSetBO();var plan=new SemanticBlueprint();
        for(String sql:List.of("select 1","select 2"))
            assertThat(service.review("question",plan,sql,result,1000,"plan",ReviewMode.SEMANTIC_ALWAYS).semanticReviewerUsed()).isTrue();
        verify(reviewer).review("question",plan,"select 1",result,"plan",List.of(),List.of(),false);
        verify(reviewer).review("question",plan,"select 2",result,"plan",List.of(),List.of(),false);
    }
}
