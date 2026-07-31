# B.10 请求与 Todo 历史案例：本地验收

本批已落地两级召回、冻结详情、恢复复核和 SQL 修复复用。不是全部 B.10、B.7/B.9 或全项目验收完成报告。

## 实现与文件

- `V39__query_case_recall_snapshots.sql`：不可变召回快照；案例内容修订纳入 SQL、SQL hash、意图和来源。用量计数不改变修订。
- `QueryCaseHistoryService`、`QueryCaseRecallSnapshotRepository`：请求最多 1 条、每个 Todo 最多 2 条，按案例及版本去重最多 3 条；不为凑数补检索。恢复核验当前权限、依赖、完整请求资格及内容修订。旧案例最新 SQL 不会替换已保存详情。
- `RequestHistoryRecallNode`、`SemEvoSQLConfiguration`、`RequestAnalysisNode`、`QueryDecompositionService`：增强之后、分析之前根召回；历史结构仅作为本轮分析参考。
- `SemanticBlueprintPipeline`、`SemanticBlueprintNode`、`SemanticBlueprintGenerationService`：Todo 案例召回与目录检索并行，在组装候选之前合并历史；正式模型引用最多补入 4 个。此项不代表旧目录 24 模型窗口已经完成 B.9 改造。
- `SqlGenerateNode`：生成和修 SQL 只读取已有快照；缺少快照时不新增案例检索。
- `QueryCaseRequestEvidence`：保留本请求使用的历史快照引用，完整请求/任务/尝试/SQL/产物引用继续保存。
- `QueryCaseHistoryController`：详情 GET 从已保存快照读取，并重新校验调用者范围及案例有效性。
- `QueryEnhanceNode`：当前消息内部指代优先，不能直接把“这个月”套成系统当前月份；不明确则正常澄清。
- `scripts/inspect-native-recovery.py` 增加快照导出；`scripts/verify-case-history.py` 校验实际记录的阶段顺序、上限、来源和重启一致性。

默认历史上下文预算 8192，以 UTF-8 字节作为保守 token 上界。超限保持完整详情引用和未加载标记，不留下失去错误标签的失败 SQL。当前已提供详情 API；没有声称模型自动调用详情工具已通过验收。

## 实际浏览器、数据库与重启

- 页面：`http://127.0.0.1:3303/semevosql/chat?projectId=2`。
- Run：`af37d5e8-b14c-4e87-ade1-884cf56481dc`，会话：`286f87d3-71d4-4f62-a512-6725b72a426c`。
- 输入：请分别展示两份结果：第一份是2026年1月已支付订单的支付金额总计，第二份是这个月已支付订单每天的支付金额汇总。两份都按支付时间筛选，不扣退款，不合成一个表。
- 浏览器选择“指2026年1月”完成初始澄清；首个 Todo 计划待审批时重启 `semevosql-acceptance-backend-1`；重新加载页面，依次批准两个正确的一月计划。
- 结果：SUCCEEDED，两个 Todo DONE，两次源查询 COMPLETED，无重复执行键；总额 270，1 月 10/11/12 日分别 150/80/40，与独立业务库 SQL 对照一致。
- 原始整条请求命中上轮双 Todo 案例 `8849e994-3bb0-451a-ba90-a134d2f8c427`。请求快照 `444bcee0-fb84-4f10-b34b-1b6cf7c2e40e`；task-1 快照 `dd152706-f205-4b10-98c0-d6ab7e145359`；task-2 快照 `b8838c1b-9803-4237-b121-eb7ada3115ff`。根快照 1 条，每个 Todo 快照 2 条；根在分析前消费，两次规划均保留同一根引用。
- 重启前后已有快照内容和 hash 完全一致，没有重复根召回或重复完成事件；23 个真实图检查点。
- 详情 HTTP 返回 29,798 字节，内容与冻结详情完全相等；不是仅凭 HTTP 200 判通过。
- 未提交正反馈，完整请求通过系统质量条件后自动生成一个 APPROVED 案例 `f80d7e0f-3ce7-4083-9a4f-4af5f92e30bc`。一次首次澄清没有被当成推翻已确认需求。
- 两个 Todo 期限共 600 秒，实际执行耗时 225.254 秒，人工等待单独暂停计时。不能据此宣称本 Run 已超过旧 300 秒执行上限；旧计时器的动态续期另由回归测试覆盖。

这轮真实模型验收部署 JAR SHA-256：`6aa4737b200a8dc770cc8800f7d18fa04e0cc9f3320e867c26410b9a9fe74435`。后续审计事件补写修复的测试/部署身份单独保存，不能倒签为此 Run 使用的版本。

## 可重跑验证

以下命令在仓库根目录执行，`E` 指本轮证据目录；读取脚本不改业务状态。输出文件必须使用新名称，避免覆盖早先证据。

```sh
export E='$LOCAL_EVIDENCE_ROOT/work/semevosql-20260927'
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home ./mvnw -o -pl backend -am \
  -Dtest=QueryCaseHistoryPostgresIT,QueryCaseDualQuestionPostgresIT,QueryCaseRequestEvidencePostgresIT,RequestEnhanceGraphTest,SemanticBlueprintPipelineTest,SemanticBlueprintGenerationServiceTest,RunTaskDeadlinePostgresIT,RunExecutionFenceServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false package
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home ./mvnw -o -pl backend -am package
python3 scripts/inspect-native-recovery.py --run-id af37d5e8-b14c-4e87-ade1-884cf56481dc --output "$E/manual-history.json"
python3 scripts/verify-case-history.py --evidence "$E/manual-history.json" --before-restart "$E/root-todo-before-restart.json" --expected-tasks 2 --output "$E/manual-history-check.json"
python3 scripts/verify-case-admission.py --evidence "$E/manual-history.json" --expected-tasks 2 --expected-clarifications 1 --expected-amount 270 --expected-amount 150 --expected-amount 80 --expected-amount 40 --output "$E/manual-history-admission.json"
python3 scripts/verify-task-deadline.py --evidence "$E/manual-history.json" --expected-tasks 2 --output "$E/manual-history-deadline.json"
docker exec -i semevosql-acceptance-metadata-db-1 psql -X -v ON_ERROR_STOP=1 -U acceptance -d semevosql_acceptance_business < "$E/task-deadline-business-oracle.sql"
```

不要将元数据库检查 SQL 投入业务库。`root-todo-completion-1.sql` 读元数据库；`task-deadline-business-oracle.sql` 读业务库。初始化与启动继续使用仓库 `scripts/local-acceptance.py`、`scripts/seed-local-business.py` 及之前的隔离环境文档，不需要重新导入或清库。

## 通过、失败、未测与受阻

**通过：** `root-todo-tests-7.log` 106 项通过，0 失败/错误/跳过，其中 68 项使用真实 PostgreSQL（快照 14、双问题 19、完整请求准入 25、期限 10）。覆盖并发只发布一份快照、SQL 改变不得偷偷替换、撤权/停用立即失效、SQL 修复不重检、预算超限保留引用、丢失审计事件补写等。独立测试容器中的合成成功状态只用于机制测试，不作为真实模型成功证据。

**通过：** `root-todo-full-backend-2.log` 全量默认测试 322 项通过；实际业务 Run 对应这一版本。浏览器 Run 的快照检查 16 项、准入检查 16 项、期限检查 9 项全部通过。`root-todo-detail-http.json` 保存了详情内容比较结果。索引在浏览器运行前为 11 个 APPROVED 案例、22 份当前文本和 22 个 1024 维向量，无索引重试积压。

**已修复的失败：** 上一版本同消息“这个月”被解释为九月，原 Run 已从页面取消，没有批准错误的第二个计划；保留 `task-deadline-wrong-month-*` 原始失败证据。新版本本轮通过初始澄清正确绑定一月。初次编译的未使用 import 错误也已修复，早期失败日志保留。

**未测/未完成：** 模型主动按需加载被截出的详情；真实模型下案例補足遗漏业务模型的专项；“推翻已确认需求”与“实现没有遵循原需求”的完整强制确认链；B.7/B.9 普通模型检索及候选窗口；大规模质量评测。这些不记作本批通过。

**受阻：** 本轮无模型凭据或依赖阻塞。没有用 Mock 代替这次真实浏览器执行。

证据目录还包括 `root-todo-deployment-1.json`、`root-todo-restart.json`、重启前后 JSON/SQL、最终 JSON/SQL、各 assessment JSON；截图 `root-todo-all-amounts-browser.png` 同屏显示 270/150/80/40。截图仅包含本地合成业务数据，无模型凭据。
