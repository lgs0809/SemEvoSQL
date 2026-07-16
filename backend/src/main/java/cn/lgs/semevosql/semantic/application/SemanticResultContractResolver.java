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

import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import cn.lgs.semevosql.semantic.domain.SemanticCandidateSet;
import cn.lgs.semevosql.semantic.domain.SemanticResultContract;
import cn.lgs.semevosql.semantic.domain.ScalarCalculation;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

/** Models select supplied identities. The program freezes output names and verifies submitted QUERY and saved USER sources. */
final class SemanticResultContractResolver {
    private SemanticResultContractResolver() {}
    static SemanticResultContract parse(JsonNode root,Set<String> boundMetrics,List<Long> personalIds,SemanticCandidateSet candidates) {
        return parse(root,boundMetrics,personalIds,candidates,null,List.of());
    }
    static SemanticResultContract parse(JsonNode root,Set<String> boundMetrics,List<Long> personalIds,
            SemanticCandidateSet candidates,cn.lgs.semevosql.learning.QueryCaseHints.ResultCompositionHint composition) {
        return parse(root,boundMetrics,personalIds,candidates,composition,List.of());
    }
    static SemanticResultContract parse(JsonNode root,Set<String> boundMetrics,List<Long> personalIds,
            SemanticCandidateSet candidates,cn.lgs.semevosql.learning.QueryCaseHints.ResultCompositionHint composition,
            List<SemanticPlanningInput.DefinitionConfirmation> confirmations) {
        var selected=root.path("resultSelection");
        boolean textOnly=personalIds.stream().anyMatch(id->candidates.confirmedDefinitions().stream().anyMatch(d ->
            id.equals(d.getSourceRecordId())&&"TEXT_DEFINITION".equals(d.getAssetType())&&d.getRepresentationCode()==null));
        if(selected.isMissingNode()||selected.isNull()) {
            if(textOnly)throw new IllegalArgumentException("A text-only calculation requires resultSelection: separate requested outputs from its authorized base dependencies");
            return null;
        }
        if(!selected.isObject())
            throw new IllegalArgumentException("resultSelection must be null or an object with metricCodes, personalDefinitionIds and optional queryDefinitionIds arrays");
        for(var field:List.of("metricCodes","personalDefinitionIds")) if(!selected.path(field).isArray())
            throw new IllegalArgumentException("resultSelection."+field+" must be an array; allowed fields: metricCodes, personalDefinitionIds, queryDefinitionIds");
        selected.fieldNames().forEachRemaining(f->{if(!Set.of("metricCodes","personalDefinitionIds","queryDefinitionIds").contains(f))
            throw new IllegalArgumentException("Unsupported resultSelection."+f+"; allowed fields: metricCodes, personalDefinitionIds, queryDefinitionIds");});
        var codes=new LinkedHashSet<String>();
        for(var code:selected.path("metricCodes"))if(!code.isTextual()||!boundMetrics.contains(code.asText())||!codes.add(code.asText()))
            throw new IllegalArgumentException("Result metric must be a unique selected governed dependency");
        var measures=new ArrayList<SemanticResultContract.PersonalMeasure>();var seen=new HashSet<Long>();
        for(var id:selected.path("personalDefinitionIds")) {
            if(!id.isIntegralNumber()||!id.canConvertToLong()||!personalIds.contains(id.longValue())||!seen.add(id.longValue()))
                throw new IllegalArgumentException("Result definition must be one actually selected supplied personal identity");
            var d=candidates.confirmedDefinitions().stream().filter(b->Objects.equals(b.getSourceRecordId(),id.longValue()))
                .findFirst().orElseThrow(()->new IllegalArgumentException("Result definition is outside supplied scope"));
            if(!"USER".equals(d.getSource())||!"TEXT_DEFINITION".equals(d.getAssetType())||d.getRepresentationCode()!=null
                    ||d.getSourceRevision()==null||d.getSourceContentHash()==null||d.getPhrase()==null||d.getPhrase().isBlank())
                throw new IllegalArgumentException("Structured metrics use their metric code; text results require an exact immutable personal definition");
            measures.add(new SemanticResultContract.PersonalMeasure("p_"+d.getSourceRecordId()+"_"+d.getSourceRevision(),
                d.getPhrase(),d.getSourceRecordId(),d.getSourceRevision(),d.getSourceContentHash()));
        }
        var queryMeasures=new ArrayList<SemanticResultContract.QueryMeasure>();var seenQueries=new HashSet<String>();
        var queryIds=selected.path("queryDefinitionIds");
        if(!queryIds.isMissingNode()) {
            if(!queryIds.isArray())throw new IllegalArgumentException("resultSelection.queryDefinitionIds must be an array");
            for(var id:queryIds) {
                if(!id.isTextual()||!seenQueries.add(id.asText()))
                    throw new IllegalArgumentException("Result confirmation must be a unique submitted query identity");
                var receipt=confirmations.stream().filter(c->Objects.equals(c.clarificationId(),id.asText())
                    &&"QUERY".equals(c.selectedScope())).findFirst()
                    .orElseThrow(()->new IllegalArgumentException("Result confirmation is outside this query's submitted scope"));
                queryMeasures.add(queryMeasure(receipt));
            }
        }
        boolean calculatedOutput=composition!=null&&"SCALAR".equals(composition.type())
            &&composition.calculationExpression()!=null&&!composition.calculationExpression().isBlank();
        if(codes.isEmpty()&&measures.isEmpty()&&queryMeasures.isEmpty()&&root.path("dimensionCodes").isEmpty()&&!calculatedOutput)
            throw new IllegalArgumentException("Result selection must retain a requested measure, dimension or validated calculation");
        return new SemanticResultContract(codes,measures,queryMeasures);
    }

    static void apply(SemanticBlueprint plan,SemanticResultContract contract) {
        apply(plan,contract,List.of());
    }
    static void apply(SemanticBlueprint plan,SemanticResultContract contract,
            List<SemanticPlanningInput.DefinitionConfirmation> confirmations) {
        if(contract==null)return;
        for(var result:contract.queryMeasures())if(confirmations.stream().noneMatch(c->
            "QUERY".equals(c.selectedScope())&&Objects.equals(c.clarificationId(),result.clarificationId())&&queryMeasure(c).equals(result)))
            throw new IllegalArgumentException("Requested query measure has no exact submitted frozen source");
        if(!plan.getMetrics().stream().map(SemanticBlueprint.MetricSelection::getMetricCode).toList().containsAll(contract.metricCodes()))
            throw new IllegalArgumentException("Requested metric is absent from the materialized plan");
        for(var result:contract.personalMeasures())if(plan.getBindingDependencies().stream().noneMatch(d ->
            "USER".equals(d.getSource())&&Objects.equals(d.getSourceRecordId(),result.definitionId())
            &&Objects.equals(d.getSourceRevision(),result.definitionRevision())&&Objects.equals(d.getSourceContentHash(),result.sourceContentHash())
            &&Objects.equals(d.getPhrase(),result.businessName())))
            throw new IllegalArgumentException("Requested text measure has no exact frozen source");
        var calculation=confirmedCalculation(plan,contract);
        var oldCalculation=plan.getScalarCalculation();
        // Selection hides unrequested base measures; it must not erase a calculation already
        // materialized from a validated resultComposition. Such outputs need not be catalog metrics.
        var hiddenOutputs=plan.getProjections().stream()
            .filter(p -> "METRIC".equals(p.getProjectionType())&&!contract.metricCodes().contains(p.getAlias()))
            .map(SemanticBlueprint.ProjectionSelection::getAlias).collect(java.util.stream.Collectors.toSet());
        var outputColumns=new LinkedHashSet<String>();
        if(plan.getExpectedResult()!=null&&plan.getExpectedResult().getColumns()!=null)
            plan.getExpectedResult().getColumns().stream().filter(c -> !hiddenOutputs.contains(c))
                .map(c -> calculation!=null&&Objects.equals(c,oldCalculation.outputCode())?calculation.outputCode():c)
                .forEach(outputColumns::add);
        var projections=new ArrayList<>(plan.getProjections().stream().filter(p ->
            (!"METRIC".equals(p.getProjectionType())||contract.metricCodes().contains(p.getAlias()))
                &&(calculation==null||!Objects.equals(p.getAlias(),oldCalculation.outputCode()))).toList());
        for(var result:contract.outputMeasures()) {
            if(projections.stream().anyMatch(p ->result.outputCode().equals(p.getAlias())))
                throw new IllegalArgumentException("Requested output identity collides with another projection");
            projections.add(SemanticBlueprint.ProjectionSelection.builder().alias(result.outputCode()).projectionType("CONFIRMED_MEASURE").build());
        }
        if(projections.isEmpty()&&outputColumns.isEmpty())throw new IllegalArgumentException("Requested result has no output");
        plan.setProjections(projections);plan.setResultContract(contract);
        if(calculation!=null) {
            plan.setScalarCalculation(calculation);
            if(plan.getMergePlan()!=null&&plan.getMergePlan().getCalculationExpression()!=null
                    &&!plan.getMergePlan().getCalculationExpression().isBlank())
                plan.getMergePlan().setCalculationExpression(calculation.outputCode()+"="
                    +plan.getMergePlan().getCalculationExpression().split("=",2)[1]);
        }
        if(plan.getExpectedResult()==null)plan.setExpectedResult(SemanticBlueprint.ExpectedResultShape.builder().build());
        projections.stream().map(SemanticBlueprint.ProjectionSelection::getAlias).forEach(outputColumns::add);
        plan.getExpectedResult().setColumns(List.copyOf(outputColumns));
        if(!contract.outputMeasures().isEmpty())plan.setCompilerMode("CONSTRAINED_GENERATION");
    }
    /** A sole confirmed result names the existing validated scalar; it does not create a second calculation. */
    private static ScalarCalculation confirmedCalculation(SemanticBlueprint plan,SemanticResultContract contract) {
        var calculation=plan.getScalarCalculation();
        if(calculation==null||contract.outputMeasures().isEmpty())return null;
        if(contract.outputMeasures().size()!=1)
            throw new IllegalArgumentException("A scalar calculation cannot be assigned to multiple confirmed results; clarify the requested outputs");
        if(!plan.getGroupBy().isEmpty()||!plan.getDimensions().isEmpty()||plan.getExpectedResult()==null
                ||plan.getExpectedResult().getColumns()==null
                ||!plan.getExpectedResult().getColumns().contains(calculation.outputCode()))
            throw new IllegalArgumentException("Confirmed scalar result must correspond to the existing ungrouped calculation output");
        var metricCodes=plan.getMetrics().stream().map(SemanticBlueprint.MetricSelection::getMetricCode)
            .collect(java.util.stream.Collectors.toSet());
        String expression=calculation.leftMetricCode()+calculation.operator()+calculation.rightMetricCode();
        if(calculation.absolute())expression="ABS("+expression+")";
        // Validate dependencies and any merge representation before changing any plan field.
        if(!calculation.equals(ScalarCalculation.parse(calculation.outputCode()+"="+expression,metricCodes)))
            throw new IllegalArgumentException("Confirmed scalar result has an invalid calculation");
        if(plan.getMergePlan()!=null&&plan.getMergePlan().getCalculationExpression()!=null
                &&!plan.getMergePlan().getCalculationExpression().isBlank()
                &&!calculation.equals(ScalarCalculation.parse(plan.getMergePlan().getCalculationExpression(),metricCodes)))
            throw new IllegalArgumentException("Confirmed scalar result and merge calculation disagree");
        String output=contract.outputMeasures().get(0).outputCode();
        if(metricCodes.contains(output))throw new IllegalArgumentException("Confirmed result cannot overwrite a selected metric");
        return new ScalarCalculation(output,calculation.leftMetricCode(),calculation.operator(),
            calculation.rightMetricCode(),calculation.absolute());
    }
    private static SemanticResultContract.QueryMeasure queryMeasure(SemanticPlanningInput.DefinitionConfirmation receipt) {
        if(receipt.phrase()==null||receipt.phrase().isBlank()||receipt.definitionText()==null||receipt.definitionText().isBlank())
            throw new IllegalArgumentException("Query result requires the complete submitted meaning");
        var identity=UUID.fromString(receipt.clarificationId()).toString();
        if(!identity.equals(receipt.clarificationId())||receipt.sourceRevision()<0)
            throw new IllegalArgumentException("Query confirmation identity or revision is invalid");
        String hash;
        // Reuse SHA-256 for exact source provenance; the model never supplies an output alias or source text.
        try {
            hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(receipt.definitionText().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
        return new SemanticResultContract.QueryMeasure("q_"+identity.replace("-","")+"_"+receipt.sourceRevision(),
            receipt.phrase(),identity,receipt.sourceRevision(),receipt.definitionText(),hash);
    }
}
