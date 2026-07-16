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
package cn.lgs.semevosql.clarification;

import cn.lgs.semevosql.semantic.domain.SemanticBlueprint;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Rechecks authoritative owner/revision before SQL; retrieval similarity grants no authority. */
@Component
public class PersonalDefinitionExecutionGuard {
    private final PersonalSemanticDefinitionStore definitions;
    private cn.lgs.semevosql.semantic.application.SemanticCatalogReadService catalogs;
    public PersonalDefinitionExecutionGuard(PersonalSemanticDefinitionStore definitions){this.definitions=definitions;}
    @org.springframework.beans.factory.annotation.Autowired
    public void catalogReader(cn.lgs.semevosql.semantic.application.SemanticCatalogReadService catalogs){this.catalogs=catalogs;}
    public void requireAuthorized(Long project,String principal,SemanticBlueprint plan) {
        if(plan==null)return;
        for(var binding:plan.getBindingDependencies()) {
            if("PROJECT_CANDIDATE".equals(binding.getSource()))throw new SecurityException("Unconfirmed project suggestion cannot execute");
            if(!"USER".equals(binding.getSource()))continue;
            if(binding.getSourceRecordId()==null || !Objects.equals(principal,binding.getPrincipalId()))
                throw new SecurityException("Private semantic definition requires its authenticated owner");
            if(binding.getSourceRevision()==null) {
                // Legacy approved plans have unknown provenance, never inferred as today's revision.
                var owner=definitions.current(binding.getSourceRecordId());
                if(!Objects.equals(project,owner.projectId())||!Objects.equals(principal,owner.principal())||"TEXT_DEFINITION".equals(binding.getAssetType()))
                    throw new SecurityException("Legacy private binding owner or kind mismatch");
                continue;
            }
            var d=definitions.require(binding.getSourceRecordId(),binding.getSourceRevision());
            cn.lgs.semevosql.semantic.application.PersonalDefinitionCatalogOverlay.requireSource(project,principal,binding,d);
            if(binding.getRepresentationCode()!=null&&binding.getRepresentationHash()==null)
                throw new SecurityException("Private structure identity required");
        }
        boolean structured=plan.getBindingDependencies().stream().anyMatch(b->b.getRepresentationCode()!=null)
            ||plan.getMetrics().stream().anyMatch(m->m.getMetricCode()!=null&&m.getMetricCode().matches("p_\\d+_\\d+"));
        if(structured) {
            if(catalogs==null)throw new IllegalStateException("Private dependency reader unavailable");
            var catalog=catalogs.getForModels(project,plan.getProjectVersionId(),plan.getModels().stream()
                .map(SemanticBlueprint.ModelSelection::getModelCode).collect(java.util.stream.Collectors.toSet()));
            new cn.lgs.semevosql.semantic.application.PersonalDefinitionCatalogOverlay(definitions,catalogs).frozenCatalog(catalog,principal,plan);
        }
    }
}
