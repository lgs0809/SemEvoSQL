# SemEvoSQL Semantic Evolution / Episode / MCP 重构实施方案

> 目标工程：当前 SemEvoSQL repository  
> 工作品牌：**SemEvoSQL** = **Semantic Evolution SQL**  
> 产品定位：**A self-evolving semantic NL2SQL platform.**  
> 日期：2026-08-18

---

## 0. 本轮目标与不可回退结论

本轮不是在现有 `Semantic Evolution Candidate -> Draft Version -> Replay -> Publish` 上加几个状态，而是重新收口 SemEvoSQL 的核心领域模型，使“自进化”成为一等能力。

固定四个概念：

1. **Corpus Revision**：当前项目拥有哪些资料/知识资产。
2. **Semantic Version**：当前项目如何理解这些业务知识。
3. **Episode**：一次完整的业务问数、澄清、纠错和最终结果经历。
4. **Semantic Evolution**：把经验证的 Episode 或资料中的业务知识，安全地变成下一版 Semantic Version。

版本规则固定为：

- **PATCH**：真实问数/普通人工 Semantic Fix 产生的语义变化，自动验证、自动生效。
- **MINOR**：新资料确实导致 Semantic Layer 变化时，自动验证、自动生效。
- **MAJOR**：只有人明确执行 `Promote Business Baseline` 时升级。
- 上传资料但没有 Semantic Diff：只增加 Corpus Revision，不增加 Semantic Version。
- Query Case 增加但没有 Semantic Diff：不增加 Semantic Version。
- Chat/Rerank Provider 变化：不增加 Semantic Version。
- Embedding Provider 变化：重建 Retrieval Index，但不增加 Semantic Version。
- Rollback：只切 Active Pointer，不制造伪 Semantic Version。
- **不引入 Live Overlay。**
- **不再用 Draft Version 承载所有试验变化；试验单位改成 SemanticChangeSet。**
- MCP 部署绑定 Project，不固定绑定 Semantic Version；新 Episode 自动解析当前 Active Semantic Version。
- 同一 Episode 默认固定语义基线，避免运行过程中静默版本漂移。

---

# 1. 品牌迁移：统一为 SemEvoSQL

## 1.1 名称与定位

工作品牌：

```text
SemEvoSQL
```

含义：

```text
Sem = Semantic
Evo = Evolution
SQL = SQL / NL2SQL
```

定位：

```text
SemEvoSQL — A self-evolving semantic NL2SQL platform.
```

中文定义：

> 从真实问数与业务资料中持续学习企业语义，并把业务意图可靠编译成 Verified SQL 的自进化 NL2SQL 平台。

在真正改 GitHub repository name 和正式发布前，需再做一次完整名称查重：GitHub exact repo/org、普通公网、PyPI、npm、Docker Hub、Maven Central；商标是独立法律检查，不把“搜不到”当成法律结论。

## 1.2 代码迁移目标

如果最终名称查重无阻断，生产代码统一迁移到：

```text
cn.lgs.semevosql.*
```

必须继续满足：

```text
Java package 必须以 cn.lgs. 开头
```

同步迁移：

```text
Maven group/artifact
Spring config prefix
Environment variables
Docker image/container/volume defaults
API primary namespace
MCP server metadata
前端品牌
README/docs/scripts
Compose/CI/release hygiene
OpenAPI metadata
日志 category
```

目标建议：

```text
Java package:     cn.lgs.semevosql
Maven groupId:    cn.lgs.semevosql
artifact prefix:  semevosql-*
Spring prefix:    semevosql.*
env prefix:       SEMEVOSQL_*
Docker:           semevosql/*
API primary:      /api/semevosql/*
```

已有公开 v1.0.0 不自动重写 Git 历史/tag。若旧 API namespace 已可能被使用，可保留一个兼容周期，内部转发到新 Application Service，并标记 deprecated；新文档只推荐新 namespace。

品牌机械 rename 放到核心领域重构稳定后执行，避免功能重构和 rename 交错导致排错困难。

---

# 2. 领域模型重新定义

最终关系：

```text
Project
├── Corpus Revisions
├── Semantic Versions
├── Conversations
│   └── Episodes
│       ├── Turns
│       ├── Attempts
│       ├── Signals
│       └── Outcome
├── Query Cases
├── Semantic ChangeSets
└── Replay / Activation History
```

严格区分：

```text
Conversation != Episode
Episode != Run
Episode != Attempt
Attempt != Query Case
Query Case != Semantic Change
SemanticChangeSet != Semantic Version
Corpus Revision != Semantic Version
Platform Version != Semantic Version
```

核心定义：

```text
Corpus Revision
= 当前系统掌握了哪些资料

Semantic Version
= 当前系统如何理解这个业务

Episode
= 围绕一个业务分析目标的一次完整求解经历

SemanticChangeSet
= 尚未提交到正式 Semantic Version 的、经归因后的语义变化
```

---

# 3. Semantic Version 新模型

## 3.1 三段式业务语义版本

格式：

```text
MAJOR.MINOR.PATCH
```

例如：

```text
3.7.24
│ │ └── runtime semantic evolution revision
│ └──── corpus/knowledge-driven revision
└────── human-promoted business baseline
```

数据库不要只存字符串，至少：

```text
major
minor
patch
displayVersion
```

并建立唯一约束：

```text
(project_id, major, minor, patch)
```

这是业务语义版本规则，不等同于传统软件 SemVer。

## 3.2 PATCH

来源：

```text
EPISODE_EVOLUTION
MANUAL_SEMANTIC_FIX
```

可能包含：

- Alias / Synonym
- Enum mapping
- Metric 新增/修正
- Dimension grounding
- Business Rule
- Time Semantic
- Grain
- Relationship
- Cross-source semantic mapping

一个 ChangeSet 无论有几个 ChangeItem，只产生一个 PATCH：

```text
2.4.17
  ↓
ChangeSet validated
  ↓
2.4.18
```

PATCH：

- 不要求人工 Publish。
- 必须 Conflict Check。
- 必须执行与风险匹配的 Replay。
- 必须等待相关 Retrieval Index Ready。
- 通过后才原子创建新版本并切 Active。
- 验证失败时保留 ChangeSet，不产生新版本。

## 3.3 MINOR

来源：

```text
CORPUS_EVOLUTION
SOURCE_KNOWLEDGE_EVOLUTION
```

流程：

```text
上传/同步资料
    ↓
Corpus Revision + 1
    ↓
Parse / Extract
    ↓
Semantic Diff
```

无 Semantic Diff：

```text
Corpus r137 -> r138
Semantic 2.4.18 -> 2.4.18
```

有 Semantic Diff 且验证通过：

```text
Corpus r137 -> r138
Semantic 2.4.18 -> 2.5.0
```

即：**上传资料本身不升级 Semantic Version；真正改变业务语义才升级 MINOR。**

## 3.4 MAJOR

只允许：

```text
MANUAL_BASELINE_PROMOTION
```

管理员明确执行：

```text
Promote Business Baseline
```

例如：

```text
2.8.41 -> 3.0.0
```

MAJOR 可在 Catalog Hash 不变化时发生，因为它代表人类确认新的业务语义基线，而不是系统自动检测 breaking change。

普通管理员修改 Alias/Metric/Rule 仍然是 `MANUAL_SEMANTIC_FIX -> PATCH`，不是 MAJOR。

## 3.5 首次初始化

项目完成初始化、Grill-Me、Validate 后形成：

```text
1.0.0
```

作为第一条 Active Semantic Baseline。初始化中间 build/checkpoint 不要都暴露成用户可见 Semantic Version。

---

# 4. Corpus Revision 独立建模

新增/重构 `CorpusRevision`，至少包含：

```text
id
projectId
revisionNo
cause
sourceSummary
contentHash / inventoryHash
status
createdAt
createdBy
```

cause 可包括：

```text
INITIAL_IMPORT
DOCUMENT_UPLOAD
DOCUMENT_DELETE
DOCUMENT_UPDATE
SOURCE_SCHEMA_REFRESH
BUSINESS_MATERIAL_SYNC
```

Corpus Revision 只回答：

> 输入给语义系统的知识资产发生了什么变化？

UI 同时展示：

```text
Semantic Version: 2.5.13
Corpus Revision:  142
```

---

# 5. SemanticChangeSet 取代 Draft Version 作为工作区

旧流程：

```text
Candidate
→ fork Draft Version
→ Replay
→ Pass/Fail
```

问题：

- 自动高频 PATCH 后会产生大量 DRAFT/REJECTED 版本。
- Version 同时承担工作区和稳定快照两个职责。
- 版本历史失真。

新流程：

```text
Active Semantic Version
        ↓
SemanticChangeSet
        ↓
Candidate Semantic State
        ↓
Impact / Replay
        ↓
PASS
        ↓
Materialize immutable SemanticVersion
        ↓
Atomic Activate
```

结论：

> ChangeSet 是 mutation workspace；Semantic Version 是验证通过后的稳定快照。

## 5.1 SemanticChangeSet

建议字段：

```text
id
projectId
baseSemanticVersionId

origin
  EPISODE
  CORPUS
  SOURCE_SCHEMA
  MANUAL

sourceEpisodeIds
sourceCorpusRevisionId

targetVersionLevel
  PATCH
  MINOR
  MAJOR

status
  OBSERVED
  ATTRIBUTED
  PROPOSED
  VALIDATING
  READY
  APPLYING
  APPLIED
  BLOCKED
  REJECTED
  SUPERSEDED

riskLevel
confidence
rootCause
semanticDiff
impactScope
evidenceSummary
replayRunId
resultSemanticVersionId
createdAt
updatedAt
```

## 5.2 SemanticChangeItem

一个 ChangeSet 可包含多个原子变化：

```text
ADD_ALIAS / REMOVE_ALIAS
ADD_METRIC / UPDATE_METRIC
ADD_DIMENSION / UPDATE_DIMENSION
ADD_ENUM_MAPPING / UPDATE_ENUM_MAPPING
ADD_RELATIONSHIP / UPDATE_RELATIONSHIP
ADD_TIME_SEMANTIC / UPDATE_TIME_SEMANTIC
ADD_GRAIN / UPDATE_GRAIN
ADD_BUSINESS_RULE / UPDATE_BUSINESS_RULE
ADD_CROSS_SOURCE_MAPPING / UPDATE_CROSS_SOURCE_MAPPING
```

每项记录：

```text
targetAsset
before
after
evidenceRefs
confidence
risk
```

---

# 6. Root Cause Attribution：不是所有失败都允许进化

禁止：

```text
query failed
→ semantic evolution
```

先分类：

```text
MODEL_REASONING_ERROR
RETRIEVAL_MISS
PLANNER_ERROR
COMPILER_ERROR
SQL_SYNTAX_ERROR
DB_CONNECTION_ERROR
TIMEOUT
PERMISSION_ERROR
DATA_QUALITY_ERROR
SEMANTIC_GAP
SEMANTIC_CONFLICT
USER_PREFERENCE
UNKNOWN
```

只有 `SEMANTIC_GAP` / `SEMANTIC_CONFLICT`，或存在足够证据证明会改变项目级业务语义时，才允许产生 ChangeSet。

进一步细分：

```text
METRIC_DEFINITION_GAP
DIMENSION_GAP
ALIAS_GAP
ENUM_MAPPING_GAP
RELATIONSHIP_GAP
TIME_SEMANTIC_GAP
GRAIN_GAP
BUSINESS_RULE_GAP
CROSS_SOURCE_GAP
```

核心防线：

```text
Execution Failure != Semantic Failure
One Bad SQL != Semantic Gap
One LLM Mistake != Semantic Gap
```

---

# 7. Evolution Promotion：什么时候经验足以成为正式知识

Query Case 不是自动变 Semantic Change。

新增/强化 `EvolutionPromotionPolicy`，综合：

```text
explicit correction
confirmed success
authoritative corpus evidence
cross-episode consistency
distinct user/session corroboration
execution evidence
catalog conflict
retrieval confidence
historical query cases
semantic scope
risk level
```

强信号：

- 用户明确纠正且最终成功确认。
- 权威资料明确支持新语义。
- 多个独立 Episode 对同一语义一致修正。
- Golden/Confirmed Query Case 支持。

弱信号：

- SQL 执行成功。
- 用户没继续追问。
- 单次模型自评。
- 单次空/非空结果。
- 单次失败。

弱信号不能独立触发项目级 Semantic Evolution。

---

# 8. Risk-aware Replay

风险建议：

### LOW

- Alias/Synonym
- 不冲突显示名称
- 受控枚举同义词
- 非行为性描述补充

### MEDIUM

- 新 Metric
- 新 Dimension
- 新 Enum Mapping
- 新 Business Rule
- 新的非冲突 Relationship

### HIGH

- 覆盖已有 Metric expression
- 修改 Existing Relationship
- 修改 Grain
- 修改 Time Semantic
- 修改默认 Filter Rule
- 修改 Cross-source join semantics
- 删除/禁用已有 Semantic Asset

Replay 范围：

```text
LOW:
  direct affected cases
  + conflict check
  + compile/preflight

MEDIUM:
  direct cases
  + dependency cases
  + related metrics/dimensions
  + representative historical cases

HIGH:
  full impacted dependency closure
  + high-value Golden cases
  + historical successful cases
  + cross-source cases
  + broad preflight/safety
```

导致本次 ChangeSet 的 Episode 可以作为 target fix case，但不能成为唯一 regression proof；HIGH 风险必须有独立证据/历史 cases/资料支持。

---

# 9. Version Commit / Activate 原子化

目标流程：

```text
1. resolve current active semantic version
2. build ChangeSet against base version
3. calculate candidate semantic state
4. validate semantic consistency
5. build/rebuild affected retrieval assets
6. run replay
7. ensure lexical/vector index READY
8. CAS check active version has not changed
9. materialize immutable SemanticVersion
10. switch project.active_semantic_version_id
11. append activation/outbox event
12. invalidate/update caches
```

第 8 步如果 Active 已变化：

```text
ChangeSet base != current active
```

必须 `REBASE_REQUIRED`：

```text
rebase
→ conflict check
→ affected replay
→ commit
```

禁止旧基线覆盖新 Active。

实现优先：optimistic lock/CAS；必要时项目级短事务锁。除非真实部署拓扑需要，不要为了这个引入 Redis distributed lock。

---

# 10. Rollback 模型

Rollback 不创建新 Semantic Version。

例如：

```text
2.4.17
2.4.18 <- bad
```

回滚：

```text
active: 2.4.18 -> 2.4.17
```

新增 `SemanticActivationEvent`：

```text
ACTIVATE
ROLLBACK
REACTIVATE
DEACTIVATE_BAD_RELEASE
```

`2.4.18` 永久保留，可标记 `BAD_RELEASE/DEACTIVATED`。

后续修复从当前 Active 重新形成 ChangeSet，得到下一合法版本，而不是把 rollback 自己当版本。

---

# 11. Episode：自进化的原子经验单位

定义：

> **Episode = 围绕一个明确业务分析目标，从提出问题，到澄清、尝试、执行、纠正，直到得到可接受结果或明确终止的完整求解过程。**

Episode 不是：

```text
Message
Conversation
HTTP Request
MCP transport session
SQL
Attempt
Run retry
```

层级：

```text
Conversation
│
├── Episode A
│   ├── Turn
│   ├── Attempt 1
│   ├── Clarification
│   ├── Turn
│   ├── Correction
│   └── Attempt 2
│
├── Episode B
│   └── ...
│
└── Episode C
```

Attempt 是一次实际求解：

```text
Retrieval
→ Semantic Blueprint
→ Compiler
→ Query Preflight
→ Verified SQL
→ Execution
```

## 11.1 Episode 边界

仍为同一 Episode：

- clarification 回答
- correction
- 同一目标 retry/repair
- 网络重连/Durable Resume
- MCP 带相同 `episodeId` 继续

新 Episode：

- 已完成目标后的 drill-down
- breakdown
- explanation
- 新独立分析目标

例：

```text
A: 上个月有效支付金额是多少？
```

澄清“自然月还是过去30天”仍是 A；用户说“不对，我说的是支付成功的钱”仍是 A。

A 完成后：

```text
按省份拆一下
```

建议：

```text
Episode B
parentEpisodeId = A
relation = DRILL_DOWN
```

再问：

```text
为什么广东下降？
```

建议：

```text
Episode C
parentEpisodeId = B
relation = EXPLAIN
```

不要通过硬编码中文关键词实现 Episode 边界。

## 11.2 Episode 状态

建议：

```text
CREATED
RUNNING
INPUT_REQUIRED
COMPLETED
REOPENED
FAILED
ABANDONED
CANCELLED
SUPERSEDED
```

Outcome 单独记录：

```text
CONFIRMED_SUCCESS
CORRECTED_SUCCESS
IMPLICIT_SUCCESS
FAILED
ABANDONED
CANCELLED
```

`CORRECTED_SUCCESS` 是高价值 Evolution signal。

## 11.3 完成后纠正

如果 Episode 已 `COMPLETED`，用户明确纠正同一个答案：

```text
COMPLETED
→ REOPENED
→ RUNNING
→ CORRECTED_SUCCESS
```

不要产生无关联新 Episode。

## 11.4 Semantic Version pinning

Episode 创建时：

```text
baseSemanticVersionId = current active semantic version
```

整个 Episode 默认固定该语义基线，避免 Attempt 1/2 突然使用不同版本而不可解释。

同一个 Episode 的 correction 可以在候选语义状态中完成 corrected attempt；ChangeSet 后台验证并提交 PATCH。Episode 记录至少：

```text
baseSemanticVersionId
acceptedSemanticStateHash
resultSemanticVersionId
```

新 Episode 使用新的 Active Version。

如果 pinned version 被标记 `BAD_RELEASE`，下一 Attempt 可强制 rebase 到当前 Active，并记录 `SEMANTIC_REBASE` 事件。

## 11.5 Idempotency

网络/MCP 重试不能创建多个 Episode。

要求：

```text
requestId
idempotencyKey
requestFingerprint
```

同一 key 返回同一 Episode/Run，避免把网络重试误当成多个独立学习信号。

---

# 12. Query Case 从 Episode 产生

不要每个 Attempt 一条 Query Case。

正确：

```text
Episode
  ↓
accepted/final outcome
  ↓
Query Case
```

Query Case 至少包含：

```text
originalQuestion
finalResolvedIntent
acceptedBlueprint
acceptedSQL
result/evidence summary
wrongAttempts
clarifications
corrections
baseSemanticVersionId
acceptedSemanticStateHash
resultSemanticVersionId
episodeId
acceptedAttemptId
confidence
confirmationLevel
```

Query Case 的作用：

1. Experience Retrieval/reuse。
2. Semantic Evolution promotion evidence。

它本身不是 Semantic Catalog Change。

---

# 13. Grill-Me 与 Runtime Evolution 统一到 Semantic Gap

两种入口：

```text
Grill-Me
= proactive semantic gap discovery

Runtime Episode
= reactive semantic gap discovery
```

都汇入：

```text
SemanticGap
→ SemanticChangeSet
→ Validation / Replay
→ Version
```

不要维护两套完全不同的规则系统；差异只体现在 origin/evidence/confidence。

---

# 14. Retrieval / Index 调整

正常路径继续固定为：

```text
Exact + BM25 + Vector → RRF → Rerank → Semantic Blueprint
```

Chat / Embedding / Rerank 都 REQUIRED。

SemEvoSQL 继续只管理 model connections，不部署/拥有模型服务。

## 14.1 PATCH 禁止每次全量 embedding

ChangeSet 必须输出：

```text
affectedSemanticAssetIds
```

只重建受影响资产的：

```text
retrieval document
lexical tokens
embedding
```

未变化资产从 parent version 逻辑继承或 Copy-on-Write 复用。

优先目标：

```text
Semantic Version snapshot
+
immutable semantic asset revision
+
version membership/reference
```

如果第一阶段现有 schema 无法一步做到，可先复用 unchanged rows、changed rows re-embed，但最终不能长期全量重算。

## 14.2 Index Ready Barrier

新 Semantic Version 激活前必须：

```text
Catalog validation PASS
Replay PASS
Lexical index READY
Vector index READY
Rerank retrieval smoke PASS
Query Preflight/Safety PASS
```

然后才 CAS Active。

旧 Active 在新版本构建期间继续服务。

项目状态拆分：

```text
queryReadiness = READY
knowledgeUpdateStatus = EVOLVING
```

## 14.3 Model 配置不属于 Semantic Version

Chat Provider：不 bump Semantic Version。

Rerank Provider：不 bump Semantic Version。

Embedding Provider：不 bump Semantic Version，但创建新的 Retrieval Index Generation 并重建向量索引。

Attempt 记录：

```text
chatModelConfigRevision
embeddingModelConfigRevision
rerankModelConfigRevision
retrievalIndexGeneration
```

用于诊断/replay，但不要污染 Semantic Version。

---

# 15. MCP 部署模型必须一起调整

## 15.1 原则

目标按当前 stateless MCP core 设计：

- 不依赖 transport/protocol session 承载业务状态。
- 应用状态通过显式 handle，这里就是 `episodeId`。
- clarification 优先适配 `input_required` / multi-round request 能力。
- Tasks 作为可选 extension，不是 SemEvoSQL Durable Execution 的唯一实现。
- Tool schema 稳定，不把动态 Semantic Catalog 烧进 tool schema。

对 capability-limited 旧 client 提供 fallback，不让旧协议约束污染 Domain Model。

## 15.2 MCP Deployment 绑定 Project，不绑定 Version

错误：

```text
MCP Deployment
→ projectVersionId = 18
```

正确：

```text
MCP Deployment
→ projectId
→ Active Semantic Version Resolver
```

Semantic Version：

```text
2.4.17 -> 2.4.18 -> 2.5.0 -> 3.0.0
```

都不需要 MCP redeploy。

只有 endpoint/auth/tool-contract-breaking/deployment config 等改变才需要 redeploy。

## 15.3 新 Episode resolve Active

MCP 新 `query`：

```text
resolve project
→ resolve current active semantic version
→ create Episode pinned to that version
```

后续显式 `episodeId` 继续。

MCP transport 本身不存业务 session。

## 15.4 MCP Tool surface

核心保持很小、稳定。

### query

建议输入：

```text
input
episodeId?
parentEpisodeId?
requestId?
```

行为：

- 无 episodeId：新 Episode。
- 有 episodeId：继续 clarification/correction/reopen。
- 普通新分析目标：新 child Episode。

### query_status

```text
query_status(episodeId)
```

作为 durable polling fallback。

如果 client 支持 Tasks extension，可映射 MCP Task；不支持也必须正常工作。

普通项目问数 MCP **不提供**：

```text
add_metric
update_metric
publish_semantic_version
apply_evolution
```

外部 Agent 只能通过正常 Episode signal 影响 Evolution；正式语义变更由内部治理链执行。

## 15.5 Clarification

支持 multi-round/input_required 的客户端：

```text
tools/call
→ input_required
→ client provides input
→ continue same Episode
```

不支持：

```text
{
  status: INPUT_REQUIRED,
  episodeId: ...,
  clarification: ...
}
```

随后：

```text
query(episodeId, input)
```

内部统一：

```text
Episode.status = INPUT_REQUIRED
```

协议只是 Adapter。

## 15.6 MCP Tasks / Durable Execution

支持 Tasks：

```text
SemEvoSQL durable run
↔ MCP Task handle
```

不支持：

```text
RUNNING + episodeId
→ query_status
```

内部永远持久化：

```text
episodeId
runId
attemptId
checkpoint
events
```

Transport 断开不丢状态。

## 15.7 Structured output

Completed 示例：

```json
{
  "episodeId": "ep_xxx",
  "status": "COMPLETED",
  "semanticVersion": "2.4.18",
  "corpusRevision": 138,
  "answer": "...",
  "sql": "...",
  "evidence": [],
  "result": {}
}
```

Input required：

```json
{
  "episodeId": "ep_xxx",
  "status": "INPUT_REQUIRED",
  "semanticVersion": "2.4.18",
  "clarification": {
    "id": "clar_xxx",
    "question": "...",
    "options": []
  }
}
```

Tool output schema 保持稳定。

---

# 16. Web / REST / MCP 统一 Episode Application Service

禁止三套状态模型：

```text
WebChatSession
RestQuery
McpTask
```

统一：

```text
Web
REST
MCP
 ↓
EpisodeApplicationService
 ↓
Episode Domain
 ↓
Query Runtime
 ↓
Evolution Pipeline
```

Adapter 只做协议映射。

---

# 17. Durable Execution 挂在 Episode 下

关系建议：

```text
Episode
├── Attempt 1
│   └── Run 1
├── Attempt 2
│   └── Run 2
└── Outcome
```

Run 是执行层对象，Episode 是业务学习对象。

浏览器/SSE/MCP 断开：

```text
Run continues
Episode persists
```

重连：

```text
episodeId
→ restore current state
```

历史会话恢复必须恢复完整：Episode timeline、Attempts、Run events、Clarification、Correction、Evidence、Evolution consequence。

---

# 18. 数据库迁移建议

必须先盘点当前真实 schema，再落 migration，禁止按名字猜。

建议新增/重构：

```text
semantic_version
semantic_activation_event
corpus_revision
semantic_change_set
semantic_change_item
semantic_change_set_event
episode
episode_turn
episode_attempt
episode_signal
episode_relation
query_case
semantic_replay_run
semantic_replay_result
```

现有对象逐一映射：

```text
project_version
semantic_evolution_candidate
semantic_evolution_event
query_example
query_case_event
attempt
episode（若已有）
conversation
query_run
```

## 18.1 Legacy Version backfill

现有 Active/PUBLISHED snapshot 保留，填：

```text
cause = LEGACY_MIGRATION
catalogHash
activatedAt
```

旧 Draft：若只是无效工作区，迁移成 ChangeSet history 或显式废弃，不进入新的 Semantic Version Timeline。

## 18.2 Legacy Episode backfill

不要为了历史数据强行猜多轮关系。无法可靠恢复时：

```text
legacy query run -> synthetic LEGACY episode
```

从新模型上线后再保证完整 Episode 结构。

## 18.3 旧 Evolution Candidate

最终迁移成 SemanticChangeSet，避免长期同时维护两个同义领域模型。

---

# 19. 物理数据库品牌前缀

若当前无必须兼容的外部生产 DB，可评估把 tracked schema `qw_*` 迁移成：

```text
sevo_*
```

但这项必须基于真实 schema/migration 风险决定。

如果风险过高，可以暂时保留物理表名作为 legacy storage detail；public docs/API/domain 名称必须全部 SemEvoSQL，并在 completion notes 记录原因。

公共 Compose 默认数据库名建议：

```text
semevosql
```

本机已有 volume 不强制改数据库名。

---

# 20. API 建议

新增/统一领域 API（具体 URI 以当前 Controller 结构为准，不为了形式硬拆）：

```text
GET  /api/semevosql/projects/{id}/semantic-versions
GET  /api/semevosql/projects/{id}/semantic-versions/{version}
POST /api/semevosql/projects/{id}/semantic-versions/promote-major
POST /api/semevosql/projects/{id}/semantic-versions/{version}/activate
POST /api/semevosql/projects/{id}/semantic-versions/{version}/rollback

GET  /api/semevosql/projects/{id}/corpus-revisions

GET  /api/semevosql/projects/{id}/semantic-changes
GET  /api/semevosql/projects/{id}/semantic-changes/{changeSetId}

GET  /api/semevosql/projects/{id}/episodes
GET  /api/semevosql/projects/{id}/episodes/{episodeId}
POST /api/semevosql/projects/{id}/episodes/{episodeId}/continue
```

所有 Query Result / Diagnosis / Replay 至少携带：

```text
semanticVersion
corpusRevision
episodeId
attemptId
```

---

# 21. 前端产品调整

## 21.1 项目首页

显示：

```text
Semantic Version  2.5.13
Corpus Revision   142
Evolution         Healthy
Query Readiness   Ready
Knowledge Update  Idle / Evolving
```

不要只展示内部 `Version ID`。

## 21.2 Semantic Version Timeline

例：

```text
3.0.0   MAJOR
Human-promoted business baseline

2.7.0   MINOR
2026 Sales Policy caused 7 semantic changes

2.6.18  PATCH
Episode #9381 corrected effective payment semantics

2.6.17  PATCH
Added alias "实付金额"
```

PATCH 多时 UI 可折叠为 patch train，但数据库真实快照不能因为 UI 折叠而删除。

## 21.3 Episode Diagnosis

一页串完整：

```text
Episode
├── Original intent
├── Base semantic version
├── Attempts
├── Retrieval evidence
├── Blueprint
├── Preflight
├── SQL/result
├── Clarification
├── Correction
├── Final outcome
├── Root-cause attribution
└── Evolution consequence
    ├── ChangeSet
    ├── Replay
    └── 2.6.17 -> 2.6.18
```

让用户真正看到系统如何“学会”。

## 21.4 ChangeSet 页面

显示：

```text
Origin
Risk
Confidence
Before / After
Evidence
Affected Cases
Replay Result
Status
Result Version
```

BLOCKED 时明确说明为什么没自动学、缺什么证据、冲突在哪。

## 21.5 Major Promotion

唯一显式 MAJOR 操作：

```text
Promote Business Baseline
```

确认页展示：

```text
Current 2.8.41
New     3.0.0
Catalog Hash
Corpus Revision
Pending ChangeSets
Recent Replay Health
```

---

# 22. Project Readiness / Knowledge Update

Semantic Evolution 构建中不能让整个项目不可问。

拆开：

```text
queryReadiness
knowledgeUpdateStatus
```

例如：

```text
queryReadiness = READY
knowledgeUpdateStatus = EVOLVING
```

旧 Active 继续服务，新版本 ready 后原子切换。

---

# 23. 事件与审计

建议事件：

```text
EPISODE_CREATED
EPISODE_REOPENED
ATTEMPT_STARTED
ATTEMPT_COMPLETED
CLARIFICATION_REQUESTED
CORRECTION_RECEIVED
OUTCOME_CONFIRMED

SEMANTIC_GAP_ATTRIBUTED
CHANGESET_PROPOSED
CHANGESET_REBASED
REPLAY_STARTED
REPLAY_PASSED
REPLAY_FAILED
SEMANTIC_VERSION_CREATED
SEMANTIC_VERSION_ACTIVATED
SEMANTIC_VERSION_ROLLED_BACK

CORPUS_REVISION_CREATED
CORPUS_SEMANTIC_DIFF_DETECTED
MAJOR_BASELINE_PROMOTED
```

用于 Diagnosis、Durable Resume、Replay provenance、Audit、产品 Timeline。

不为了事件引入 Kafka；优先沿用当前数据库持久化/outbox/worker 模式。

---

# 24. 缓存一致性

所有缓存必须以：

```text
projectId + semanticVersionId
```

或明确 generation key 隔离。

涉及：

```text
semantic catalog cache
retrieval cache
query-case retrieval cache
MCP active resolver cache
project health/readiness cache
```

禁止只按 projectId 缓存 Catalog 然后依赖手工 clear。

Active 切换后：

- 新 Episode 用新版本。
- 已有 Episode 保持 pinned 版本。
- rollback bad release 时按规则 rebase。

---

# 25. 明确不做

本轮不做：

- 不照搬 EvoSQL 的 `16 SQL x 3 rounds` candidate search。
- 不加 LLM Critic 多候选循环作为核心链路。
- 不做 SDPO/RL/模型训练。
- 不拥有模型部署。
- 不引入 Live Semantic Overlay。
- 不新增 basic/enhanced retrieval 模式。
- 不让 Rerank 变 optional。
- 不删除 RRF。
- 不恢复 Typed Semantic Plan / Semantic Query Plan / Governed SQL / Dry Plan。
- 不为了自动版本事件引入 Kafka/Kubernetes 等外围平台。
- 不给普通 MCP Client 暴露直接修改 Semantic Catalog 的 tools。
- 不让 MCP transport session 承载业务状态。
- 不把失败 SQL 直接当 Semantic Evolution 信号。

---

# 26. 与现有 Query Runtime 的兼容原则

必须继续保留：

```text
Natural Language
→ Exact + BM25 + Vector
→ RRF
→ Rerank
→ Semantic Blueprint
→ Compiler
→ Query Preflight
→ Verified SQL
→ Read-only execution
→ Evidence / Diagnosis / Learning
```

本轮改变的是：

```text
Experience -> Knowledge
```

不是推翻 query execution architecture。

---

# 27. 实施阶段

## Phase 0：真实状态盘点

新 Session 第一件事：

1. 打开真实 workspace，确认 branch/git status/staged。
2. 保留全部用户修改。
3. 搜索并画出现有：project version、active/working version、semantic evolution candidate/event、replay、episode/attempt/query run/conversation、MCP deployment/version binding、semantic embeddings/index、project health/readiness。
4. 读取当前 product guide/MCP 方案/相关 roadmap。
5. 盘点数据库 schema。
6. 把本方案映射到真实类/表后再改代码。

## Phase 1：Domain + Database Migration

实现：

```text
SemanticVersion
CorpusRevision
SemanticChangeSet
SemanticChangeItem
SemanticActivationEvent
Episode
EpisodeTurn
EpisodeAttempt
EpisodeSignal
EpisodeRelation
```

完成 legacy backfill，保持现有查询可运行，再逐步去掉旧 duplicate model。

## Phase 2：Semantic Version Engine

实现：

- PATCH/MINOR/MAJOR bump policy
- auto activate
- manual major promote
- activation event
- rollback
- optimistic concurrency/CAS
- rebase
- immutable snapshot
- no-diff no-version

## Phase 3：Semantic Evolution Pipeline

实现：

```text
Episode / Corpus
→ Signal
→ Root Cause Attribution
→ Semantic Gap
→ SemanticChangeSet
→ Risk Assessment
→ Impact Analysis
→ Replay
→ Index Build
→ Version Commit
→ Activate
```

补齐 promotion policy、risk-aware replay、independent evidence rule、blocked/rejected lifecycle、replay provenance、auto PATCH/MINOR。

## Phase 4：Episode Runtime

统一 Conversation/Episode/Turn/Attempt/Run：

- Episode boundary
- clarification continuation
- correction reopen
- parent/child Episode
- version pinning
- idempotency
- durable resume
- accepted outcome
- Query Case generation

如果历史 reopen 仍有 Run/Attempt/Evidence 恢复不完整，一并修复。

## Phase 5：Retrieval Index Versioning

实现：

- affected semantic asset calculation
- incremental embedding
- lexical/vector readiness generation
- copy-on-write/reference reuse
- active switch barrier
- model config revision recording
- query readiness independent from knowledge update

真实跑一次 PATCH 和 MINOR，确认无需全量 rebuild。

## Phase 6：MCP

实现：

- project-scoped deployment
- no fixed semantic version
- stateless transport
- explicit episode handle
- multi-round clarification adapter
- optional Tasks adapter
- query_status fallback
- structured output
- idempotency
- Episode version pinning
- Active auto-follow for new Episode
- no redeploy on PATCH/MINOR/MAJOR

真实使用支持新版能力的 client + capability-limited fallback path 各测一次。

## Phase 7：Web/API/Diagnosis

完成：Semantic Version Timeline、Corpus Revision、ChangeSet、Episode Diagnosis、Evolution result、Major Promote、Rollback、Query Readiness vs Knowledge Update、Version labels。

必须浏览器真实验收。

## Phase 8：品牌 Rename

功能稳定后再集中 rename：

- package
- Maven
- env
- Docker
- API primary namespace
- UI
- README
- docs
- scripts
- CI
- MCP metadata
- schema prefix（若最终决定迁移）

全仓旧品牌扫描。

不要改 Git 历史，除非用户另行明确要求。

## Phase 9：测试 / 验收 / 分批 Commit

建议 commit 批次：

```text
refactor(domain): introduce semantic versioning and episode model
feat(evolution): add changeset-driven automatic semantic evolution
feat(runtime): unify episodes attempts and durable query execution
refactor(retrieval): version semantic indexes and incremental rebuilds
feat(mcp): follow active semantics with stateless episode handles
feat(web): expose semantic evolution and episode diagnosis
refactor(brand): consolidate public brand as SemEvoSQL
test(release): close SemEvoSQL evolution acceptance
```

按真实 diff 合并/拆分，但每个 commit 必须可编译、可运行。

未经明确要求不要 push。

---

# 28. 测试矩阵

## 28.1 Version rules

1. Query success, no semantic diff -> version unchanged。
2. New Query Case, no semantic diff -> unchanged。
3. Alias evolution -> PATCH + 1。
4. Metric evolution -> PATCH + 1。
5. Multiple items in one ChangeSet -> one PATCH。
6. Upload doc no semantic diff -> only Corpus Revision。
7. Upload doc with semantic diff -> MINOR + 1, PATCH = 0。
8. Manual semantic fix -> PATCH。
9. Manual Promote -> MAJOR + 1, MINOR/PATCH = 0。
10. Rollback -> active pointer changes, no new version。
11. Failed Replay -> no version。
12. Stale ChangeSet -> rebase, no lost update。

## 28.2 Episode rules

1. clarification same Episode。
2. correction same/reopened Episode。
3. retry same Episode。
4. same requestId network retry -> same Episode。
5. completed query then drill-down -> child Episode。
6. explanation follow-up -> child Episode。
7. conversation can contain multiple Episodes。
8. Attempt failure != Episode failure。
9. accepted outcome produces one Query Case。
10. corrected Episode keeps before/after Attempts。

## 28.3 Evolution safety

1. DB timeout cannot produce ChangeSet。
2. SQL syntax error cannot directly produce ChangeSet。
3. weak single signal cannot overwrite high-confidence Metric。
4. explicit correction + evidence can propose change。
5. high-risk change requires broader replay。
6. causative case cannot be only proof。
7. conflict blocks change。
8. old active remains queryable during validation。
9. index not ready -> cannot activate。
10. rollback bad version forces rebase where required。

## 28.4 MCP

1. Deployment stores project, not fixed version。
2. PATCH after deployment -> new MCP Episode auto uses it。
3. MINOR after deployment -> no redeploy。
4. MAJOR after deployment -> no redeploy。
5. existing running Episode stays pinned。
6. new Episode gets current Active。
7. multi-round capable client gets input-required flow。
8. limited client gets `INPUT_REQUIRED + episodeId` fallback。
9. Tasks capable client maps durable run。
10. no Tasks support -> query_status works。
11. same requestId does not duplicate Episode。
12. disconnect/reconnect preserves durable state。
13. MCP cannot directly mutate Semantic Catalog。

## 28.5 Retrieval

1. Alias PATCH only rebuilds affected asset。
2. Metric PATCH rebuilds dependency closure only。
3. old active remains queryable during build。
4. version activates only after index ready。
5. Embedding provider change rebuilds vector index, no semantic bump。
6. Rerank/Chat config change no semantic bump。
7. normal path remains Exact + BM25 + Vector -> RRF -> Rerank。

---

# 29. 真实端到端验收场景

## Scenario A：PATCH

初始：

```text
Semantic 1.0.0
```

用户：

```text
上个月实付金额是多少？
```

系统 semantic grounding 错。用户明确纠正：

```text
实付金额指支付成功的钱。
```

期望：

```text
same Episode
→ corrected success
→ Semantic Gap
→ ChangeSet
→ targeted replay
→ 1.0.1 auto active
```

新 Episode 再问同义问题直接正确；MCP 无需 redeploy。

## Scenario B：MINOR

上传新版业务制度：

```text
Corpus r7 -> r8
```

检测到 Metric 定义变化：

```text
Semantic Diff
→ ChangeSet
→ broad replay
→ 1.1.0 auto active
```

再上传无语义变化普通材料：

```text
Corpus r8 -> r9
Semantic stays 1.1.0
```

## Scenario C：MAJOR

管理员：

```text
Promote Business Baseline
```

期望：

```text
1.4.23 -> 2.0.0
```

无需 Catalog Hash 变化；MCP 新 Episode 自动使用 2.0.0。

---

# 30. 发布前检查

必须确认：

- no SemEvoSQL public branding remains，除兼容 migration/deprecation 说明。
- Java package 以 `cn.lgs.` 开头。
- 无用户本机路径/私钥/API key。
- model boundary 仍是 connection-only。
- Chat / Embedding / Rerank required。
- RRF + Rerank 都存在。
- MCP 不 version-pinned。
- Semantic Version 与 Platform Version 完全区分。
- README 不重新膨胀成内部设计文档，只更新品牌和核心定位。
- Product guide / architecture docs 详细记录本方案。
- browser acceptance PASS。
- backend tests PASS。
- frontend verify PASS。
- MCP real smoke PASS。
- migration from current DB PASS。
- fresh clone / fresh DB bootstrap PASS。

---

# 31. 最终验收标准

本轮只有同时满足以下条件才算完成：

1. 运行时纠错可自动产生 PATCH，并在自动验证后对后续 Episode 立即生效。
2. 新资料只在造成 Semantic Diff 时自动产生 MINOR。
3. MAJOR 只能人工 Promote。
4. Semantic Version 不再承担 Draft Workspace 职责。
5. 一次完整问数/纠错链正确建模成 Episode。
6. Clarification/Correction/Retry 不错误地产生新 Episode。
7. MCP 部署不绑定固定 Semantic Version。
8. MCP semantic update 不需要 redeploy。
9. MCP 以显式 episode handle 承载业务状态。
10. 正在执行的 Episode 不因后台自动升级发生无记录版本漂移。
11. PATCH 不做不必要的全量 embedding。
12. Active 切换前 Catalog/Replay/Index 全 ready。
13. 并发 ChangeSet 不丢更新。
14. Rollback 不制造伪 Semantic Version。
15. Web 可以看到 Episode -> ChangeSet -> Replay -> Version 的完整学习链。
16. 项目完成 SemEvoSQL 品牌迁移（除明确保留 compatibility layer）。
17. 现有问数主链路、MCP、Durable Execution、Semantic Retrieval 全部回归通过。
18. 工作树最终清楚说明并分批 commit；未经要求不 push。

---

# 32. 新 Session 强制原则

新 Session 不重新讨论产品方向，直接以本方案为权威 roadmap，但先用当前真实代码/数据库校正实现细节。

必须遵守：

- 不写死针对某一道测试题的语义规则。
- 不通过 hardcode phrase 修 Episode 边界。
- 不删除用户已有修改。
- 不使用破坏性 Git 操作。
- 不因为 rename 重写历史。
- 不把功能性问题掩盖成“模型不行”。
- 不只改前端文案；后端领域模型、数据库、MCP、runtime 必须一致。
- 不做 fake smoke；关键链路要用真实模型/真实 DB/真实 MCP 跑一次。
- 若现有实现与本方案冲突，优先保持：**Episode 是经验单位、ChangeSet 是变更单位、Semantic Version 是稳定快照、PATCH/MINOR 自动、MAJOR 人工。**

---

# 33. 最终架构图

```text
                         Conversation
                              │
                              ▼
                           Episode
                              │
                 ┌────────────┼────────────┐
                 │            │            │
               Turn        Attempt       Signal
                              │            │
                              │       Clarification
                              │       Correction
                              │       Confirmation
                              │
                              ▼
                        Episode Outcome
                              │
                              ▼
                          Query Case
                              │
                     Root Cause Attribution
                              │
                  ┌───────────┴───────────┐
                  │                       │
           Runtime/Model Issue       Semantic Gap
                  │                       │
             Diagnosis                    ▼
                                   SemanticChangeSet
                                          │
                                   Risk + Impact
                                          │
                                       Replay
                                          │
                                    Index Build
                                          │
                                         PASS
                                          │
                    ┌─────────────────────┼─────────────────────┐
                    │                     │                     │
                 EPISODE                CORPUS                HUMAN
                    │                     │                     │
                  PATCH                 MINOR                 MAJOR
                    │                     │                     │
                    └─────────────────────┼─────────────────────┘
                                          ▼
                                  Semantic Version
                                          │
                                  Atomic Activation
                                          │
                                          ▼
                         Active Semantic Version Resolver
                              │                       │
                              │                       │
                           Web/REST                   MCP
                              │                 stateless call
                              │                  + episodeId
                              └──────────────┬────────┘
                                             ▼
                                   Semantic Retrieval
                                             │
                           Exact + BM25 + Vector → RRF → Rerank
                                             │
                                             ▼
                                   Semantic Blueprint
                                             │
                                             ▼
                             Compiler + Query Preflight
                                             │
                                             ▼
                                      Verified SQL
                                             │
                                             ▼
                                    Read-only Execute
```

---

**本方案是下一 Session 的权威实施 roadmap。**
