# SQL-01／02／03／05：真实崩溃恢复与修改后审核

本批使用既有隔离 PostgreSQL 业务数据及原账号，不改审批、查询或案例成功状态。独立参考 SQL 为 `scripts/sql/acceptance-golden-ordered-amount-oracle.sql`：2026年1月全部状态5笔订单，下单金额420元。

## 已核验的真实恢复

浏览器用正常中文请求全部状态订单下单金额并批准执行。`test-local-sql-lock-recovery.py` 在隔离业务库持有临时锁，确认该 Run 的实际 SQL 正在等待锁后强杀本地后端，再回滚锁并重启。第一次锁到期而未强杀，记录为失败；第二次确实强杀、清理并恢复。

Run `5c9de077-9526-4e03-bab9-f594c69908c5` 原生检查点恢复后完成：原 SQL 尝试期限保留，SQLState57014、SESSION_TERMINATION_CONFIRMED、共享 SQL 修复1次；新 EXPLAIN／PREVIEW／QUERY 全部结束，结果420.00，完整请求案例自动 APPROVED。18项确定性验证通过。

核对审核细节又发现：实际 SQL 修复走模型生成，但最初 Blueprint 仍标记 DETERMINISTIC，使原后置复核跳过模型。这一轮不能算模型复核通过。严格探针新增 `--require-model-review`，原证据明确失败，不重写旧成功记录或绿色报告。

`PostExecutionReviewService` 按实际 ADVANCED_EXECUTION 路径及已知模型生成模式要求模型复核，不能被可选审核开关或原编译提示绕过。模型不可用或无效时沿原错误处理，不降为确定性成功；继续复用原审核、持久化节点效果和修复预算。新增回归先复现失败，再修复。

修复后的新 Run `afa7d30e-c2b9-4578-820c-04962ead3579` 已由真实浏览器输入普通中文、正常批准，等待实际业务库 SQL 锁时强杀并重启后端。原生23个检查点恢复同一 Run，结果420.00；严格20项检查全部通过，包含审批、失败／成功SQL尝试、远端清理、期限不重置、共享修复1次及必要模型复核。独立参考SQL仍为5笔／420.00，原案例自动 APPROVED，不依靠手动反馈或改库。

实际模型复核 callId `2b35aa12-b918-4106-95cf-7a93a411dd81`，输入2365／输出190 token，8454ms，PASS／置信度0.99。实际HTTP用途 SEMANTIC_RESULT_REVIEW 与持久化效果记录一致。模型身份依据未改变的默认客户端与当前有效配置 `gpt-5.6-luna`，不把配置值声称为provider回包字段；Terra配置保留。旧 Run 的模型复核失败证据仍单列保留。

## 登录与页面恢复

真实强杀还暴露页面持续401却显示处理中。`localSession.ts`集中识别本地API登录失效；`App.vue`复用 Vue watch 和 Vue Router 返回登录页并保存原地址，页面明确提示重新登录。不会自动重放401的写请求或替换用户账号，原CSRF旋转的同账号一次重放规则保留。

真实无活跃查询时重启同一后端及上述新 Run 的强杀恢复均实际操作：页面自动进入登录页，原账号重新登录后回到原项目／查询，仍显示420.00，没有提交新查询。前端25项、lint／类型／unused及构建通过；68个当前静态文件与运行容器字节一致，前端部署未重启后端。两项新增认证测试分别验证401写请求不重放、错误密码不触发会话过期。

## 完整回归与迁移入口

显式包含 `*Test,*IT` 的最终 package：881项通过、零失败／错误／跳过。第一轮因升级矩阵缺专用连接配置而失败，保留原日志；矩阵现复用已有Testcontainers／pgvector PostgreSQL16，创建唯一测试Schema，不删除已存在的业务Schema。显式CI连接配置继续支持。

`scripts/verify-db-upgrade-matrix.sh` 改用同一个框架测试入口，取消按调用者提供的容器名称删除／重建数据库的逻辑。实际把旧环境变量设为当前元数据库容器名运行，矩阵1项通过，原元库容器仍为9月26日创建、9月30日启动。本证据是完成后的实际观察，不虚构改造前快照。

部署 JAR SHA256 `cbbd4fecc41972982075264ade2b7f1e2e77f54566fa6869afd5b05e3ccbce5d` 与容器实际字节相同。迁移包装脚本的修改发生在部署之后，仅更换测试入口，没有修改运行中的Java代码。

## 可重跑命令

```sh
SEM_PROOF="$(mktemp -d /tmp/semevosql-recovery.XXXXXX)"
python3 scripts/test-local-sql-lock-recovery.py --run-id <等待正常页面批准的Run-ID> --crash-backend --output "$SEM_PROOF/fault.json"
# 看到LOCK_HELD后，在真实页面点击批准执行；程序观察实际SQL才强杀。
python3 scripts/inspect-native-recovery.py --run-id <同一Run-ID> --output "$SEM_PROOF/run.json"
python3 scripts/verify-sql-recovery.py --evidence "$SEM_PROOF/run.json" --fault-evidence "$SEM_PROOF/fault.json" --amount-column ordered_amount --expected-amount 420 --require-case --require-model-review --output "$SEM_PROOF/verified.json"
docker exec -i semevosql-acceptance-metadata-db-1 psql -X -v ON_ERROR_STOP=1 -U acceptance -d semevosql_acceptance_business < scripts/sql/acceptance-golden-ordered-amount-oracle.sql
./mvnw -B -pl backend -am '-Dtest=*Test,*IT' -Dsurefire.failIfNoSpecifiedTests=false package
SEMEVOSQL_UPGRADE_MATRIX_CONTAINER=semevosql-acceptance-metadata-db-1 bash scripts/verify-db-upgrade-matrix.sh
```

故障脚本只允许命名的本地验收库、目标必须等待页面审批且无其他运行查询；事务最长120秒，finally回滚及恢复后端。每次使用新证据文件名。原`verify-sql-recovery.py`默认支付金额列行为保留，新增参数指定本次下单金额，不能把不同业务指标混为同一结果。

问数地址 http://127.0.0.1:3303/semevosql/chat?projectId=2 。种子仍由 `seed-local-business.py`／`seed-quality-business.py` 幂等初始化，不覆盖现有业务数据。

本批不是300个独立问题／700次模型评测的完成报告。其他未测、原失败和整体历史整理仍需分别记录。

规模种子实际重复导入复核7项通过：200客户／50商品／2000订单／4000明细／204退款，全部既有业务行保留，小切片独立基准、外键与NULL边界、只读拒写及元库隔离均通过。证据 `sem-retained-quality-seed-verification-1.json`，数据Hash `12fc776fbc3c872cc8a053722cdd41a5553865c1b2bfbdcb5a67205e710a8d10`。没有写查询、审批或发布状态。

## 后续页面发现的三项缺陷

实际页面发现三个缺陷，并分别保留失败 Run、数据库快照、日志和截图：

1. `b8fee4a0-e9ec-4c81-894f-d6224bb66b54`：中文澄清以 QUERY 范围保存后，`RelationshipCardinality` 未注册导致原生检查点失败，尚未发送 SQL。显式要求确认的这次提问不能算无提示自动发现。修复通过可信 DTO 字段类型注册枚举依赖，保持检查点格式与类型白名单。
2. `79a87bc4-c2ce-448d-94b8-b3031f9605c1`：普通用户仅问“按下单月份统计2026年第一季度的退款率”，模型实际主动发现歧义；模型遗漏 OTHER，页面接受中文自定义回答但服务拒绝。兼容旧问题的读取边界补充 OTHER，业务候选映射仍排除控制选项。普通取消流程结束旧 Run，未改库制造成功。
3. `682503b1-1a8e-4ad5-81fd-977fcd192301`：上述自定义答案已实际保存，检查点恢复成功；审批前却发现“4月1日之前／至少一笔”被跨句误识别成排名，计划带 LIMIT 1。按通用语句边界、数量和时间单位修复排名识别，保留真实 Top/Bottom/最高/最低查询。正常取消旧 Run，没有批准错误计划。

每个反例先复现再修复。最新专项68项与显式 `*Test,*IT` 完整package897项通过、零失败／错误／跳过。新后端已部署，JAR SHA256 `44009092c66c15d4a2ed2a771db83a934a4a36b0a36a5098b1d00d4ec2786356` 与容器字节相同；前端25项、68个部署资源匹配且未重建前端。证据 `sem-ranking-scope-{targeted-2,all-package-1,full-test-results-1,backend-deploy-1}`。**退款率最终模型生成 SQL／数值业务验收仍在重新执行，尚未记通过。**

首次无会话页面 composer 与文案共用有效版本，发送自动创建会话无需额外按钮。前端证据 `sem-first-query-version-web-{verify,deploy}-1`。

独立基准 `scripts/sql/quality-refunded-order-rate-oracle.sql`，实际日志 `sem-refunded-order-rate-oracle-1.log`：1月687单／45成功退款单／6.55%，2月622／0／0%，3月689／23／3.34%。不输入模型，不修改公共目录或问询结果来迎合基准。


## 本次临时口径的结果契约缺口

运行 `2721c972-a567-4ec9-8ec4-2a2807629bd0` 正常中文问询、QUERY 回答、审批与实际 SQL 均完成，输出三个月数量687/45、622/0、689/23，但没有退款率百分数，业务验收失败。通过正常页面提交负反馈后原案例 QUARANTINED；没有改库或删除原结果。

现有结果契约原先只支持公共指标与保存的个人文本输出。通用修复增加本次 QUERY 输出，模型只能选当前 Run 已提交的口径ID，程序冻结完整文本、版本与SHA-256，拥有输出别名。依赖指标保留，未请求的基础数量不强迫输出。执行、SQL Guard、数值验收、必要模型复核与原生检查点继续复用原流程；不引入公式DSL或额外持久化个人口径。旧检查点缺 queryMeasures 时保持空集合，不补造过去的确认。

46项专项和8项证据校验测试通过；另保留编译、格式及旧检查点兼容修复过程的失败日志。完整回归正在运行，本增量尚未部署／退款率业务重验尚未通过。部署与最终结果以新的独立证据为准。

本增量随后完成904项完整回归，零失败／错误／跳过（`sem-query-result-contract-all-package-2.log`）；首轮904中的1项旧错误文案断言失败留存，未知字段仍拒绝。部署JAR `38fc53a478b9dc95d2071e9e23604616e37541bfe25270529a254c8730cbdc64` 与容器字节相同、68前端资源匹配；退款率实际页面重验正在进行，不能仅凭部署或回归记业务通过。

## 已确认的生成与映射边界缺陷

部署38fc后的真实浏览器运行 `3d807231-84f4-43ec-8426-f3d712313e43` 已正确冻结QUERY口径与输出列，但模型重写了目录中成功退款订单数的公式，两次被原语义约束拦截，随后到达原5分钟期限。运行FAILED、0次SQL执行、0个结果产物；不记业务通过。另确认物理表与Model同名时，限定表名会被错误地按basename认成逻辑模型。两个新的反例在原实现中失败，修复保持模型来源固定过滤与所有既有门禁。

`SemanticSqlPromptContract` 在原规划与SQL生成节点共享来自实际冻结Blueprint的指标／关系原语清单；修复SQL沿用相同系统优先级和原Run剩余期限，不能因原SQL没有METRIC或普通报错就切换到物理表路径。没有按退款率问题写专属SQL，不削弱发布指标校验，不增加公式DSL。真实PostgreSQL分别验证派生占比与目录过滤、同名模型隔离物理表，专项61项通过；最终完整回归、部署及业务闭环以后续新证据为准。

## 已确认输出字段的执行前校验

上述修改完成910项完整回归并部署后，真实 Run `7c553e7f-1eff-4619-a973-3742a30efb2c` 已使用发布指标与关系原语，实际SQL得到1月6.55%、2月0.00%、3月3.34%，与独立参考SQL一致。但模型把程序拥有的输出字段 `q_7795e1d68c7542cea70be6021d1369d0_1` 截去版本后缀，原结果校验正确拒绝。必要模型复核也指出字段缺失，两次修复仍未纠正，最终修复预算耗尽。该运行为FAILED；正确数值不能替代完整结果验收，也不能归因于网络超时。失败数据库快照、实际SQL、模型复核和页面截图保留在 `sem-governed-generation-alias-failed-1`。

通用修复在原 `QueryPreflightService` 使用现有Druid AST核对最终SELECT的输出字段，包含CTE和UNION的实际输出命名规则，缺少完整已确认字段即在发送SQL前拒绝。共享提示契约显式给出完整字段名，包含确认版本；原后置复核在确定性结果已无效时直接沿现有修复路径返回，避免浪费一次模型请求。有效模型生成结果仍必须执行必要模型复核，没有降级成功或放宽指标约束。

66项专项与919项显式 `*Test,*IT` 完整package通过，零失败／错误／跳过。已部署JAR SHA256 `cb6b0ba68ec48deb97dc3a58d83699171547cd0b5fd991c5bd8a54eeeedc5c8f`，容器实际字节一致，68个前端资源匹配且未重建前端。证据 `sem-confirmed-output-preflight-{targeted-1,all-package-1,full-test-results-1,backend-deploy-1}`。真实浏览器业务重验另行记录，部署与自动化通过不代表它已通过。

## 真实中文问询到结果闭环通过

最新 Run `9aa5fe60-30b5-4da3-a14d-0fe21eff8c39` 仅输入“按下单月份统计2026年第一季度的退款率。”，Luna主动询问金额／订单数及时间归属口径。使用普通中文补充本次含义，保持“仅本次”，未写个人或公共定义，再正常批准查询。27个原生检查点，实际EXPLAIN／PREVIEW／QUERY成功；完整输出字段 `q_c4db8290f78546b8b8ddfb824260508b_1` 保留，1月6.55%、2月0.00%、3月3.34%。

独立参考SQL、字段来源／版本／完整文本Hash、发布v11、只读业务库、正常审批、SQL回执与页面产物15项核验全部通过；必要模型复核与最终SQL／产物／会话／案例13项核验全部通过。实际复核callId `65860cb1-560e-44e4-8689-27fb966bcd32`，4288输入／380输出token，13779ms，PASS／0.99；原SQL修复预算用了1次，恢复不重置。核验后在正常页面确认结果正确，带用户采纳的18项检查通过，原案例 `4f4f6e12-a482-436c-ac68-5f260e9989e4` APPROVED。

证据 `sem-confirmed-output-preflight-{final,quality,feedback,feedback-quality,reviewed}-1`、`result-1.png` 及独立`.sql`均保留。所有旧失败记录保留。本次证明本次业务流程通过，不等于300个独立问题／700次评测或整体项目已完成。
