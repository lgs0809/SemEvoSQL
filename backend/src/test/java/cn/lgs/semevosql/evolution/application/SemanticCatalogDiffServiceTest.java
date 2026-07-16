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
package cn.lgs.semevosql.evolution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.lgs.semevosql.evolution.SemanticPatch.OperationType;
import cn.lgs.semevosql.semantic.application.SemanticCatalogPatchAnalyzer;
import cn.lgs.semevosql.semantic.domain.SemanticAssetProvenance.AssetType;
import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SemanticCatalogDiffServiceTest {

    private SemanticCatalogPatchAnalyzer fingerprint;

    private SemanticCatalogDiffService service;

    @BeforeEach
    void setUp() {
        fingerprint = mock(SemanticCatalogPatchAnalyzer.class);
        when(fingerprint.fingerprintAsset(any(), any())).thenReturn("fingerprint");
        service = new SemanticCatalogDiffService(fingerprint);
    }

    @Test
    void unchangedBusinessSemanticsProduceNoDiff() {
        SemanticCatalogSnapshot current = SemanticCatalogSnapshot.builder()
            .metrics(List.of(metric("paid_amount", "SUM(pay_amount)", "实付金额")))
            .build();
        SemanticCatalogSnapshot incoming = SemanticCatalogSnapshot.builder()
            .metrics(List.of(metric("paid_amount", "SUM(pay_amount)", "实付金额")))
            .build();

        var result = service.diff(current, incoming);

        assertThat(result.semanticDiffDetected()).isFalse();
        assertThat(result.operations()).isEmpty();
        assertThat(result.blockedChanges()).isEmpty();
    }

    @Test
    void metricDefinitionChangeBecomesUpdateOperation() {
        SemanticCatalogSnapshot.Metric before = metric("paid_amount", "SUM(pay_amount)", "实付金额");
        SemanticCatalogSnapshot.Metric after = metric("paid_amount", "SUM(CASE WHEN status='PAID' THEN pay_amount ELSE 0 END)",
                "实付金额");
        when(fingerprint.fingerprintAsset(eq(AssetType.METRIC), eq(before))).thenReturn("metric-before");

        var result = service.diff(SemanticCatalogSnapshot.builder().metrics(List.of(before)).build(),
                SemanticCatalogSnapshot.builder().metrics(List.of(after)).build());

        assertThat(result.releasable()).isTrue();
        assertThat(result.operations()).hasSize(1);
        assertThat(result.operations().get(0).operation()).isEqualTo(OperationType.UPDATE_METRIC);
        assertThat(result.operations().get(0).assetKey()).isEqualTo("paid_amount");
        assertThat(result.operations().get(0).expectedCurrentFingerprint()).isEqualTo("metric-before");
        assertThat(result.operations().get(0).values())
            .containsEntry("expression", "SUM(CASE WHEN status='PAID' THEN pay_amount ELSE 0 END)")
            .doesNotContainKey("businessName");
    }

    @Test
    void physicalColumnChangeIsBlockedInsteadOfAutoPatched() {
        SemanticCatalogSnapshot.Column before = SemanticCatalogSnapshot.Column.builder()
            .modelCode("order")
            .columnName("pay_amount")
            .businessName("支付金额")
            .dataType("BIGINT")
            .nullable(false)
            .status(SemanticAssetStatus.ENABLED)
            .build();
        SemanticCatalogSnapshot.Column after = SemanticCatalogSnapshot.Column.builder()
            .modelCode("order")
            .columnName("pay_amount")
            .businessName("支付金额")
            .dataType("VARCHAR")
            .nullable(false)
            .status(SemanticAssetStatus.ENABLED)
            .build();

        var result = service.diff(SemanticCatalogSnapshot.builder().columns(List.of(before)).build(),
                SemanticCatalogSnapshot.builder().columns(List.of(after)).build());

        assertThat(result.semanticDiffDetected()).isTrue();
        assertThat(result.releasable()).isFalse();
        assertThat(result.operations()).isEmpty();
        assertThat(result.blockedChanges()).singleElement()
            .satisfies(blocked -> assertThat(blocked.code()).isEqualTo("PROTECTED_COLUMN_SCHEMA_OR_POLICY_CHANGE"));
    }

    private SemanticCatalogSnapshot.Metric metric(String code, String expression, String businessName) {
        return SemanticCatalogSnapshot.Metric.builder()
            .modelCode("order")
            .metricCode(code)
            .businessName(businessName)
            .expression(expression)
            .aggregation("SUM")
            .status(SemanticAssetStatus.ENABLED)
            .build();
    }
}
