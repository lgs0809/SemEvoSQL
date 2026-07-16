/*
 * Copyright 2026 the original author or authors.
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

import cn.lgs.semevosql.clarification.*;
import cn.lgs.semevosql.semantic.domain.*;
import cn.lgs.semevosql.util.JsonUtil;
import java.util.*;

/** A saved public definition can remain personal after the public formula changes. Physical authority cannot. */
public final class FrozenPersonalMetric {
    private FrozenPersonalMetric() {}
    public static boolean differs(PersonalSemanticDefinitionStore.Definition definition,SemanticCatalogSnapshot current) {
        return !Objects.equals(definition.dependencyFingerprint(),PersonalDefinitionSnapshot.capture(current,
            definition.assetType(),definition.assetKey()).dependencyFingerprint());
    }
    public static SemanticCatalogSnapshot.Metric project(PersonalSemanticDefinitionStore.Definition definition,SemanticCatalogSnapshot current) {
        if(!"METRIC".equals(definition.assetType())||!definition.snapshot().path("completeDefinitionRecorded").asBoolean())
            throw new SecurityException("A complete confirmed personal metric snapshot is required");
        var metric=JsonUtil.getObjectMapper().convertValue(definition.snapshot().path("target"),SemanticCatalogSnapshot.Metric.class);
        if(!Objects.equals(metric.getMetricCode(),definition.assetKey())||metric.getStatus()!=SemanticAssetStatus.ENABLED)
            throw new SecurityException("Frozen personal metric identity changed");
        metric.setDefinitionBinding(null);
        if(!new SemanticCatalogVisibility(current).maySendMetric(metric))throw new SecurityException("Frozen personal metric dependencies are unavailable");
        var scope=current.detachedCopy();
        var metrics=new ArrayList<>(scope.getMetrics());metrics.removeIf(m->definition.assetKey().equals(m.getMetricCode()));metrics.add(metric);scope.setMetrics(metrics);
        if(!Objects.equals(definition.dependencyFingerprint(),PersonalDefinitionSnapshot.capture(scope,"METRIC",definition.assetKey()).dependencyFingerprint()))
            throw new SecurityException("Frozen personal metric population or physical dependencies changed");
        metric.setMetricCode("p_"+definition.preferenceId()+"_"+definition.revision());metric.setBusinessName(definition.phrase());
        metric.setDefinitionBinding(null);return metric;
    }
}
