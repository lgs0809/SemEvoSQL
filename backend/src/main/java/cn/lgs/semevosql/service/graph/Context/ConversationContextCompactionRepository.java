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
package cn.lgs.semevosql.service.graph.Context;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import cn.lgs.semevosql.service.graph.Context.ConversationTurnRepository.ConversationTurn;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Database-neutral latest-snapshot repository for conversation compaction. */
@Repository
public class ConversationContextCompactionRepository {

	private final JdbcTemplate jdbc;

	public ConversationContextCompactionRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Optional<ConversationContextCompactionSnapshot> find(String threadId) {
		List<ConversationContextCompactionSnapshot> values = jdbc.query("""
				SELECT thread_id, covered_through_sequence, summary_json, source_digest, summary_version,
				       create_time, update_time, revision, valid
				FROM qw_conversation_context_compaction WHERE thread_id = ?
				""",
				(rs, rowNum) -> new ConversationContextCompactionSnapshot(rs.getString("thread_id"),
						rs.getLong("covered_through_sequence"), rs.getString("summary_json"),
						rs.getString("source_digest"), rs.getInt("summary_version"),
						time(rs.getTimestamp("create_time")), time(rs.getTimestamp("update_time")),
						rs.getLong("revision"), rs.getBoolean("valid")),
				threadId);
		return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
	}

	/** Commit only the exact baseline and unchanged source batch used outside the transaction. */
	@Transactional
	public boolean compareAndSet(ConversationContextCompactionSnapshot expected,
			ConversationContextCompactionSnapshot next, List<ConversationTurn> batch) {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("Compaction commit requires a transaction");
		}
		if (batch == null || batch.isEmpty() || batch.size() > 200
				|| batch.get(batch.size()-1).turnSequence() != next.coveredThroughSequence()
				|| expected != null && !Objects.equals(expected.threadId(), next.threadId())) {
			throw new IllegalArgumentException("Compaction source scope is invalid");
		}
		jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "semevosql-compaction:"+next.threadId());
		long from=expected == null || !expected.valid() ? 0 : expected.coveredThroughSequence();
		if (next.coveredThroughSequence() <= from) throw new IllegalArgumentException("Compaction must advance its covered prefix");
		var current=jdbc.query("""
				SELECT id, turn_sequence, revision FROM qw_conversation_turn
				WHERE thread_id=? AND status='COMPLETED' AND turn_sequence>? AND turn_sequence<=?
				ORDER BY turn_sequence LIMIT ?
				""", (rs,n)->new Source(rs.getString(1),rs.getLong(2),rs.getLong(3)), next.threadId(),from,
				next.coveredThroughSequence(),batch.size()+1);
		if (batch.stream().anyMatch(t->!next.threadId().equals(t.threadId()))
				|| !current.equals(batch.stream().map(t->new Source(t.id(),t.turnSequence(),t.revision())).toList())) return false;
		LocalDateTime now=next.updateTime()==null?LocalDateTime.now():next.updateTime();
		if (expected==null) {
			return jdbc.update("""
					INSERT INTO qw_conversation_context_compaction(thread_id,covered_through_sequence,summary_json,
					 source_digest,summary_version,create_time,update_time,revision,valid)
					VALUES (?,?,CAST(? AS JSONB),?,?,?,?,1,TRUE) ON CONFLICT(thread_id) DO NOTHING
					""",next.threadId(),next.coveredThroughSequence(),next.summaryJson(),next.sourceDigest(),next.summaryVersion(),
					Timestamp.valueOf(now),Timestamp.valueOf(now))==1;
		}
		return jdbc.update("""
				UPDATE qw_conversation_context_compaction SET covered_through_sequence=?,summary_json=CAST(? AS JSONB),
				 source_digest=?,summary_version=?,update_time=?,revision=revision+1,valid=TRUE
				WHERE thread_id=? AND revision=? AND covered_through_sequence=? AND source_digest=? AND valid=?
				""",next.coveredThroughSequence(),next.summaryJson(),next.sourceDigest(),next.summaryVersion(),Timestamp.valueOf(now),
				next.threadId(),expected.revision(),expected.coveredThroughSequence(),expected.sourceDigest(),expected.valid())==1;
	}

	private record Source(String id,long sequence,long revision) { }

	private static LocalDateTime time(Timestamp value) {
		return value == null ? null : value.toLocalDateTime();
	}

}
