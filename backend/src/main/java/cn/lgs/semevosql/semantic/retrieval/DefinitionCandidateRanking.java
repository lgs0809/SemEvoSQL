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
package cn.lgs.semevosql.semantic.retrieval;

import java.util.*;
import java.util.function.Function;
import org.slf4j.LoggerFactory;

/** Bounded independent channels; source repositories enforce visibility before their limits. */
public final class DefinitionCandidateRanking {
    private DefinitionCandidateRanking() { }
    public record Hit<K,T>(K key,T value) { }
    public static <K,T> List<T> rank(String query,List<Hit<K,T>> lexical,List<Hit<K,T>> vector,
            Function<T,String> text,RerankModelProvider reranker) {
        var values=new LinkedHashMap<K,T>();var scores=new HashMap<K,Double>();
        for(var channel:List.of(lexical,vector))for(int i=0;i<Math.min(20,channel.size());i++) {
            var hit=channel.get(i);values.putIfAbsent(hit.key(),hit.value());scores.merge(hit.key(),1d/(61+i),Double::sum);
        }
        var ranked=values.entrySet().stream().sorted(Comparator.<Map.Entry<K,T>>comparingDouble(e->scores.get(e.getKey())).reversed())
            .limit(20).map(Map.Entry::getValue).toList();
        var fallback=ranked.stream().limit(4).toList();
        if(reranker==null||ranked.isEmpty())return fallback;
        try {
            var output=reranker.currentRerankModel().rerank(query,ranked.stream().map(text).toList(),4);
            var unique=new HashSet<Integer>();
            var selected=output.stream().filter(s->s.index()>=0&&s.index()<ranked.size()&&Double.isFinite(s.score())&&unique.add(s.index()))
                .sorted(Comparator.comparingDouble(RerankModel.RerankScore::score).reversed()).limit(4).map(s->ranked.get(s.index())).toList();
            return selected.isEmpty()?fallback:selected;
        } catch(RuntimeException unavailable) {
            LoggerFactory.getLogger(DefinitionCandidateRanking.class).warn("Definition reranking unavailable after {}; keeping scoped RRF",unavailable.getClass().getSimpleName());
            return fallback;
        }
    }
}
