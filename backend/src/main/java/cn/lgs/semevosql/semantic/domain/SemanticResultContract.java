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
package cn.lgs.semevosql.semantic.domain;

import java.util.List;
import java.util.Set;

/** Requested numeric outputs are distinct from the authorized measures needed to calculate them. */
public record SemanticResultContract(Set<String> metricCodes,List<PersonalMeasure> personalMeasures,
        List<QueryMeasure> queryMeasures) {
    public SemanticResultContract(Set<String> metricCodes,List<PersonalMeasure> personalMeasures) {
        this(metricCodes,personalMeasures,List.of());
    }
    public SemanticResultContract {
        metricCodes=Set.copyOf(metricCodes);
        personalMeasures=List.copyOf(personalMeasures);
        // Older approved checkpoints did not carry query-scoped outputs.
        queryMeasures=List.copyOf(queryMeasures==null?List.of():queryMeasures);
    }
    public record PersonalMeasure(String outputCode,String businessName,long definitionId,int definitionRevision,
            String sourceContentHash) {}
    /** Exact submitted text, frozen for one query; never a saved or published Catalog asset. */
    public record QueryMeasure(String outputCode,String businessName,String clarificationId,long sourceRevision,
            String definitionText,String sourceContentHash) {}
    public record OutputMeasure(String outputCode,String businessName) {}
    public List<OutputMeasure> outputMeasures() {
        return java.util.stream.Stream.concat(
            personalMeasures.stream().map(m->new OutputMeasure(m.outputCode(),m.businessName())),
            queryMeasures.stream().map(m->new OutputMeasure(m.outputCode(),m.businessName()))).toList();
    }
}
