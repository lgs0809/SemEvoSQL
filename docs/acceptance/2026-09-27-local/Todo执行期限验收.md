# B.9 / B.10：按必需 Todo 分配执行期限

用户于 2026-09-27 明确同意按 Todo 数设不同期限。实现为默认每个必需答案目标 300 秒：单任务 300、两个 Todo 600、三个 900；沿用最多 12 个任务的已有拆分边界。创建 Run 时冻结单位，首次持久化拆分后只追加额外目标的预算，不重置已经消耗的时间。重试、恢复、重复拆分不再次加时。人工确认等待继续单独计时，最长 24 小时；单次模型调用与 SQL 限时保持原约束。

显式调用方期限及迁移前的 Run 保留原契约。新迁移只加列，不清库、不改旧失败状态。

## 改动文件

- `V38__request_task_deadline_budget.sql`：冻结单位和任务数。
- `run/QueryRunRepository.java`、`run/QueryRunService.java`：事务与活动 Attempt 校验、一次性分配、幂等事件。
- `workflow/node/RequestAnalysisNode.java`：分析完成后按必需 Todo 数分配预算，并更新 Graph 状态。
- `run/RunExecutionFenceService.java`、`service/graph/GraphServiceImpl.java`：节点、同步调用及 SSE 期限检查使用权威持久化期限，避免旧检查点的 300 秒计时器误杀已分配 600 秒的请求。
- `RunTaskDeadlinePostgresIT.java`、`RunExecutionFenceServiceTest.java`、`GraphServiceImplFailureClassificationTest.java`：数据库并发、恢复、人等候和流式超时边界。
- `scripts/inspect-native-recovery.py`、`scripts/verify-task-deadline.py`：只读查询与可复跑核对。
- `application.yml`：解释配置现为每个必需目标的预算单位。

以下 E 为 `$LOCAL_EVIDENCE_ROOT/work/semevosql-20260927`。

## 自动化与部署

- `task-deadline-tests-1.log`：46 项通过，0 失败／错误／跳过；其中 10 项真实 PostgreSQL。覆盖 1／2／3／12 任务、四路并发重复分配、修改任务数拒绝、过期及旧 Attempt、重启后配置变化、旧期限与显式期限、人等待暂停恢复。
- `task-deadline-full-backend-1.log`：320 项默认回归通过，0 失败／错误／跳过。与定向测试有重叠，不相加。
- `task-deadline-deployment-1.json`：健康 UP，运行 JAR 与测试 JAR 均为 `799b4d8cddbbe72068493a540469179b30e88f47babdc1e533d45ef352c8fe11`；63 个前端文件与已验证产物一致。前端本批未改。

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home ./mvnw -o -pl backend -am \
  -Dtest=RunTaskDeadlinePostgresIT,RunExecutionFenceServiceTest,QueryRunServiceTest,GraphServiceImplFailureClassificationTest,RequestEnhanceGraphTest \
  -Dsurefire.failIfNoSpecifiedTests=false package
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home ./mvnw -o -pl backend -am package
# 为上述 package 保留新日志，然后部署对应产物；不要拿旧日志替代新构建：
python3 scripts/local-acceptance.py up --backend-test-log "$E/新的后端验证.log" \
  --web-test-log "$E/todo-state-web-1.log" --output "$E/新的部署证据.json"
```

## 真实页面、SQL 与案例交叉核对

访问 `http://127.0.0.1:3303/semevosql/chat?projectId=2`，后端 `http://127.0.0.1:18093`。模型保持 Luna／Terra；业务数据为本地合成数据，种子及业务表 SQL 仍在 `deploy/acceptance/sql/business-seed.sql`。

成功 Run：`7c59c9c0-56ea-4c62-871f-7fb13c2e9929`，会话 `67314da0-f31d-4c04-9e23-57fce71be976`。自然语言请求分别展示 2026 年 1 月已支付订单的总额和每日金额，两份都按支付时间、不扣退款。通过 computer use 创建会话、输入、核对并批准两次计划，最终返回 **总额 270；每日 150／80／40**。

- 两个 Todo 都 DONE；2 次源执行真实完成；18 个原生检查点；没有正反馈；完整请求生成 1 个 APPROVED 案例 `8849e994-3bb0-451a-ba90-a134d2f8c427`。
- 只出现 1 次预算分配事件，单位 300000 ms、任务数 2、总额 600000 ms。排除人工等待后的执行时间约 **150.202 秒**。该成功请求本身没有消耗超过 300 秒；旧计时器失效边界另由流式定向测试验证，不夸大此请求的证明范围。
- `task-deadline-explicit-final.json/.sql` 保留 Run、审批、Task、SQL Trace、源执行、产物及准入证据。
- `task-deadline-explicit-assessment.json`：16 项业务／准入检查通过；`task-deadline-budget-assessment.json`：9 项期限／审批检查通过。
- `task-deadline-business-oracle.sql/.json` 为独立业务库真值。`task-deadline-double-detail-browser.png` 可见两份金额；另一张 `task-deadline-double-result-browser.png` 为自然语言请求及成功页头。

```sh
python3 scripts/inspect-native-recovery.py --run-id 7c59c9c0-56ea-4c62-871f-7fb13c2e9929 --output "$E/新的只读双任务证据.json"
python3 scripts/verify-task-deadline.py --evidence "$E/新的只读双任务证据.json" --expected-tasks 2 --output "$E/新的期限核对.json"
python3 scripts/verify-case-admission.py --evidence "$E/新的只读双任务证据.json" --expected-tasks 2 \
  --expected-amount 270 --expected-amount 150 --expected-amount 80 --expected-amount 40 --output "$E/新的业务核对.json"
```

## 三 Todo 真实期限补验

Run `1a5b1291-8bcf-41c6-b8a7-96b27f36065f`，会话 `f34e8a1e-a30f-407f-9b0c-1d819523ea82`。通过 computer use 提交自然语言请求，分别统计一月／二月／三月，逐项核对三次口径并批准。三项 DONE，结果分别 **270／300／360**，与 `three-todo-business-oracle.sql/.json` 独立业务库查询一致。

- 一次性分配 900 秒；排除人工等待的实际执行约 **424.499 秒**，确实超过旧单任务 300 秒，未被旧定时器误杀。
- `three-todo-browser-final.json/.sql`：25 个原生检查点、3 次源执行、3 次审批、1 个自动 APPROVED 案例、没有反馈造成功；请求级 1 份＋Todo 级 3 份持久化历史快照。
- `three-todo-deadline-assessment.json` 9 项通过，`three-todo-admission-assessment.json` 16 项通过，`three-todo-history-assessment.json` 12 项通过。
- `three-todo-concurrent-runs.json/.sql` 保留该请求与单任务请求 `0eb51244-7a91-4c03-9243-36f26608a2c3` 同时 RUNNING 的实录；两个最后都成功。这是两条真实请求重叠执行，不能替代完整并发容量测试。
- 本次执行 JAR `f8b9facce9ecbbfa164f801a4fd5132981f55258e6dca945728b765a43f1aec2`，对应 `catalog-fts-full-backend-2.log` 与 `catalog-fts-deployment-2.json`。
- 截图 `three-todo-result-browser.png` 暴露展示缺陷：结论包含三份正确金额，但表格缓存只显示第一项，口径却是最后一项。此截图保留作为失败证据，不能当完整 UI 通过；修复与复测已通过，见[多 Todo 结果展示验收](多Todo结果展示验收.md)。

```sh
python3 scripts/inspect-native-recovery.py --run-id 1a5b1291-8bcf-41c6-b8a7-96b27f36065f --output "$E/新的三任务证据.json"
python3 scripts/verify-task-deadline.py --evidence "$E/新的三任务证据.json" --expected-tasks 3 --output "$E/新的三任务期限核对.json"
python3 scripts/verify-case-history.py --evidence "$E/新的三任务证据.json" --expected-tasks 3 --output "$E/新的三任务历史核对.json"
python3 scripts/verify-case-admission.py --evidence "$E/新的三任务证据.json" --expected-tasks 3 \
  --expected-amount 270 --expected-amount 300 --expected-amount 360 --output "$E/新的三任务金额核对.json"
```

## 失败与未测

- **失败并取消**：前一 Run `c21961f3-67f8-4bee-bc71-02dd7d0b9064` 的问题增强把同一消息中“这个月”错误绑定到当前九月，而不是前句一月。第一任务返回 270，第二任务停在审批前，未批准错误计划；通过页面取消，案例数 0。证据 `task-deadline-multi-wrong-month.json/.sql`、`task-deadline-wrong-month-cancelled.json/.sql`、`task-deadline-wrong-month-browser.png`。预算分配成功不能掩盖业务理解失败。
- **后续复测通过**：`QueryEnhanceNode.java` 已补当前消息内部指代优先与歧义问询规则，真实浏览器复测通过一次初始澄清将两份结果都绑定一月，见[请求与 Todo 历史案例验收](请求与Todo历史案例验收.md)。旧失败不删除。
- **未完成**：完整规模／故障矩阵、B.9 整模型资料、B.10 全纠正分类及 Git 历史整理。B.7 FTS＋真实向量混合召回已另批验证，见[正式资料FTS验收](正式资料FTS验收.md)。三 Todo 执行／审批已通过，展示错配另行修复验收。
- **受阻**：本批没有缺少本地组件或凭据的新阻塞。旧文中“是否允许按任务延长期限”的待确认项已由用户本次答复解决。
