# SQL AST 作用域与查询账号验收

对应 SQL-01／交接 G。证据 E：`$LOCAL_EVIDENCE_ROOT/work/semevosql-20260927`。

## 已复现问题

`E/sql-cte-scope-baseline-1.log`：16 个回归中 7 个失败，旧 guard 确实放行限定 Schema 同名 CTE、系统表同名 CTE、嵌套作用域泄漏、注释／字符串伪造 CTE、UPDATE／INSERT 型 CTE。不是只根据源码推测。

`E/sql-cte-scope-postgres-baseline-1.log`：实际隔离 PostgreSQL 中，`WITH secret AS (SELECT id FROM orders) SELECT id FROM restricted.secret` 合法执行并读取真实另一表的合成哨兵值 999，而旧 guard 未拒绝，3 个测试中 1 个失败。最小权限账号独立拒绝此表，并未用该漏洞读取任何用户数据。

## 修复

`backend/src/main/java/cn/lgs/semevosql/sql/application/SqlExecutionGuard.java`：删除 CTE 正则及全局 CTE 名豁免，逐个 AST 表来源向上解析当前 SELECT 的可见 WITH 作用域。普通 WITH 只看较早定义；递归语义按方言处理；限定名称始终按真实物理表核权。注释和文本不产生 CTE 声明。

所有嵌套 statement 必须为 SELECT，阻断数据修改型 CTE。按实际 PostgreSQL／MySQL AST 拦截行锁（包括插入注释的 FOR UPDATE／FOR SHARE），保留危险函数和现有预检链。限定白名单不再暗中添加未限定表名；引用未限定表时只绑定配置 Schema。保存物理名称大小写，区分 PostgreSQL quoted Orders 与 orders，也不把 MySQL Linux 上的不同大小写物理表合并；实际 MySQL Linux 容器中 CTE 名也区分大小写，按真实行为保留精确身份。

作用域依据：[PostgreSQL 16 SELECT／WITH](https://www.postgresql.org/docs/16/sql-select.html)、[MySQL 8.4 WITH](https://dev.mysql.com/doc/refman/8.4/en/with.html)。实际适配读取当前 Druid 1.2.22 AST 类型并在数据库上验证，没有升级依赖。

## 可重跑测试

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home ./mvnw -o -pl backend -am -Dtest=SqlCteScopeGuardTest,SqlSessionControlGuardTest,SqlCteScopePostgresIT,SqlCteScopeMysqlIT -Dsurefire.failIfNoSpecifiedTests=false test
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home ./mvnw -o -pl backend -am -Dtest=SqlCteScopeMysqlIT -Dsurefire.failIfNoSpecifiedTests=false test
```

`E/sql-cte-scope-tests-6.log`：30 通过，0 失败／错误／跳过，包括 4 个实际 PostgreSQL 测试。合法普通／嵌套 CTE 实际返回 270；递归 CTE 返回 6；同名限定表为 999、大小写另一表为 888，guard 均区分正确。只有 id／paid_amount SELECT 权限的真实连接对另一 Schema、restricted_note／SELECT *、UPDATE／INSERT CTE 返回 SQLState 42501，原 2 行与金额 270 保持不变。

测试源码在 `backend/src/test/java/cn/lgs/semevosql/sql/application/SqlCteScope{GuardTest,PostgresIT,MysqlIT}.java`。独立手动 SQL：`scripts/sql/cte-scope-repro.sql`，只建事务内临时表、最后回滚，不覆盖已有业务表。应用拒绝验证仍使用上面的真实 Java guard 测试；手动 SQL 只解释数据库名字绑定。

编译首轮因 visitor 自带 dbType 字段遮蔽局部参数失败，改名后通过。扩展注释行锁测试首轮发现 2 个漏拦，按实际方言 AST 字段修复。失败日志 `sql-cte-scope-tests-1.log`／`-3.log` 保留，不计通过。

## 本轮最终真实组件结果

`E/sql-cte-scope-tests-7.log`：33 通过，0 失败／错误／跳过（25 项 guard、1 项原会话控制回归、4 项真实 PostgreSQL、3 项真实 MySQL）。MySQL 8.4 实际库验证合法聚合 270、递归结果 6；限定表哨兵 999、另一大小写表 888 以及小写物理 alias 表 777 都被正确识别为物理表并阻断。大写 CTE ALIAS 不会遮蔽 Linux 配置下的小写物理 alias。

`E/sql-cte-scope-mysql-1.log` 是首次 MySQL 测试失败记录：原先误以为 CTE 名不区分大小写，数据库实际返回 business.alias 不存在。随后增加独立 alias 物理表 777，修正名称解析，并以真实返回值验证没有误认 CTE。该失败不删除、不计通过。

手动复现已实际运行：

```sh
docker exec -i semevosql-acceptance-metadata-db-1 psql -U acceptance -d semevosql_acceptance_business -v ON_ERROR_STOP=1 < scripts/sql/cte-scope-repro.sql
```

结果 `E/sql-cte-scope-manual-repro-1.log`：合法汇总 270、两个限定／越出作用域引用均读取合成物理表 999，最后 ROLLBACK。该脚本可重复执行，只有连接内临时表。

边界：本批完成 CTE／表名作用域与嵌套写入／行锁修复，并用真实最小权限 PostgreSQL 账号验证列授权。不是整个 SQL-01 已完成声明；还需覆盖全部生产入口的列／函数／发布资产联动，以及未测的其他数据库方言和标识符形式。MySQL 验证环境是 Linux 默认大小写行为，未宣称覆盖所有 lower_case_table_names 配置。

## 全量构建、部署与真实页面

- `E/sql-cte-scope-full-backend-1.log`：`./mvnw -o -pl backend -am package`，376 通过、0 失败／错误／跳过。专项 33 与全量套件重叠，不相加声称独立测试数。
- `E/sql-cte-scope-deployment-1.json`：运行 JAR SHA-256 `9fd944d8150370a38023baa36a90332ded528e9bdee4cc747b3736242d573f4f` 与测试包相同，63 个前端文件一致，健康 UP。前端未改动，复用本日 `task-answer-web-2.log` 的已验证产物。
- 页面 `http://127.0.0.1:3303/semevosql/chat?projectId=2` 新建会话，输入“请查询2026年3月已支付订单的支付金额总计，按支付时间筛选，不扣退款。”，实际点击页面批准执行；没有 API 代批、直接改库或点击好评造成功。
- Run `65d13347-7817-476a-a655-55313210355a`：SUCCEEDED，3 月 1 日至 4 月 1 日、paid_at、最终 360.00。`E/sql-cte-scope-browser-final.json` 及同名 SQL 保存运行／事件／SQL／执行尝试／结果／案例；`E/sql-cte-scope-browser-assessment.json` 的 16 项检查全通过。
- `E/sql-cte-scope-browser-pass.png` 为实际页面截图；仅合成业务数据，无凭据。

重跑部署和只读交叉验证（E 替换为上面的证据目录）：

```sh
python3 scripts/local-acceptance.py up --backend-test-log "$E/sql-cte-scope-full-backend-1.log" --web-test-log "$E/task-answer-web-2.log" --output "$E/new-deployment.json"
python3 scripts/inspect-native-recovery.py --run-id 65d13347-7817-476a-a655-55313210355a --output "$E/new-cte-run-inspection.json"
python3 scripts/verify-case-admission.py --evidence "$E/new-cte-run-inspection.json" --expected-amount 360 --output "$E/new-cte-run-assessment.json"
```

通过：上述作用域专项、真实两种数据库、全量回归、测试产物部署、自然语言→计划审批→执行→页面与数据库交叉验证。失败：首轮旧逻辑反例、方言行锁和 MySQL 大小写错误均已修复，失败证据保留。未测：前述剩余 SQL-01 范围；本批没有外部凭据阻塞。
