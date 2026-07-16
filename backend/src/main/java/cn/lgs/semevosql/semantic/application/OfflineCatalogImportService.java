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

import cn.lgs.semevosql.common.*;
import cn.lgs.semevosql.project.application.ProjectScopeService;
import cn.lgs.semevosql.project.domain.*;
import cn.lgs.semevosql.semantic.domain.*;
import cn.lgs.semevosql.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Durable preview, immutable input, current-source verification and atomic draft replacement. */
@Service
@RequiredArgsConstructor
public class OfflineCatalogImportService {
    private final SourceSchemaExportService source;
    private final SemanticProjectRepository projects;
    private final SemanticCatalogApplicationService catalogs;
    private final SemanticCatalogRepository repository;
    private final ProjectScopeService scope;
    private final LocalSecurityProperties security;
    private final LocalOperatorService operators;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JsonNode export(Long project,Long version,OperatorContext operator) throws Exception {
        authorize(project,operator);return source.capture(project,version);
    }
    public Preview preview(Long project,Long versionId,String raw,OperatorContext operator) throws Exception {
        authorize(project,operator);var version=requireDraft(project,versionId);
        var pack=OfflineCatalogProtocol.parse(raw);var physical=source.capture(project,versionId);
        var snapshot=OfflineCatalogProtocol.convert(pack,physical,projects.findDatasourceBindings(versionId));
        snapshot.setProjectId(project);snapshot.setProjectVersionId(versionId);
        var violations=catalogs.validateDraftWrite(snapshot);
        if(!violations.isEmpty())throw new IllegalArgumentException("Invalid converted catalog: "+String.join("; ",violations));
        var baseline=SemanticCatalogFingerprint.fingerprint(repository.loadCatalog(project,versionId));
        String inputHash=OfflineCatalogProtocol.hash(pack),sourceHash=OfflineCatalogProtocol.hash(physical),id=UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO qw_semantic_catalog_import(import_id,project_id,project_version_id,operator_name,input_hash,source_fingerprint,
                baseline_catalog_hash,baseline_version_revision,input_json,planned_catalog_json)
            VALUES (?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)
            ON CONFLICT(project_version_id,operator_name,input_hash,baseline_catalog_hash,baseline_version_revision) DO NOTHING
            """,id,project,versionId,operator.operator(),inputHash,sourceHash,baseline,version.getRevision(),json(pack),json(snapshot));
        var rows=jdbc.queryForList("""
            SELECT import_id,status,receipt_json FROM qw_semantic_catalog_import
            WHERE project_version_id=? AND operator_name=? AND input_hash=? AND baseline_catalog_hash=? AND baseline_version_revision=?
            """,versionId,operator.operator(),inputHash,baseline,version.getRevision());
        if(rows.size()!=1)throw new IllegalStateException("Import preview identity missing");
        return new Preview(rows.get(0).get("import_id").toString(),rows.get(0).get("status").toString(),inputHash,sourceHash,
            baseline,version.getRevision(),snapshot,pack.path("unresolvedIssues"));
    }
    public Receipt commit(Long project,Long versionId,String id,OperatorContext operator) throws Exception {
        authorize(project,operator);var preview=read(project,versionId,id,operator,false);
        if(preview.get("status").equals("COMMITTED"))return receipt(preview);
        // JDBC metadata capture and all protocol work happen before the metadata publication transaction.
        var physical=source.capture(project,versionId);
        if(!OfflineCatalogProtocol.hash(physical).equals(preview.get("source_fingerprint")))conflict("Physical schema changed after preview");
        var pack=OfflineCatalogProtocol.parse(preview.get("input_json").toString());
        var planned=OfflineCatalogProtocol.convert(pack,physical,projects.findDatasourceBindings(versionId));
        planned.setProjectId(project);planned.setProjectVersionId(versionId);
        return transactions.execute(status->{
            authorize(project,operator);
            jdbc.queryForList("SELECT id FROM qw_project_version WHERE project_id=? AND id=? FOR UPDATE",project,versionId);
            var locked=read(project,versionId,id,operator,true);
            if(locked.get("status").equals("COMMITTED"))return receipt(locked);
            var version=requireDraft(project,versionId);
            if(((Number)locked.get("baseline_version_revision")).longValue()!=version.getRevision())conflict("Draft version changed after preview");
            if(!locked.get("baseline_catalog_hash").equals(SemanticCatalogFingerprint.fingerprint(repository.loadCatalog(project,versionId))))conflict("Draft catalog changed after preview");
            // Binding writes take the same version lock, so metadata aliases cannot change under this check.
            var current=OfflineCatalogProtocol.convert(pack,physical,projects.findDatasourceBindings(versionId));
            current.setProjectId(project);current.setProjectVersionId(versionId);
            if(!json(planned).equals(json(current)))conflict("Datasource binding changed after source verification");
            if(version.getAnalysisStatus()==InitializationAnalysisStatus.PENDING || version.getAnalysisStatus()==InitializationAnalysisStatus.FAILED) {
                version.startAnalysis();projects.updateVersion(version);
            }
            var persisted=catalogs.replaceDraftCatalog(project,versionId,current);
            version=projects.findVersion(versionId).orElseThrow();
            if(version.getAnalysisStatus()==InitializationAnalysisStatus.RUNNING){version.completeAnalysis();projects.updateVersion(version);}
            var result=new Receipt(id,"COMMITTED",project,versionId,locked.get("input_hash").toString(),
                locked.get("source_fingerprint").toString(),SemanticCatalogFingerprint.fingerprint(persisted),
                persisted.getModels().size(),persisted.getColumns().size(),persisted.getMetrics().size());
            if(jdbc.update("UPDATE qw_semantic_catalog_import SET status='COMMITTED',committed_time=CURRENT_TIMESTAMP,receipt_json=?::jsonb WHERE import_id=? AND status='PREVIEWED'",json(result),id)!=1)
                throw new IllegalStateException("Import commit lost its preview identity");
            return result;
        });
    }
    private Map<String,Object> read(Long project,Long version,String id,OperatorContext operator,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM qw_semantic_catalog_import WHERE project_id=? AND project_version_id=? AND import_id=? AND operator_name=?"+(lock?" FOR UPDATE":""),project,version,id,operator.operator());
        if(rows.size()!=1)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Import preview not found");return rows.get(0);
    }
    private SemanticProjectVersion requireDraft(Long project,Long id) {
        var version=projects.findVersion(id).orElseThrow();
        if(!project.equals(version.getProjectId()) || version.getStatus()!=ProjectVersionStatus.DRAFT)conflict("Offline imports require a mutable DRAFT version");
        return version;
    }
    private void authorize(Long project,OperatorContext operator) {
        operators.require(operator,"offline catalog import");scope.requireProject(project,operator);
        if(security.isEnabled() && !security.account(operator.operator()).isAdministrator())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Catalog import requires administrator permission");
    }
    private Receipt receipt(Map<String,Object> row) {
        try{return JsonUtil.getObjectMapper().readValue(row.get("receipt_json").toString(),Receipt.class);}
        catch(Exception invalid){throw new IllegalStateException("Import receipt invalid",invalid);}
    }
    private static void conflict(String message){throw new ResponseStatusException(HttpStatus.CONFLICT,message);}
    private static String json(Object value){try{return JsonUtil.getObjectMapper().writeValueAsString(value);}catch(Exception invalid){throw new IllegalArgumentException("Invalid import serialization",invalid);}}
    public record Preview(String importId,String status,String inputHash,String sourceFingerprint,String baselineCatalogHash,long baselineVersionRevision,SemanticCatalogSnapshot plannedCatalog,JsonNode unresolvedIssues) {}
    public record Receipt(String importId,String status,Long projectId,Long projectVersionId,String inputHash,String sourceFingerprint,String catalogHash,int models,int attributes,int metrics) {}
}
