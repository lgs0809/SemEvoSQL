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

import com.fasterxml.jackson.databind.JsonNode;
import cn.lgs.semevosql.semantic.domain.SemanticCandidateSet;
import java.util.List;
import java.util.Set;

/** A model identifies intent; exact user text is confirmed through the existing HITL boundary. */
final class SemanticDefinitionProposal {
    private static final Set<String> FIELDS=Set.of("rawExpression","definitionText","definitionSpan","intentExcerpt");
    private static final Set<String> SPAN_FIELDS=Set.of("startQuote","endQuote");
    private SemanticDefinitionProposal() {}

    static SemanticPlanningOutcome confirmation(SemanticPlanningInput input,JsonNode response,SemanticCandidateSet candidates) {
        var proposal=response.path("definitionProposal");
        if(proposal.isMissingNode()||proposal.isNull())return null;
        if(!proposal.isObject())throw new IllegalArgumentException("Definition proposal must contain exact user excerpts");
        proposal.fieldNames().forEachRemaining(name->{
            if(!FIELDS.contains(name))throw new IllegalArgumentException("Unsupported definition proposal field");
        });
        String phrase=excerpt(proposal,"rawExpression",500,input.currentUserMessage());
        String definition=definition(proposal,input.currentUserMessage());
        excerpt(proposal,"intentExcerpt",2000,input.currentUserMessage());
        if(!input.question().contains(phrase)||!definition.contains(phrase))
            throw new IllegalArgumentException("Definition must name a measure requested in this question");
        if(input.definitionConfirmations().stream().anyMatch(c->phrase.equals(c.phrase())))return null;
        return new SemanticPlanningOutcome.ClarificationRequired("METRIC_MISSING",
            "你为“"+phrase+"”说明了这套口径，请确认完整含义，并选择保存范围。",
            List.of(new SemanticPlanningOutcome.Option("CONFIRM_DEFINITION",definition,"TEXT_DEFINITION",null),
                new SemanticPlanningOutcome.Option("OTHER","我再补充或调整口径",null,null),
                new SemanticPlanningOutcome.Option("CANCEL","取消本次查询",null,null)),
            "定义来自你本轮的原话。确认只授权所选保存范围；共享建议仍须经过公共发布流程。",phrase);
    }

    /** Short, unique source anchors avoid asking the model to reproduce a long passage exactly. */
    private static String definition(JsonNode proposal,String message) {
        boolean hasText=proposal.hasNonNull("definitionText");
        boolean hasSpan=proposal.hasNonNull("definitionSpan");
        if(hasText==hasSpan)
            throw new IllegalArgumentException("Definition proposal requires exactly one of definitionText or definitionSpan");
        if(hasText)return excerpt(proposal,"definitionText",20000,message);
        JsonNode span=proposal.path("definitionSpan");
        if(!span.isObject())throw new IllegalArgumentException("Definition span must contain source quotes");
        span.fieldNames().forEachRemaining(name->{
            if(!SPAN_FIELDS.contains(name))throw new IllegalArgumentException("Unsupported definition span field");
        });
        String startQuote=excerpt(span,"startQuote",1000,message);
        String endQuote=excerpt(span,"endQuote",1000,message);
        int start=uniquePosition(message,startQuote);
        int end=uniquePosition(message,endQuote)+endQuote.length();
        if(end<start+startQuote.length() || end-start>20000)
            throw new IllegalArgumentException("Definition source quotes must bound one complete ordered excerpt");
        return message.substring(start,end);
    }

    private static int uniquePosition(String message,String quote) {
        int first=message.indexOf(quote);
        if(message.indexOf(quote,first+1)>=0)
            throw new IllegalArgumentException("Definition source quote must identify a unique current-message position");
        return first;
    }

    private static String excerpt(JsonNode proposal,String field,int limit,String message) {
        var value=proposal.path(field);
        if(!value.isTextual()||value.textValue().isBlank()||value.textValue().length()>limit
                ||!message.contains(value.textValue()))
            throw new IllegalArgumentException("Definition proposal "+field+" must be a verbatim current-message excerpt");
        return value.textValue();
    }
}
