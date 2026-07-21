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

/** One consent predicate for recall and promotion. Existing revision consent remains the default. */
final class PersonalDefinitionSharingPolicy {
    private PersonalDefinitionSharingPolicy() {}
    static final String SOURCE_AUTHORIZED="""
        ((SELECT a.choice FROM qw_user_semantic_authorization a WHERE a.preference_id=s.preference_id
          AND a.definition_revision=s.definition_revision ORDER BY a.authorization_revision DESC LIMIT 1)='ALLOWED'
        OR EXISTS(SELECT 1 FROM qw_personal_sharing_use_decision x
          JOIN qw_user_semantic_preference_usage u ON u.preference_id=x.preference_id
            AND u.definition_revision=x.definition_revision AND u.run_id=x.run_id AND u.valid AND u.event_type='COUNTED'
          WHERE x.preference_id=s.preference_id AND x.definition_revision=s.definition_revision AND x.allowed
            AND NOT EXISTS(SELECT 1 FROM qw_personal_sharing_use_decision newer
              WHERE newer.preference_id=x.preference_id AND newer.definition_revision=x.definition_revision
                AND newer.run_id=x.run_id AND newer.id>x.id)))
        """;
    static final String USE_AUTHORIZED="""
        COALESCE((SELECT x.allowed FROM qw_personal_sharing_use_decision x
          WHERE x.preference_id=u.preference_id AND x.definition_revision=u.definition_revision AND x.run_id=u.run_id
          ORDER BY x.id DESC LIMIT 1),
          (SELECT a.choice='ALLOWED' FROM qw_user_semantic_authorization a WHERE a.preference_id=u.preference_id
            AND a.definition_revision=u.definition_revision ORDER BY a.authorization_revision DESC LIMIT 1),FALSE)
        """;
    // Read-only audit visibility: preserve previously shared receipts, exclude later private uses.
    // A revision-wide ALLOWED decision also shares the receipts already known at that decision.
    static final String HISTORICALLY_SHARED_USAGE="""
        (EXISTS(SELECT 1 FROM qw_personal_sharing_use_decision x WHERE x.preference_id=u.preference_id
          AND x.definition_revision=u.definition_revision AND x.run_id=u.run_id AND x.allowed)
        OR EXISTS(SELECT 1 FROM qw_user_semantic_authorization a WHERE a.preference_id=u.preference_id
          AND a.definition_revision=u.definition_revision AND a.principal_id=p.user_id AND a.choice='ALLOWED'
          AND (a.create_time>=u.create_time OR NOT EXISTS(SELECT 1 FROM qw_user_semantic_authorization newer
            WHERE newer.preference_id=a.preference_id AND newer.definition_revision=a.definition_revision
              AND newer.authorization_revision>a.authorization_revision AND newer.create_time<=u.create_time))
          AND COALESCE((SELECT x.allowed FROM qw_personal_sharing_use_decision x
            WHERE x.preference_id=u.preference_id AND x.definition_revision=u.definition_revision AND x.run_id=u.run_id
              AND x.create_time<=GREATEST(a.create_time,u.create_time) ORDER BY x.id DESC LIMIT 1),TRUE)))
        """;
}
