# SQL 限时、清理与有界调整验收

对应交接 G（SQL-01／02／05）及 B.10 的修复后案例收录。2026-09-27。

证据目录：`$LOCAL_EVIDENCE_ROOT/work/semevosql-20260927`（下文 E）。测试使用合成订单及隔离数据库，未改业务完成状态、审批状态或发布状态。

## 已落地

- PostgreSQL 每条查询使用只读事务与事务级 `statement_timeout`，MySQL 使用只读事务及会话级 `max_execution_time`；JDBC 查询超时与取消保留。默认正式执行 30 秒，较小的预检限时保持生效。查询结束回滚并恢复会话参数；外层已有事务使用保存点，不提交外层写入。
- 回滚失败的连接被淘汰，清理未确认时禁止自动 SQL 替代。先取消后注册的请求不会执行 SQL。取消属于终止，不消耗修复预算重发。
- SQL 错误携带错误类别、SQLState、厂商码、耗时、失败 SQL／轨迹引用及清理状态。直接编译失败进入受约束调整，不原样重复编译。权限／连接问题不盲目改 SQL。
- 修复仍共用 SQL 2 次、语义／执行重规划 2 次，移除 `SqlGenerateNode` 的第二套生成次数限制，保留诊断计数。每个 SQL 修改版本重新经过审核和预检。
- V41 `qw_sql_execution_attempt` 保存 Run、图尝试、逻辑阶段、输入指纹、会话归属、不可重置的截止时间、脱敏结果和错误。EXPLAIN、PREVIEW、QUERY、FRESHNESS 分开记录；相同逻辑尝试更换输入会拒绝。
- 同一尝试恢复时复用已保存的脱敏结果或核对旧会话，不重发原尝试。PostgreSQL advisory lock／MySQL named lock 标记程序拥有的会话；恢复先撤销旧尝试提交资格，再确认旧会话结束，才允许共享预算中的新调整。
- 内部会话控制是固定、参数化的程序命令；业务 SQL 继续使用连接池 SQL 防护，并拒绝会话终止、锁操纵及覆盖超时的 optimizer hint。
- 修复路径的 `DIRECT_RESULT` 经最终 PASS 审核后，也能按原规则收录完整请求案例；源分片或失败结果不因此放行。

相关文件：`connector/{SqlExecutor,JdbcQueryScope,JdbcAttemptSessionLock,JdbcStatementCancellationRegistry,SqlAttemptReplayException}.java`，`sql/application/{SqlExecutionAttemptService,SqlFailureEvidence,SqlValidationClassifier,SqlExecutionGuard}.java`，`semantic/application/{VerifiedQueryExecutionService,SourceSqlExecutionException}.java`，`multisource/MultiSourceSqlExecutionService.java`，`workflow/node/{SemanticExecutionNode,SqlGenerateNode,SqlExecuteNode}.java`，`learning/QueryCaseRequestEvidence.java`，`V41__durable_sql_attempts.sql`。以上 Java 路径位于 `backend/src/main/java/cn/lgs/semevosql/`。

## 实际测试命令与结果

在仓库根执行，Java 使用 `/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home`。

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home ./mvnw -o -pl backend -am package
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home ./mvnw -o -pl backend -am -Dtest=SqlAttemptDatabaseIT,QueryCaseRequestEvidencePostgresIT,SqlFailureRoutingTest,SqlSessionControlGuardTest -Dsurefire.failIfNoSpecifiedTests=false package
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home ./mvnw -o -pl backend -am -Dtest=SqlTimeoutDatabaseIT -Dsurefire.failIfNoSpecifiedTests=false package
```

- 全量默认后端：**334 通过，0 失败／错误／跳过**，`E/sql-durable-full-backend-3.log`。
- 最后版本专项：**42 通过**，其中 11 个真实数据库尝试测试、26 个真实 PostgreSQL 案例证据测试、5 个保护／分流测试；`E/sql-durable-final-it-1.log`。
- 较早组合专项：54 通过，含 10 个 SQL 取消测试；`E/sql-durable-tests-5.log`。PostgreSQL 40 秒慢查询实际在 **30.032 秒**停止（57014），随后同一连接 SELECT 42 成功，取消注册表清零。MySQL 数据库端独立的 1 秒验证实际在 **1.027 秒**终止（3024）；另验证默认 30,000ms 限制和原会话参数恢复。
- 已实际强杀独立 Java 查询进程：原截止时间保留、旧后端会话消失，新调整 SELECT 42 成功；不是抛出 Mock 超时代替进程强杀。
- 并发重复提交、同键换 SQL、取消失败不补发、迟到旧进程不得继续提交、PostgreSQL／MySQL 原生连接及实际 Druid 池恢复均已覆盖。连接池恢复专项 `E/sql-durable-pool-tests-2.log` 15 通过。

上述用例集合有重叠，不相加作为独立测试总量。编译／初次集成的失败日志也保留在 E（`*-1.log` 等），未当作通过证据。

## 浏览器自然语言超时修复

Run `90a4bcb6-6079-4eaf-a39c-5fe7f1ebefa5`，用户输入“请查询2026年2月已支付订单的支付金额总计，按支付时间筛选，不扣退款。”，通过真实页面批准执行。

测试在隔离业务库对 orders 持有临时排他锁；实际 EXPLAIN 约 10.085 秒超时，SQLState=57014，清理记录 `ROLLBACK_CONFIRMED`。脚本发现真实失败轨迹后释放锁，模型进入受约束生成；新 SQL 经过预检、执行及 PASS 审核，最终网页与实际结果表均为 **300.00**。Run 中记录 **SQL 修复 1 次**，未新增澄清问题。

证据：`E/sql-lock-recovery-1.{json,sql}`、`E/sql-lock-recovery-browser-final.{json,sql}`、`E/sql-lock-recovery-assessment.json`（10 项通过）、`E/sql-lock-recovery-browser.png`。该次执行的 JAR 为 `37b021f005a8cbcb076328d75b652392ce9c081e6702cd38c5175072e03e947d`，尚未含 V41；不能用它证明持久化尝试恢复。它还暴露了 DIRECT_RESULT 案例漏收问题，该问题随后已修复并由真实 PG 集成测试覆盖。

## 可重复手动测试

地址：`http://127.0.0.1:3303/semevosql/chat?projectId=2`。业务数据和独立 oracle：`deploy/acceptance/sql/business-seed.sql`、`business-oracle.sql`；重复初始化用 `python3 scripts/seed-local-business.py --help` 查看既有脚本参数。不要清库。

先从网页发起正常自然语言查询，等“批准执行”，从查询记录取得 Run ID，然后在另一终端运行：

```sh
python3 scripts/test-local-sql-lock-recovery.py --run-id <Run-ID> --output <新证据路径.json>
# 看到 LOCK_HELD 后，在网页点“批准执行”。脚本会释放锁；最长 120 秒，事务另有超时保护。
# 如需真实后端强杀并自动重启，仅针对本地 acceptance 容器：
python3 scripts/test-local-sql-lock-recovery.py --run-id <Run-ID> --crash-backend --output <新崩溃证据路径.json>
python3 scripts/inspect-native-recovery.py --run-id <Run-ID> --output <新结果证据路径.json>
python3 scripts/verify-sql-recovery.py --evidence <结果证据.json> --fault-evidence <故障证据.json> --expected-amount 300 --require-case --output <新检查结果.json>
```

脚本只锁定命名的隔离业务库；有其他运行中查询就拒绝干扰。它通过实际失败／实际 SQL 锁等待触发释放或强杀，不改应用成功状态。证据同时保存可查看的 SQL。不要将脚本用于其他环境。

## 边界

这份记录不代表整个 G 或项目已完成。全应用的“浏览器查询→后端强杀→原 Run 恢复”在下方补录；真实 MySQL 默认 30 秒完整计时已补充，见下方记录。模型 HTTP 传输重试预算、300 模型评测、其他语义资产改造另行验收。

实现依据：PostgreSQL [事务／会话设置](https://www.postgresql.org/docs/16/sql-set.html)、[statement_timeout](https://www.postgresql.org/docs/16/runtime-config-client.html)、[会话终止与 advisory lock](https://www.postgresql.org/docs/16/functions-admin.html)；MySQL [max_execution_time](https://dev.mysql.com/doc/refman/8.4/en/server-system-variables.html#sysvar_max_execution_time)、[named lock](https://dev.mysql.com/doc/refman/8.4/en/locking-functions.html)、[KILL](https://dev.mysql.com/doc/refman/8.4/en/kill.html)。这些机制的返回值区分“发出终止信号”和“确认会话结束”；本实现与测试以后者为恢复条件。

## 全应用强杀首轮暴露的缺陷与修复

首轮 Run `07bb74eb-5b61-4b9d-8f23-32a93321e5b9`：真实页面批准三月金额查询，数据库确认 EXPLAIN 正在等待 orders 锁时，对 acceptance 后端执行 SIGKILL，释放测试事务并重启同一容器。页面最后显示 360.00，原 SQL deadline 保留且清理记录为 `SESSION_TERMINATION_CONFIRMED`；但该轮 **FAIL**：旧 source-sub-run 仍 RUNNING，重启产生了另一执行标识，未计入 SQL 修复预算。证据 `E/sql-crash-recovery-1.json`、`sql-crash-recovery-browser-final.{json,sql}`、`sql-crash-recovery-assessment.json`、`sql-crash-recovery-browser-failed-evidence.png` 保留，不能当通过证据。

根因是执行指纹直接序列化 Blueprint，其中不可变 Set 的遍历顺序随 JVM 变化。`util/CanonicalJson.java` 对对象键及集合规范排序，保留 SQL 参数等列表顺序；`SemanticExecutionNode`、`SqlExecuteNode` 和 SQL 尝试输入统一采用它。8 个独立 Java 进程实测旧指纹有 **4 种**，修复后 **1 种**，并验证 JSON 及真实 `DurableGraphStateSerializer` 的往返一致性。修复预算变化仍产生新执行身份。

同时取消两处执行服务在落盘脱敏后再次脱敏，防止 SHA256 重复哈希；无持久化上下文的调用仍会脱敏。实际 PG 查询验证首次结果、落盘、恢复结果都是同一个单次哈希，重放不再调用脱敏器。案例准入／每次召回增加未结束 source-sub-run 和未确认 SQL 尝试检查，历史异常记录不被当作完整成功轨迹复用，未改写已有业务状态。

命令与结果：

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home ./mvnw -o -pl backend -am -Dtest=SemanticExecutionNodeTest,CanonicalJsonTest,SqlAttemptDatabaseIT,SqlResultSanitizationTest,MultiSourceSqlExecutionServiceTest -Dsurefire.failIfNoSpecifiedTests=false package
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home ./mvnw -o -pl backend -am -Dtest=QueryCaseRequestEvidencePostgresIT -Dsurefire.failIfNoSpecifiedTests=false package
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home ./mvnw -o -pl backend -am package
```

专项 **19 通过**（`E/sql-identity-tests-1.log`），案例真实 PG **27 通过**（`E/sql-settled-case-tests-1.log`），最终默认后端 **337 通过，0 失败／错误／跳过**（`E/sql-identity-full-backend-2.log`）。最后一份全量日志包含 8 进程及检查点往返验证；集合有重叠，不累加。

## 修复后全应用强杀恢复通过

部署 JAR `6e9b40e3bf69a3b6f74aae66025e2d686b1ffb99d45c5bc167ae07824362eee9`（`E/sql-identity-deployment-1.json`，运行 JAR 一致、63 个前端文件一致）。

第二轮 Run `96dae52e-b7e6-4758-9551-427dbb20d0af`，页面新建会话、自然语言请求三月支付金额、实际批准口径。脚本见到本库 SQL 锁等待后强杀 acceptance 后端，再 ROLLBACK 释放锁并重启。原 Run 恢复后正确结束旧 source-sub-run，保留原尝试截止时间，确认旧会话清理，消耗共享 SQL 修复 **1 次**；受约束新 SQL 经预检及最终 PASS 后返回 **360.00**，案例自动 APPROVED，无额外用户好评或澄清。

`E/sql-crash-recovery-assessment-2.json` **18 项全部通过**，包含数据库与独立种子数据 oracle 的结果、源任务／SQL 尝试全部结束、失败＋成功轨迹、审批、期限及案例收录核对。原始证据 `E/sql-crash-recovery-2.{json,sql}`、`sql-crash-recovery-browser-final-2.{json,sql}`，真实浏览器截图 `sql-crash-recovery-browser-pass-2.png`。首轮失败证据未覆盖。

MySQL 补测命令：

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home ./mvnw -o -pl backend -am '-Dtest=SqlTimeoutDatabaseIT#mysqlDefaultThirtySecondExecutionActuallyStopsAndReturnsCleanConnection' -Dsurefire.failIfNoSpecifiedTests=false test
```

`E/sql-mysql-30s-tests-1.log` **1 通过**：实际大表笛卡尔慢 SELECT 在 **30.282 秒**终止，JDBC 超时分支 `SQLState=null/vendorCode=0`，分类 `SQL_TIMEOUT`，回滚确认，原连接 max_execution_time 恢复 0，自动提交／只读状态恢复，SELECT 42 成功，实际 processlist 无该连接残留查询，取消注册表为 0。与较早 MySQL 服务端独立 1 秒 3024 测试互补，未把它写成数据库原生 3024。
