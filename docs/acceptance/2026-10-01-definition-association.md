# SQL-03／SQL-04 E.3：自然语言定义与实际等价关联

这是实际代码、部署及浏览器验收增量，不代表三个项目或全部清单完成。证据保存在外部本机验收包 `closeout-20261001`，具体地址见交付链接，不在公共仓库记录个人主目录。全部数据为已标记的本机合成验收项目；现有业务数据、个人定义和失败记录保留。模型仍为 gpt-5.6-luna／gpt-5.6-terra，embedding-model 保留。

## 修复及改动文件

- `SemanticDefinitionProposal.java`／`SemanticPlannerPrompt.java`：模型用本轮原文中唯一的起止短引文定位定义，程序截取完整原文并正常请用户确认范围。严格检查唯一性、顺序、字段及长度；旧 definitionText 契约仍支持。模型不直接保存定义，也不授予分享或发布权限。长句、标点或 Unicode 不再依赖模型逐字重写；不采用模糊相似度猜测用户授权。
- `SemanticPlanningRejectedException.java`／`SemanticPlanningClarificationRequiredException.java`／`SemanticBlueprintPipeline.java`／`QueryExecutionEvidence.java`／`SemanticBlueprintNode.java`：失败和待问询同样保留实际调用编号、次数、耗时、token 使用和规范化响应指纹，形成持久化 `SEMANTIC_PLANNING_INTERRUPTED` 事件。原始模型响应和私密提示不进入这个摘要。
- `QueryRunErrorPresenter.java`／`frontend/src/views/ProjectChat.vue`：定义选择校验失败与执行超时分别解释；保留原始失败原因，过期说明准确。
- `DefinitionCandidateMeaning.vue`／`ProjectDefinitionGovernance.vue`：桌面与手机共用完整含义；手机改为可直接展开的卡片。技术字段默认折叠，已发布建议显示历史比较而不是当前冲突。切项目清空旧审核，过期响应不能覆盖当前项目。
- `query-progress-stage.ts`／`QueryRunProgress.vue`：依据实际节点及里程碑推进。通用执行／预算事件不假装 SQL 已经开始，问询与最终成功有独立优先级。
- `localSession.ts`／`App.vue`：复用 Axios 拦截器与 Element Plus 提示。仅后端明确的 Invalid CSRF Token 允许正常刷新 session，并且同账号才原样重试一次，保留请求体与幂等键。普通403不重试，跨账号不重放。保存最近已验证用户名只用于比较；服务端仍验证实时权限。全局提示避免权限变化卸载局部表单后错误信息消失。
- 回归文件：`SemanticDefinitionProposalTest`、`SemanticBlueprintPipelineTest`、`InterruptedPlanningEvidenceTest`、`QueryRunErrorPresenterTest`、`frontend/scripts/{query-progress-stage,local-session-recovery}.test.mjs`。
- 复验脚本：`verify-project-definition-decision.py` 增加八路独立正常登录客户端并发重放；`verify-project-definition-publication.py` 对 ASSOCIATE 核验复用已有公共资产，不要求另造发布版本。

## 实際通过

1. `sem-source-span-backend-full-2.log`：880项通过，0失败／错误／跳过，包含真实 PostgreSQL／MySQL 集成；5分14秒。`sem-source-span-deploy-1.json` 核对本次 JAR 与运行 JAR SHA256均为 `700a3b1ae0a66816d9b4d55fd7de2ebfab79696c05ee97f046be2ac1873ef94b`。
2. `sem-governance-web-7.log`：lint、Vue typecheck、unused、23项回归及构建通过。`sem-governance-web-deploy-5.json` 核对68个当前文件，保留有界旧资源，未重启后端。
3. 浏览器普通中文输入：查询2026年1月的台账下单额。台账下单额是所有状态订单下单金额的合计，按订单创建时间统计，金额单位为元，不扣退款，不做额外筛选。我愿意将这份口径用于项目分享。
   Run `68f1b677-b8d1-42e7-b41a-05805505154f` 的真实 Terra 调用约30.8秒，7788／368 token，生成正常定义问询。用户在真实页面选择完整原文及“允许分享为项目建议”，正常提交。问询 `461db431-22aa-40a7-9ed1-15950fa51143`、答复44、个人定义26／revision1保留。此 Run 后续执行超时，不能计问数通过。
4. 同会话正常追加“使用我已经确认并同意项目分享的台账下单额，查询2026年1月的金额。按订单创建时间统计，金额单位为元。”Run `41c56443-00bf-4e21-812e-33a16271cb47` 实际批准执行，QUERY 返回420.00元；独立业务库1月5单的 SUM(amount)=420.0。SOURCE_RESULT与MERGED_RESULT一致，13个原生checkpoint。`associate-query-lineage-1.json`12项通过。该简单查询使用 DETERMINISTIC 审核，不宣称模型事后审核。
5. 真实页面确认结果正确；`associate-feedback-1.json`4项通过，5分、采纳与实际 Episode、已批准学习案例一致。最终完整记录见 `associate-query-final-1.json/.sql`。
6. 后台 Terra 实际比较候选9“台账下单额”与 `ordered_amount`，表达式、时间、单位、筛选、退款处理等价，程序结构检查通过。1人／1次有效使用，贡献阈值未达；管理员在真实页面提前选择 ASSOCIATE、已有指标及理由并提交。决定9，后台发布任务DONE，候选PUBLISHED，复用原版本10。`associate-publication-after-1.json/.sql`12项通过：公共目录、版本、版本活动、索引与向量、个人定义和结构不变，仅保留正常关联事件。
7. `associate-concurrency-and-permissions-1.json/.sql`11项通过：8路同时重放都返回决定9，没有追加审批、发布任务或使用贡献；同键异内容409，普通成员、外部用户和跨项目管理员403。
8. 实际另一标签页退出后同管理员重新登录，旧页面可正常完成一次审批。实际切换普通成员后，旧管理员页面不重放请求，并显示“登录账号已变化，旧操作未提交”。`changed-account-approval-after-2.json/.sql`2项确认没有决定和发布任务；测试结束已恢复管理员。截图 `changed-account-global-notice-1.png`。
9. 手机390×844真实展开完整含义；documentWidth384、卡片宽330、右边界357，无整页横向溢出。`associate-published-mobile-390.png`，结束恢复默认尺寸。`associate-query-completed-final.png`保留420元及同会话历史超时。

## 失败记录，不能折算通过

- 原Run `305b10ef-e836-48ef-9d61-903268912195` 因模型逐字重写完整定义失败，INVALID_GOVERNED_SELECTION；修复前报告与截图保留。
- Run `68f1b677-b8d1-42e7-b41a-05805505154f` 定义已真实确认，但后续模型网络调用在120秒内超时，持久化恢复继续尝试后达到交互期限，最终FAILED／INTERACTIVE_QUERY_TIMEOUT。后来是新的自然语言 Run 成功，不能改写旧Run。
- 旧标签页最初因缓存CSRF出现403；第一版恢复因后台401清除了当前用户比较依据，仍提示失效。后续限定同账号刷新修复；原截图保留。
- backend-full-1 在测试前因新Java测试缺许可证头失败；web-3 的进度断言失败；均修复，最新完整验证通过。
- `sem-web-input-guard-1.log`是调用部署脚本时误把部署JSON当作测试日志传入，被校验拒绝；不是故障注入测试。随后用真实web-7验证日志正确部署。

## 手动使用与复验

访问 http://127.0.0.1:3303/semevosql/chat?projectId=2 ，正常本机登录后直接中文问数。治理入口为项目2→验证与发布→语义治理→成员建议与演进，搜索“台账下单额”；真实已发布状态及完整含义可查看。账号保存在 ignored `deploy/.acceptance-private/accounts.json`，不复制凭据。

```sh
cd "$(git rev-parse --show-toplevel)"
ASSOC_OUT="$(mktemp -d /tmp/semevosql-associate.XXXXXX)"
test -f "${ASSOC_BASELINE:?先设置为交付的 closeout-20261001 证据目录}/associate-publication-baseline-1.json"
python3 scripts/inspect-native-recovery.py --run-id 41c56443-00bf-4e21-812e-33a16271cb47 --output "$ASSOC_OUT/run.json"
python3 scripts/verify-reviewed-result.py --evidence "$ASSOC_OUT/run.json" --output "$ASSOC_OUT/lineage.json"
python3 scripts/verify-query-feedback.py --evidence "$ASSOC_OUT/run.json" --account semevosql-acceptance-owner --rating 5 --adopted --case-status APPROVED --output "$ASSOC_OUT/feedback.json"
python3 scripts/verify-project-definition-decision.py --candidate 9 --action ASSOCIATE --expected-users 1 --expected-uses 1 --output "$ASSOC_OUT/decision.json"
python3 scripts/verify-project-definition-publication.py --candidate 9 --action ASSOCIATE --operator semevosql-acceptance-owner --baseline "$ASSOC_BASELINE/associate-publication-baseline-1.json" --output "$ASSOC_OUT/publication.json"
docker exec -i semevosql-acceptance-metadata-db-1 psql -X -v ON_ERROR_STOP=1 -U acceptance -d semevosql_acceptance_business < scripts/sql/acceptance-golden-ordered-amount-oracle.sql
(cd frontend && npm run verify > "$ASSOC_OUT/web.log" 2>&1)
python3 scripts/deploy-tested-web-acceptance.py --test-log "$ASSOC_OUT/web.log" --output "$ASSOC_OUT/web-deploy.json"
python3 scripts/test-backend-with-databases.py --output "$ASSOC_OUT/backend.log"
python3 scripts/local-acceptance.py up --backend-only --backend-test-log "$ASSOC_OUT/backend.log" --web-test-log "$ASSOC_OUT/web.log" --output "$ASSOC_OUT/backend-deploy.json"
```

所有快照脚本输出旁边均保留只读SQL。决定脚本只重放已有决定以及被拒绝的请求，不新增审批。初始化继续使用既有 `seed-local-business.py`／`seed-quality-business.py` 与离线目录夹具，不清空数据库或覆盖个人定义。

ASSOC_BASELINE 是交付目录中的真实发布前快照路径，外部验收包README给出本机值；不能用发布后的新快照替代前置快照。

## 未测／待完成／受阻

本批ASSOCIATE链路已通过；300个独立题目与700次实际评测、全部定义范围与故障矩阵、最终整体重构和Git日期整理仍未完成。OrbisOps真实SPLIT与平台评测、惠多拼整体验收另列。没有新增凭据或依赖阻塞；模型网络超时是已测失败，不能归成缺少端点，也不把尚未执行项目计通过。
