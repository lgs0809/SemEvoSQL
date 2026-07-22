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

import cn.lgs.semevosql.common.LocalSecurityProperties;
import cn.lgs.semevosql.common.OperatorContextProperties;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Computes contributions from query receipts and revision-specific consent, never a mutable hit counter. */
@Repository
public class ProjectDefinitionContributions {
    private final JdbcTemplate jdbc;
    private final LocalSecurityProperties security;
    private final OperatorContextProperties operators;
    public ProjectDefinitionContributions(JdbcTemplate jdbc, LocalSecurityProperties security, OperatorContextProperties operators) {
        this.jdbc = jdbc; this.security = security; this.operators = operators;
    }
    public record Totals(int authorizedSources, int validUsers, int validUses, String fingerprint) {}
    private record Source(long preference, int revision, String user, int authorizationRevision) {}
    private record Use(long preference, int revision, String user, String run, String owner) {}
    private record Snapshot(Totals totals, List<Source> sources, List<Use> uses) {}
    private record UseIdentity(long preference, int revision, String run) {}
    private record HistoricalReceipt(long id,String user,String owner) {}

    public Totals totals(long candidate, int contentRevision, long project) {
        return snapshot(candidate,contentRevision,project).totals();
    }

    private Snapshot snapshot(long candidate, int contentRevision, long project) {
        var sources = jdbc.query("""
            SELECT s.preference_id,s.definition_revision,p.user_id,a.authorization_revision
            FROM qw_project_definition_source s
            JOIN qw_user_semantic_preference p ON p.id=s.preference_id AND p.project_id=? AND NOT p.archived
            JOIN LATERAL (SELECT choice,authorization_revision,principal_id FROM qw_user_semantic_authorization a
                WHERE a.preference_id=s.preference_id AND a.definition_revision=s.definition_revision
                ORDER BY authorization_revision DESC LIMIT 1) a ON a.principal_id=p.user_id
            WHERE s.candidate_id=? AND s.candidate_content_revision=? AND
            """+PersonalDefinitionSharingPolicy.SOURCE_AUTHORIZED+" ORDER BY s.preference_id,s.definition_revision",
            (r,n)->new Source(r.getLong(1),r.getInt(2),r.getString(3),r.getInt(4)), project,candidate,contentRevision)
            .stream().filter(s->trusted(s.user(),project)).toList();
        var keys = new HashSet<String>();
        sources.forEach(s->keys.add(s.preference()+":"+s.revision()));
        var uses = jdbc.query("""
            SELECT s.preference_id,s.definition_revision,p.user_id,u.run_id,r.request_payload,v.created_by
            FROM qw_project_definition_source s JOIN qw_user_semantic_preference p ON p.id=s.preference_id
            JOIN qw_user_semantic_preference_usage u ON u.preference_id=s.preference_id AND u.definition_revision=s.definition_revision
            JOIN qw_query_run r ON r.run_id=u.run_id AND r.project_id=p.project_id
            LEFT JOIN qw_project_conversation v ON v.conversation_id=r.thread_id AND v.project_id=r.project_id AND v.status<>'DELETED'
            WHERE s.candidate_id=? AND s.candidate_content_revision=? AND u.valid AND u.event_type='COUNTED'
              AND r.status IN ('SUCCEEDED','FAILED','CANCELLED','EXPIRED')
              AND r.run_type IN ('INTERACTIVE_QUERY','EXTERNAL_MCP_QUERY')
              AND EXISTS (SELECT 1 FROM qw_sql_execution_attempt x WHERE x.run_id=r.run_id AND x.phase='QUERY')
              AND
            """+PersonalDefinitionSharingPolicy.USE_AUTHORIZED+" ORDER BY p.user_id,u.run_id,s.preference_id,s.definition_revision",
            (r,n)->new Use(r.getLong(1),r.getInt(2),r.getString(3),r.getString(4),
                RuntimePrincipalResolver.storedPrincipal(r.getString(5),r.getString(6))), candidate,contentRevision)
            .stream().filter(u->keys.contains(u.preference()+":"+u.revision()) && u.user().equals(u.owner())).toList();
        var users = new TreeSet<String>(); var runs = new TreeSet<String>();
        uses.forEach(u->{users.add(u.user()); runs.add(u.run());});
        // Consent withdrawal and removal of a trusted account change this approval input even without a new use.
        String fingerprint = PersonalDefinitionSnapshot.hash(Map.of("sources",sources,"uses",uses));
        return new Snapshot(new Totals(sources.size(),users.size(),runs.size(),fingerprint),sources,uses);
    }

    /** Historical receipts are classified by the same accepted snapshot used for approval. */
    public Map<String,Object> evidence(long candidate,int contentRevision,long project,int offset,int limit) {
        var snapshot=snapshot(candidate,contentRevision,project);
        var authorized=new HashSet<String>();
        snapshot.sources().forEach(s->authorized.add(s.preference()+":"+s.revision()));
        var counted=new HashSet<UseIdentity>();
        snapshot.uses().forEach(u->counted.add(new UseIdentity(u.preference(),u.revision(),u.run())));
        String from="""
            FROM qw_project_definition_source s
            JOIN qw_user_semantic_preference p ON p.id=s.preference_id AND p.project_id=?
            JOIN qw_user_semantic_preference_usage u ON u.preference_id=s.preference_id AND u.definition_revision=s.definition_revision
            JOIN qw_query_run r ON r.run_id=u.run_id AND r.project_id=p.project_id
            LEFT JOIN qw_project_conversation v ON v.conversation_id=r.thread_id AND v.project_id=r.project_id AND v.status<>'DELETED'
            WHERE s.candidate_id=? AND s.candidate_content_revision=?
            """;
        var history=jdbc.query("SELECT u.id,p.user_id,r.request_payload,v.created_by "+from+" AND "+PersonalDefinitionSharingPolicy.HISTORICALLY_SHARED_USAGE+" ORDER BY u.create_time DESC,u.id DESC",
            (r,n)->new HistoricalReceipt(r.getLong(1),r.getString(2),RuntimePrincipalResolver.storedPrincipal(r.getString(3),r.getString(4))),project,candidate,contentRevision)
            .stream().filter(r->r.user().equals(r.owner())).toList();
        var ids=history.stream().skip(offset).limit(limit).map(HistoricalReceipt::id).toList();
        var parameters=new ArrayList<Object>(List.of(project,candidate,contentRevision));parameters.addAll(ids);
        String selected=ids.isEmpty()?"FALSE":"u.id IN ("+String.join(",",Collections.nCopies(ids.size(),"?"))+")";
        var rows=jdbc.queryForList("""
            SELECT p.user_id,s.preference_id,s.definition_revision,s.equivalence_kind,
              u.run_id,u.event_type,u.valid,u.create_time,u.update_time,r.status AS run_status,
              r.project_version_id
            """+from+" AND "+selected+" ORDER BY u.create_time DESC,u.id DESC",parameters.toArray());
        var records=rows.stream().<Map<String,Object>>map(row->{
            var result=new LinkedHashMap<>(row);
            long preference=((Number)row.get("preference_id")).longValue();
            int revision=((Number)row.get("definition_revision")).intValue();
            boolean accepted=counted.contains(new UseIdentity(preference,revision,Objects.toString(row.get("run_id"))));
            boolean source=authorized.contains(preference+":"+revision);
            String state=accepted?"COUNTED":!Boolean.TRUE.equals(row.get("valid"))||"WITHDRAWN".equals(row.get("event_type"))
                ?"WITHDRAWN":!source?"SOURCE_INELIGIBLE":"NOT_COUNTED";
            result.put("counted",accepted);result.put("authorized_source",source);result.put("contribution_state",state);
            for(String key:List.of("create_time","update_time")) {
                if(row.get(key) instanceof java.util.Date date)result.put(key,date.toInstant().toString());
            }
            return result;
        }).toList();
        return Map.of("totals",snapshot.totals(),"records",records,"totalRecords",history.size(),"offset",offset,"limit",limit);
    }

    private boolean trusted(String user,long project) {
        if (user==null || user.isBlank() || RuntimePrincipalResolver.ANONYMOUS.equals(user)) return false;
        // A single-user workspace has a real configured operator, but cannot manufacture multiple identities.
        if (!security.isEnabled()) return user.equals(operators.getDefaultOperator());
        return security.getAccounts().containsKey(user) && security.canAccess(user,project);
    }
}
