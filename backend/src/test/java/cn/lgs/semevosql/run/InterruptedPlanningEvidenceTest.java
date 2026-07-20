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
package cn.lgs.semevosql.run;

import cn.lgs.semevosql.common.json.CanonicalJson;
import cn.lgs.semevosql.model.ModelCallPurpose;
import cn.lgs.semevosql.model.SemEvoSQLModelGateway.ModelCallResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class InterruptedPlanningEvidenceTest {
    @Test void persistedFactsRetainCallIdentityAndUsageWithoutExposingPrivateModelOutput() {
        var json=new CanonicalJson();
        String privateResponse="private formula and internal model explanation";
        var call=new ModelCallResult("actual-call-id",ModelCallPurpose.SEMANTIC_PLANNING,
            privateResponse,2,1200,500,40,null,3);
        var evidence=QueryExecutionEvidence.interruptedPlanning("REJECTED","INVALID_GOVERNED_SELECTION",List.of(call),json);
        var persisted=json.write(evidence);
        assertThat(persisted).contains("actual-call-id","SEMANTIC_PLANNING","1200","500","40",
            "httpAttempts\":3",json.hash(privateResponse)).doesNotContain(privateResponse,"internal model explanation");
        assertThat(evidence.modelCalls()).hasSize(1);
    }
}
