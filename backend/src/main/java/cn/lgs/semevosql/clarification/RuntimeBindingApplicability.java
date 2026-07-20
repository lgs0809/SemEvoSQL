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

import cn.lgs.semevosql.semantic.domain.SemanticAssetStatus;
import cn.lgs.semevosql.semantic.domain.SemanticCatalogSnapshot;
import java.util.*;
import java.util.stream.Stream;

/** Shared lexical applicability for confirmed names, never a semantic equivalence or consent decision. */
public final class RuntimeBindingApplicability {
    private RuntimeBindingApplicability() {}

    public static boolean shadowed(SemanticCatalogSnapshot catalog,String query,String type,String key,String phrase) {
        Stream<String> names=switch(type) {
            case "TEXT_DEFINITION", "METRIC" -> catalog.getMetrics().stream()
                .filter(m->m.getStatus()==SemanticAssetStatus.ENABLED && !Objects.equals(m.getMetricCode(),key))
                .flatMap(m->Stream.of(m.getBusinessName(),m.getMetricCode()));
            case "DIMENSION" -> catalog.getDimensions().stream()
                .filter(d->d.getStatus()==SemanticAssetStatus.ENABLED && !Objects.equals(d.getDimensionCode(),key))
                .flatMap(d->Stream.of(d.getBusinessName(),d.getDimensionCode()));
            case "ENUM_VALUE" -> catalog.getEnumValues().stream()
                .filter(e->e.getStatus()==SemanticAssetStatus.ENABLED && !Objects.equals(e.getModelCode()+":"+e.getColumnName()+":"+e.getValueCode(),key))
                .flatMap(e->Stream.of(e.getBusinessName(),e.getValueCode()));
            default -> Stream.empty();
        };
        return onlyInsideExplicitTerms(query,phrase,names.toList());
    }

    static boolean onlyInsideExplicitTerms(String query,String phrase,Collection<String> names) {
        String text=UserSemanticPreferenceService.normalizePhrase(query),shortTerm=UserSemanticPreferenceService.normalizePhrase(phrase);
        if(text.isBlank() || shortTerm.isBlank()) return false;
        var longer=names.stream().map(UserSemanticPreferenceService::normalizePhrase)
            .filter(t->t.length()>shortTerm.length() && t.contains(shortTerm) && text.contains(t)).distinct().toList();
        if(longer.isEmpty()) return false;
        boolean found=false;
        for(int start=text.indexOf(shortTerm);start>=0;start=text.indexOf(shortTerm,start+1)) {
            found=true;boolean covered=false;
            for(String term:longer) {
                for(int occurrence=text.indexOf(term);occurrence>=0;occurrence=text.indexOf(term,occurrence+1)) {
                    if(occurrence<=start && occurrence+term.length()>=start+shortTerm.length()) {covered=true;break;}
                }
                if(covered) break;
            }
            // A separate request for the generic personal name must survive a second, explicit metric.
            if(!covered) return false;
        }
        return found;
    }
}
