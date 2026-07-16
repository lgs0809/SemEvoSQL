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
package cn.lgs.semevosql.semantic.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticCatalogApplicationServiceTest {

	@Test
	void boundedFallbackUsesCompleteSmallGovernedNamespace() {
		SemanticCatalogSnapshot snapshot = SemanticCatalogSnapshot.builder()
			.models(List.of(model("orders", "orders"), model("customers", "customers")))
			.build();

		var recall = SemanticCatalogApplicationService.boundedCatalogFallback(snapshot, 20);

		assertThat(recall.physicalTables()).containsExactly("customers", "orders");
		assertThat(recall.hits()).hasSize(2);
		assertThat(recall.hits().get(0).channelRanks()).containsKey("BOUNDED_CATALOG_FALLBACK");
	}

	@Test
	void boundedFallbackRefusesPartialLargeCatalogInjection() {
		SemanticCatalogSnapshot snapshot = SemanticCatalogSnapshot.builder()
			.models(List.of(model("a", "a"), model("b", "b"), model("c", "c")))
			.build();

		var recall = SemanticCatalogApplicationService.boundedCatalogFallback(snapshot, 2);

		assertThat(recall.physicalTables()).isEmpty();
		assertThat(recall.hits()).isEmpty();
	}

	private SemanticCatalogSnapshot.Model model(String code, String table) {
		return SemanticCatalogSnapshot.Model.builder()
			.modelCode(code)
			.physicalTable(table)
			.status(SemanticAssetStatus.ENABLED)
			.build();
	}
}
