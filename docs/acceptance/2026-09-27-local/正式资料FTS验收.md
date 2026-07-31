# B.7 正式资料 PostgreSQL 词法检索

本批把查询时全量 `findCatalog` → Java EXACT/BM25 评分替换为数据库 FTS；案例检索仍使用自己的双问题索引。当前正式资料仍为原有资产投影，B.9 的一模型一完整文本、12/6 候选窗口和共享定义绑定不是本批已经完成的内容。

## 代码与运行行为

- `V40__semantic_document_fts.sql` 增加统一的 `qw_semantic_tokenize_v1`、版本标记、派生 tsvector 和 GIN；入库、迁移既有文档、查询使用相同 NFKC、小写、中文单字/双字规则，`simple` 消费已分词内容。文本修改同步重算向量，不等待异步词法索引。
- `SemanticRetrievalDocumentRepository.lexicalScores`：参数化 OR 词项、`@@`、`ts_rank`、稳定 ID 排名与 LIMIT；项目、版本、Catalog hash、数据源、模型、文档类型、资产过滤位于 LIMIT 前。排名只返回 ID 与分数。
- `findByIds` 对两路命中 ID 的并集加载文本，再核一次同样范围；不读取未命中全库文档。每路返回额度由 `semevosql.retrieval.channel-top-k` 配置，当前默认 48、上限 500；它还是资产阶段的过渡额度，不冒充已完成模型级额度测量。
- `SemanticHybridRetrievalService` 只有 FTS、VECTOR 两路做 RRF(k=60)；没有隐藏的 EXACT/BM25 第三路。旧枚举名字兼容映射到 FTS，实际证据标为 FTS。明确的业务绑定流程继续独立存在。
- `SemanticCatalogApplicationService` 对无字母/数字的输入直接返回空召回，不触发目录兜底全读。
- 词法读取失败且没有可用向量命中时抛出读取故障，不能当成“定义不存在”；单路有命中时可以降级继续。
- 从本地实际 EXPLAIN 发现查询词聚合在小表扫描中重复运行，已将查询输入 CTE 显式物化。5001 文档独立测试中聚合只执行一次。

## 自动化结果

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home ./mvnw -o -pl backend -am \
  -Dtest=SemanticLexicalPostgresIT,SemanticHybridRetrievalServiceTest,SemanticCatalogApplicationServiceTest,SemanticBlueprintPipelineTest \
  -Dsurefire.failIfNoSpecifiedTests=false package
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home ./mvnw -o -pl backend -am package
```

- `catalog-fts-tests-3.log`：35 项通过，0 失败/错误/跳过，其中 16 项真实 PostgreSQL。覆盖中文、英文标识、混合文本、全角归一化、补充区汉字、无词项、OR 召回、排序稳定、权限范围、更新即时生效、通道故障与只加载命中文本。
- `catalog-fts-full-backend-1.log`：327 项默认回归通过；`catalog-fts-full-backend-2.log` 是物化输入优化后的同一套回归。测试集合有重叠，不相加。
- 最初 `catalog-fts-tests-1.log` 有一个测试数据外键错误：测试版本语义号重复导致目标版本未创建；修复测试夹具后全数通过。没有放松外键或删除失败日志。
- 独立 PostgreSQL 测试保存了 5000 条无关资料加 1 条命中资料的真实 EXPLAIN：实际走 `idx_qw_semantic_document_fts` 的 Bitmap Index Scan，读取并返回 1 条；本机该次数据库执行 0.399 ms。只证明这个隔离样本，不推断生产性能或恒定扫描复杂度。

## 部署、页面与交叉验证

页面 `http://127.0.0.1:3303/semevosql/chat?projectId=2`；保留隔离元数据库及业务库，不清空业务数据。

第一轮真实 Run `8c4cb96c-eb2e-4360-821b-22d8567b5b95`：通过 computer use 新建会话，输入“请查2026年1月已支付订单的支付金额总计，按支付时间筛选，不扣退款”，核对正确口径后批准。返回 270，源执行完成，13 个原生检查点，无正反馈仍正常准入，16 项数据/案例检查通过。单任务只保存 1 份请求级案例快照，分析和规划消费同一快照。

该次部署 JAR 为 `2894da8039c7fd674d387579525e931c83c0a302f6af6cb5ca2025d82b92cf62`。实际规划记录有 FTS/RRF/RERANK，无 EXACT/BM25。交叉检查发现旧正式目录索引配置指纹不匹配，所以**本轮只算 FTS+重排降级路径通过，不算向量通道通过**。

随后通过应用 `/api/semevosql/operations/semantic-index/reindex` 重建隔离库中仅有的 7 份正式资料。保存重建前的注册信息和 7 份向量备份；模型保持已有本地 `Qwen/Qwen3-VL-Embedding-2B`，输出 1024 维，没有换模型。接口实际重建 7 条，随后 readiness 为 INDEX_READY、7 documents/7 vectors，数据库身份一致。重建耗时 19.907 秒；这不是交互检索耗时。

重建后第二轮真实 Run `0eb51244-7a91-4c03-9243-36f26608a2c3` 再次通过浏览器输入、审批和实际执行返回 270；规划记录同时存在 **FTS、VECTOR、RRF、RERANK**。`verify-catalog-retrieval.py --require-vector` 的 10 项检查通过，业务/准入 16 项检查通过。使用物化输入优化后的 JAR `f8b9facce9ecbbfa164f801a4fd5132981f55258e6dca945728b765a43f1aec2`，对应 `catalog-fts-full-backend-2.log` 的 327 项默认回归及 `catalog-fts-deployment-2.json`。证据 `catalog-hybrid-browser-final.json/.sql`、`catalog-hybrid-channels-assessment.json`、`catalog-hybrid-admission-assessment.json`、`catalog-hybrid-result-browser.png`。

## 可重跑的 SQL 与证据

```sh
export E='$LOCAL_EVIDENCE_ROOT/work/semevosql-20260927'
python3 scripts/inspect-catalog-fts.py --project-id 2 --version-id 2 --output "$E/新的FTS检查.json"
python3 scripts/inspect-catalog-fts.py --project-id 2 --version-id 2 --limit 2 --output "$E/新的FTS窄窗口.json"
python3 scripts/inspect-native-recovery.py --run-id 8c4cb96c-eb2e-4360-821b-22d8567b5b95 --output "$E/新的FTS运行证据.json"
python3 scripts/verify-case-admission.py --evidence "$E/新的FTS运行证据.json" --expected-amount 270 --output "$E/新的FTS运行检查.json"
python3 scripts/verify-catalog-retrieval.py --evidence "$E/catalog-hybrid-browser-final.json" --require-vector --output "$E/新的混合通道检查.json"
```

检查脚本输出同名 `.sql`，只读元数据库；金额真值来自 `task-deadline-business-oracle.sql`，它读取业务库。

`catalog-fts-plan-1.json/.sql` 保存实际执行 SQL、EXPLAIN 和读取量：隔离库 7 份资料、2089 字节，默认窗口 7 条均命中；`catalog-fts-limit2.json/.sql` 的人工窄窗口返回 2 条/195 字节评分数据，仅加载 788 字节文本。没有把人工设置的 LIMIT 2 写成实际主流程默认值。小表实际使用 Seq Scan，不能声称 GIN 总会被采用。

其余证据：`catalog-fts-scale-5001-plan.json`、`catalog-fts-browser-final-1.json/.sql`、`catalog-fts-admission-1.json`、`catalog-fts-result-browser.png`、`catalog-fts-real-reindex-1.json`、`catalog-fts-readiness-after-reindex.json`、各部署身份 JSON。

## 剩余边界

- 已通过：FTS 与实际浏览器降级查询、重建后的真实混合通道查询、只读 SQL 交叉检查、单任务复用一份根历史、索引重建及真实数据库规模样本。
- 待补：模型调用 5 秒预算与故障恢复完整矩阵。
- 未完成：B.8 规划候选按需目录加载；B.9/B.11 全模型资料、定义绑定、模型级候选窗口；完整 B.10 需求纠正确认链和大规模模型质量评测。
- 无凭据/依赖阻塞；不将未测项目算作通过。
