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

import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import cn.lgs.semevosql.semantic.domain.SemanticIssueType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RuntimeClarificationServiceTest {

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

}
