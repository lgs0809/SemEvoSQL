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
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProjectDefinitionPublicationPolicyTest {
    private ProjectDefinitionPublicationPolicy.Decision decision(int users,int uses,boolean exists,boolean conflict) {
        return ProjectDefinitionPublicationPolicy.assess(new ProjectDefinitionPublicationPolicy.Evidence(3,users,uses,true,true,exists,conflict,true));
    }
    @Test void firstPublicationRequiresBothIndependentUsersAndActualUses() {
        assertFalse(decision(2,100,false,false).automaticPublicationAllowed());
        assertFalse(decision(3,4,false,false).automaticPublicationAllowed());
        assertTrue(decision(3,5,false,false).automaticPublicationAllowed());
        assertTrue(decision(2,4,false,false).administratorMayApprove());
    }
    @Test void quorumNeverChangesExistingPublicMeaningOrBypassesConflicts() {
        for(boolean exists:new boolean[]{true,false}) for(boolean conflict:new boolean[]{true,false}) {
            if(!exists&&!conflict)continue;
            var result=decision(100,1000,exists,conflict);
            assertTrue(result.thresholdReached());assertFalse(result.automaticPublicationAllowed());
            assertEquals("NEEDS_ADMIN_REVIEW",result.lifecycle());
        }
    }
    @Test void evenAdministratorCannotApproveWithdrawnUnsafeOrUnassessedInput() {
        for(var input:new ProjectDefinitionPublicationPolicy.Evidence[]{
            new ProjectDefinitionPublicationPolicy.Evidence(0,3,5,true,true,false,false,true),
            new ProjectDefinitionPublicationPolicy.Evidence(3,3,5,true,true,false,false,false),
            new ProjectDefinitionPublicationPolicy.Evidence(3,3,5,false,true,false,false,true),
            new ProjectDefinitionPublicationPolicy.Evidence(3,3,5,true,false,false,false,true)}) {
            var result=ProjectDefinitionPublicationPolicy.assess(input);
            assertFalse(result.automaticPublicationAllowed());assertFalse(result.administratorMayApprove());
        }
    }
}
