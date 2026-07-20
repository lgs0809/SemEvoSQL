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
package cn.lgs.semevosql.clarification;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticIssueType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RuntimeClarificationServiceTest {

    @Test void managementFollowupUsesOnlySubmittedOwnerTextAndNeverRecyclesAgentQuestionAsUserIntent() {
        var repository=mock(RuntimeClarificationRepository.class);
        var runs=mock(cn.lgs.semevosql.run.QueryRunService.class);
        var principal=mock(RuntimePrincipalResolver.class);
        when(principal.resolve(org.mockito.ArgumentMatchers.any())).thenReturn("alice");
        var service=new RuntimeClarificationService(repository,null,runs,null,null,null,principal,null,null,null,null);
        var own=RuntimeClarification.builder().assetType(PersonalDefinitionChangeService.TYPE)
            .status(RuntimeClarification.ClarificationStatus.ANSWERED).selectedOption("OTHER").answeredBy("alice")
            .question("agent-proposed-text-must-not-be-a-user-source").customAnswer("采用我补充的完整新口径").build();
        var other=managementAnswer("bob",RuntimeClarification.ClarificationStatus.ANSWERED,"OTHER","foreign-owner-text");
        var pending=managementAnswer("alice",RuntimeClarification.ClarificationStatus.PENDING,"OTHER","unsubmitted-text");
        var cancelled=managementAnswer("alice",RuntimeClarification.ClarificationStatus.ANSWERED,"CANCEL","cancelled-text");
        when(repository.answeredByRun("synthetic-run")).thenReturn(List.of(own,other,pending,cancelled));
        String input=service.definitionChangeUserInput("synthetic-run","原始修改请求");
        assertTrue(input.contains("原始修改请求"));assertTrue(input.contains("采用我补充的完整新口径"));
        assertFalse(input.contains("agent-proposed-text"));assertFalse(input.contains("foreign-owner-text"));
        assertFalse(input.contains("unsubmitted-text"));assertFalse(input.contains("cancelled-text"));
        assertFalse(service.applyResolvedAnswer("synthetic-run","原始修改请求").contains("agent-proposed-text"));
    }

    private RuntimeClarification managementAnswer(String owner,RuntimeClarification.ClarificationStatus status,String option,String text) {
        return RuntimeClarification.builder().assetType(PersonalDefinitionChangeService.TYPE).status(status)
            .selectedOption(option).answeredBy(owner).customAnswer(text).build();
    }

	@Test
	void explicitSpecificDimensionBeatsGenericObjectTermEvenAtSameTokenLength() {
		SemanticCatalogSnapshot.Dimension province = dimension("省份", "province", "province");

		assertTrue(RuntimeClarificationService.hasUniqueSpecificDimensionMatch("按客户省份统计有效支付金额", List.of(province),
				Set.of("客户")));
	}

	@Test
	void genericTermItselfDoesNotCountAsSpecificDimension() {
		SemanticCatalogSnapshot.Dimension customer = dimension("客户", "customer", "customer_id");

		assertFalse(RuntimeClarificationService.hasUniqueSpecificDimensionMatch("按客户统计金额", List.of(customer), Set.of("客户")));
	}

	@Test
	void plannerAmbiguityWithGovernedSemanticTargetsCanBeSavedAsLanguageHabit() {
		RuntimeClarification clarification = RuntimeClarification.builder()
			.issueType(SemanticIssueType.USER_QUESTION_AMBIGUOUS)
			.assetType("DIMENSION")
			.assetKey("order_time,payment_time")
			.options(List.of(new RuntimeClarification.ClarificationOption("order_time", "下单时间", "下单时间", null, null),
					new RuntimeClarification.ClarificationOption("payment_time", "支付时间", "支付时间", null, null)))
			.rawExpression("按时间统计实付金额趋势")
			.build();

		assertTrue(RuntimeClarificationService.isDurablePhraseBinding(clarification));
	}

	@Test
	void plannerAmbiguityWithoutPersonalSemanticAssetTargetsStaysQueryOnly() {
		RuntimeClarification clarification = RuntimeClarification.builder()
			.issueType(SemanticIssueType.USER_QUESTION_AMBIGUOUS)
			.assetType("RELATIONSHIP")
			.assetKey("path_a,path_b")
			.options(List.of(new RuntimeClarification.ClarificationOption("a", "路径 A", "路径 A", null, null),
					new RuntimeClarification.ClarificationOption("b", "路径 B", "路径 B", null, null)))
			.build();

		assertFalse(RuntimeClarificationService.isDurablePhraseBinding(clarification));
	}

	private SemanticCatalogSnapshot.Dimension dimension(String businessName, String dimensionCode, String columnName) {
		return SemanticCatalogSnapshot.Dimension.builder()
			.businessName(businessName)
			.dimensionCode(dimensionCode)
			.columnName(columnName)
			.build();
	}

    @Test
    void conversationalReferenceCannotBecomePersonalOrProjectSemanticAsset() {
        var reference = RuntimeClarification.builder().issueType(SemanticIssueType.USER_QUESTION_AMBIGUOUS)
            .assetType("CONTEXT_REFERENCE").options(List.of(new RuntimeClarification.ClarificationOption(
                "CONTEXT_1", "查询二月已支付金额", "查询二月已支付金额", null, null))).build();
        assertFalse(RuntimeClarificationService.isDurablePhraseBinding(reference));
    }

}
