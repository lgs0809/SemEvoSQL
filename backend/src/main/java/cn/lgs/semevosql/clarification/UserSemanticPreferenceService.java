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
package cn.lgs.semevosql.clarification;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Exact project-scoped per-user phrase -> governed semantic asset preferences. */
@Service
public class UserSemanticPreferenceService {

	private static final Set<String> PERSONAL_BINDING_TYPES = Set.of("METRIC", "DIMENSION", "ENUM_VALUE", "TIME_COLUMN");

	private final JdbcTemplate jdbc;

	private final SemanticBindingTargetValidator targetValidator;

    private final PersonalSemanticDefinitionStore definitions;

	public UserSemanticPreferenceService(JdbcTemplate jdbc, SemanticBindingTargetValidator targetValidator) {
		this.jdbc = jdbc;
		this.targetValidator = targetValidator;
        this.definitions = new PersonalSemanticDefinitionStore(jdbc);
	}
    @org.springframework.beans.factory.annotation.Autowired
    public UserSemanticPreferenceService(JdbcTemplate jdbc,SemanticBindingTargetValidator targetValidator,PersonalSemanticDefinitionStore definitions) {
        this.jdbc=jdbc;this.targetValidator=targetValidator;this.definitions=definitions;
    }

	public Optional<UserSemanticPreference> find(Long projectId, String userId, String rawPhrase) {
		String normalized = normalizePhrase(rawPhrase);
		if (projectId == null || !hasText(userId) || normalized.isBlank()) {
			return Optional.empty();
		}
		return jdbc.query("""
				SELECT * FROM qw_user_semantic_preference
				WHERE project_id = ? AND user_id = ? AND normalized_phrase = ? AND NOT archived
				""", this::map, projectId, userId.trim(), normalized).stream().findFirst();
	}

	public List<UserSemanticPreference> applicable(Long projectId, String userId, String query) {
		String normalizedQuery = normalizePhrase(query);
		if (projectId == null || !hasText(userId) || normalizedQuery.isBlank()) {
			return List.of();
		}
		return jdbc.query("""
				SELECT * FROM qw_user_semantic_preference
				WHERE project_id = ? AND user_id = ? AND NOT archived
				ORDER BY LENGTH(normalized_phrase) DESC, id
				""", this::map, projectId, userId.trim())
			.stream()
			.filter(value -> !value.normalizedPhrase().isBlank() && normalizedQuery.contains(value.normalizedPhrase()))
			.toList();
	}

    @Transactional
    public UserSemanticPreference save(Long projectId,String userId,String rawPhrase,String assetType,String assetKey,String businessLabel) {
        return confirmAsset(projectId,targetValidator.activeVersionId(projectId),userId,rawPhrase,assetType,assetKey,
            businessLabel,null,PersonalSemanticDefinitionStore.Sharing.PRIVATE,null);
    }

    @Transactional
    public UserSemanticPreference confirmAsset(Long projectId,Long versionId,String principal,String phrase,String type,
            String key,String label,String sourceId,PersonalSemanticDefinitionStore.Sharing sharing,Integer expectedRevision) {
        requirePersonalType(type);
        var captured=targetValidator.captureAsset(projectId,versionId,type,key);
        // The entire frozen definition is retained alongside the user's ordinary business phrase.
        var definition=definitions.confirm(new PersonalSemanticDefinitionStore.Confirmation(projectId,principal,phrase,
            label,type,key,label,"ASSET_CONFIRMATION",sourceId,versionId,captured.snapshot(),captured.dependencyFingerprint(),sharing,expectedRevision));
        return requireById(definition.preferenceId());
    }

    @Transactional
    public UserSemanticPreference confirmText(Long projectId,Long versionId,String principal,String phrase,String text,
            String sourceId,PersonalSemanticDefinitionStore.Sharing sharing,Integer expectedRevision) {
        var snapshot=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        snapshot.put("completeDefinitionRecorded",true);snapshot.put("confirmedText",required(text,"definitionText"));
        var definition=definitions.confirm(new PersonalSemanticDefinitionStore.Confirmation(projectId,principal,phrase,text,
            "TEXT_DEFINITION",normalizePhrase(phrase),phrase,"TEXT_CONFIRMATION",sourceId,versionId,snapshot,null,sharing,expectedRevision));
        return requireById(definition.preferenceId());
    }

    public void freezeQuestionBase(String question,Long project,Long version,String principal,String phrase){definitions.freezeQuestionBase(question,project,version,principal,phrase);}
    public int questionBase(String question,Long project,Long version,String principal,String phrase){return definitions.questionBase(question,project,version,principal,phrase);}

    public List<PersonalSemanticDefinitionStore.Definition> applicableDefinitions(Long projectId,String principal,String query) {
        return definitions.applicable(projectId,principal,query);
    }
    public PersonalSemanticDefinitionStore.Definition definition(long id,int revision){return definitions.require(id,revision);}

    @Transactional
    public void delete(Long projectId,String userId,String rawPhrase) {
        definitions.archive(projectId,required(userId,"userId"),requiredNormalize(rawPhrase));
    }
    @Transactional
    public void recordApplied(Long preferenceId,String runId) {
        if(preferenceId!=null&&hasText(runId))recordApplied(preferenceId,requireById(preferenceId).currentRevision(),runId);
    }
    @Transactional
    public void recordApplied(Long preferenceId,int revision,String runId) {
        if(preferenceId==null||!hasText(runId))return;
        definitions.lockPreferences(List.of(preferenceId));
        definitions.require(preferenceId,revision);
        jdbc.update("""
            INSERT INTO qw_user_semantic_preference_usage(preference_id,definition_revision,run_id,event_type,valid,idempotency_key)
            VALUES (?,?,?,'APPLIED',TRUE,?) ON CONFLICT DO NOTHING
            """,preferenceId,revision,runId,"personal-use:"+preferenceId+":"+revision+":"+runId);
    }

    @Transactional
    public List<UpgradePrompt> finalizeSuccessfulRun(String runId) {
        if(!hasText(runId))return List.of();
        String status=jdbc.query("SELECT status FROM qw_query_run WHERE run_id=?",(rs,n)->rs.getString(1),runId).stream().findFirst().orElse(null);
        if(status==null || !Set.of("SUCCEEDED","FAILED","CANCELLED").contains(status))return List.of();
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM qw_sql_execution_attempt WHERE run_id=? AND phase='QUERY')",Boolean.class,runId)))return List.of();
        var ids=jdbc.query("SELECT DISTINCT preference_id FROM qw_user_semantic_preference_usage WHERE run_id=? AND valid ORDER BY preference_id",(rs,n)->rs.getLong(1),runId);
        definitions.lockPreferences(ids);
        var prompts=new java.util.ArrayList<UpgradePrompt>();
        for(long id:ids) {
            jdbc.update("UPDATE qw_user_semantic_preference_usage SET event_type='COUNTED',update_time=CURRENT_TIMESTAMP WHERE preference_id=? AND run_id=? AND valid AND event_type='APPLIED'",id,runId);
            var current=refreshPromotionState(id);
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT archived FROM qw_user_semantic_preference WHERE id=?",Boolean.class,id)))continue;
            var definition=definitions.current(id);
            if(current.hitCount()>=5&&definition.sharing()==PersonalSemanticDefinitionStore.Sharing.PRIVATE) {
                definitions.authorize(id,current.currentRevision(),PersonalSemanticDefinitionStore.Sharing.AWAITING_CONSENT,current.userId(),"five-valid-uses:"+runId);
                jdbc.update("UPDATE qw_user_semantic_preference SET upgrade_prompt_pending=TRUE WHERE id=?",id);
                definition=definitions.current(id);
            }
            if(current.hitCount()>=5&&definition.sharing()==PersonalSemanticDefinitionStore.Sharing.AWAITING_CONSENT
                && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM qw_user_semantic_authorization WHERE preference_id=? AND definition_revision=? AND choice='AWAITING_CONSENT' AND source_id=?)",Boolean.class,id,current.currentRevision(),"five-valid-uses:"+runId))) {
                prompts.add(new UpgradePrompt(id,current.displayPhrase(),current.businessLabel(),current.hitCount(),
                    definition.revision(),definition.contentHash(),definition.text()));
            }
        }
        return List.copyOf(prompts);
    }
    @Transactional
    public void invalidateRunUsage(String runId,Long preferenceId) {
        if(!hasText(runId))return;
        var ids=jdbc.query("SELECT DISTINCT preference_id FROM qw_user_semantic_preference_usage WHERE run_id=? AND valid AND (?::bigint IS NULL OR preference_id=?) ORDER BY preference_id",
            (rs,n)->rs.getLong(1),runId,preferenceId,preferenceId);
        definitions.lockPreferences(ids);
        for(long id:ids) {
            jdbc.update("UPDATE qw_user_semantic_preference_usage SET valid=FALSE,update_time=CURRENT_TIMESTAMP WHERE preference_id=? AND run_id=? AND valid",id,runId);
            jdbc.update("UPDATE qw_user_semantic_preference SET correction_count=correction_count+1 WHERE id=?",id);
            refreshPromotionState(id);
        }
    }
    @Transactional
    public UserSemanticPreference continuePersonal(Long preferenceId){return dismissUpgrade(preferenceId);}
    @Transactional
    public UserSemanticPreference dismissUpgrade(Long preferenceId) {
        lock(preferenceId);var current=requireById(preferenceId);
        definitions.authorize(preferenceId,current.currentRevision(),PersonalSemanticDefinitionStore.Sharing.DECLINED,current.userId(),"declined-promotion");
        return requireById(preferenceId);
    }
    @Transactional
    public UserSemanticPreference allowSharing(Long preferenceId) {
        lock(preferenceId);var current=requireById(preferenceId);
        definitions.authorize(preferenceId,current.currentRevision(),PersonalSemanticDefinitionStore.Sharing.ALLOWED,current.userId(),"allowed-promotion");
        return requireById(preferenceId);
    }
    @Transactional
    public UserSemanticPreference allowSharing(Long preferenceId,int expectedRevision,String expectedContentHash) {
        lock(preferenceId);var current=requireById(preferenceId);var definition=definitions.current(preferenceId);
        if(definition.revision()!=expectedRevision||!Objects.equals(definition.contentHash(),expectedContentHash))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                "这条个人口径已经修改，请核对当前完整定义后重新选择分享");
        definitions.authorize(preferenceId,current.currentRevision(),PersonalSemanticDefinitionStore.Sharing.ALLOWED,current.userId(),"allowed-promotion");
        return requireById(preferenceId);
    }
    private void lock(long id){definitions.lockPreferences(List.of(id));}

	public Optional<UserSemanticPreference> findById(Long preferenceId) {
		return jdbc.query("SELECT * FROM qw_user_semantic_preference WHERE id = ?", this::map, preferenceId)
			.stream()
			.findFirst();
	}

    private UserSemanticPreference refreshPromotionState(Long preferenceId) {
        // Actual use and query success are distinct. Only completed, actually attempted queries count.
        jdbc.update("""
            UPDATE qw_user_semantic_preference p SET hit_count=(
              SELECT count(DISTINCT u.run_id) FROM qw_user_semantic_preference_usage u JOIN qw_query_run r ON r.run_id=u.run_id
              WHERE u.preference_id=p.id AND u.definition_revision=p.current_revision AND u.valid AND u.event_type='COUNTED' AND r.status IN ('SUCCEEDED','FAILED','CANCELLED')),
              last_used_time=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP WHERE p.id=?
            """,preferenceId);
        return requireById(preferenceId);
    }

	private UserSemanticPreference requireById(Long preferenceId) {
		return findById(preferenceId)
			.orElseThrow(() -> new IllegalArgumentException("User semantic preference not found: " + preferenceId));
	}

	private UserSemanticPreference map(ResultSet rs, int rowNum) throws SQLException {
		return new UserSemanticPreference(rs.getLong("id"), rs.getLong("project_id"), rs.getString("user_id"),
				rs.getString("normalized_phrase"), rs.getString("display_phrase"), rs.getString("asset_type"),
				rs.getString("asset_key"), rs.getString("business_label"), rs.getLong("hit_count"),
				rs.getLong("correction_count"), rs.getLong("next_upgrade_prompt_at"),
				rs.getBoolean("upgrade_prompt_pending"), rs.getBoolean("upgrade_dismissed"),
				time(rs.getTimestamp("create_time")), time(rs.getTimestamp("update_time")),
				time(rs.getTimestamp("last_used_time")),rs.getInt("current_revision"));
	}

	public static String normalizePhrase(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "").trim();
	}

	private static String requiredNormalize(String value) {
		String normalized = normalizePhrase(value);
		if (normalized.isBlank()) {
			throw new IllegalArgumentException("rawPhrase is required");
		}
		return normalized;
	}

	private static void requirePersonalType(String assetType) {
		if (!PERSONAL_BINDING_TYPES.contains(assetType)) {
			throw new IllegalArgumentException("Personal preference cannot redefine semantic asset type: " + assetType);
		}
	}

	private static String required(String value, String field) {
		if (!hasText(value)) {
			throw new IllegalArgumentException(field + " is required");
		}
		return value.trim();
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private static LocalDateTime time(Timestamp value) {
		return value == null ? null : value.toLocalDateTime();
	}

	public record UserSemanticPreference(Long id, Long projectId, String userId, String normalizedPhrase,
			String displayPhrase, String assetType, String assetKey, String businessLabel, long hitCount,
			long correctionCount, long nextUpgradePromptAt, boolean upgradePromptPending, boolean upgradeDismissed,
			LocalDateTime createTime, LocalDateTime updateTime, LocalDateTime lastUsedTime, int currentRevision) {
	}

	public record UpgradePrompt(Long preferenceId, String phrase, String businessLabel, long hitCount,
            int definitionRevision,String sourceContentHash,String completeDefinition) {
	}

}
