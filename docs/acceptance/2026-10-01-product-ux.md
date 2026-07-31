# 2026-10-01 产品与真实使用验收增量

本批对应 SQL-03／04／05 的交互和回归证据增量，不代表整份任务清单完成。代码已经实际部署，使用真实浏览器、既有账号、真实 gpt-5.6-luna／terra 与 PostgreSQL；不修改秋招材料。证据目录：`$LOCAL_EVIDENCE_ROOT/work/product-ux-20261001`。

## 产品取舍与代码

参考 [Metabase 查询工作台](https://www.metabase.com/docs/latest/questions/query-builder/introduction)、[Hex 语义模型工作区](https://learn.hex.tech/docs/connect-to-data/semantic-models/semantic-authoring/semantic-authoring-overview) 和 [Hex AI](https://learn.hex.tech/docs/getting-started/ai-overview)：普通成员直接问数，管理操作按权限呈现，结果、依据和待确认动作就近显示，技术记录按需展开。

- `frontend/src/views/ProjectChat.vue`、`utils/query-workspace.ts`：自然语言直接发送，创建会话自动完成；按项目／会话保存本页未发送草稿；不同 ID 的同名会话保留；新建空会话展示引导；问询卡放在所属消息旁；阅读历史时不强拉到底部。失败保留原输入，等待确认时拦截新发送。
- `components/chat/{ChatWelcome,QueryRunProgress,AnswerCard,RunDetailsDrawer}.vue`：完成状态收拢，实际执行依据持续可读，等待确认入口明确；抽屉适配手机宽度；同步事件数不宣称全量记录数。
- `layouts/BaseLayout.vue`、`styles/global.css`：复用 Element Plus 主题与组件，统一主色、间距、焦点和减少动画；普通成员隐藏平台管理入口；390px 查询页与结果卡不发生整页横向溢出。
- `components/project/ProjectDefinitionGovernance.vue`、`utils/definition-candidate-filters.ts`：关键词、演进状态、贡献门槛、公共比较独立组合；显示真实分母、无匹配、清除筛选；已发布候选明确显示发布前历史比较，保留当前贡献语义。
- `components/project/ProjectEvaluations.vue`、`utils/evaluation-presentation.ts`、`services/semevosql.ts`：用真实 DTO 字段展示编号、回放方式、预期和结果。任务执行完成与用例通过分别展示；零用例不允许开始；安全证据缺失、统计矛盾和失败不会显示通过。兼容实际 PostgreSQL PGobject／versioned JSON。`ReplayEvidenceDrawer.vue`复用Element Plus抽屉，以用例原问题、检查结论、缺失／失败原因、实际结果明细展示；SQL及原始记录默认折叠。只展示已有SOURCE_RESULT，旧记录缺明细明确说明，未知或自相矛盾的逐项状态不记通过。手机用例／任务改为卡片，不需横向拖动宽表找操作。运行中自动同步已存进度并阻止误点重复创建；失去创建响应时复用幂等键。切版本和重复加载使用代次防止旧响应覆盖。
- `ProductionGoldenReplayRunner.java`：时间边界按编译器同一 `LocalDateTime` 契约比较；保留执行器已有脱敏结果、实际 SQL 与绑定参数。无另造执行或成功状态。
- `frontend/src/main.js`、`deploy/semevosql/nginx.conf`、`scripts/{web_asset_retention,deploy-tested-web-acceptance,local-acceptance}.py`：按 [Vite 部署说明](https://vite.dev/guide/build)／[排错说明](https://vite.dev/guide/troubleshooting) 保留当前＋前两版静态资源，HTML 不缓存，资源缺失真实404。`vite:preloadError` 显示恢复提示，提醒保留草稿，不自动重发查询。全量和仅前端部署复用同一资源保留路径；`--backend-only` 保留正在运行的前端。运行 Nginx 仍为 UID101。后端仅在没有执行／等待任务时重启。
- 初始化与检查：`deploy/acceptance/product-ux-golden-cases.json`、`scripts/seed-product-ux-golden-cases.py`、`scripts/verify-product-ux-replay.py`；只往本机项目2创建一个独立用例，同内容重跑复用 ID，异内容拒绝覆盖。

## 实际通过

- `sem-web-verify-12.log`：lint、Vue typecheck、unused、17 项前端回归、构建通过。新增的 8 项行为验证包含会话 ID、发送边界、草稿隔离、候选筛选、零用例／失败／缺安全证据结果及真实 PGobject。
- `sem-web-deploy-12.json/.log`：67 个当前文件与运行容器完全一致，含有界旧资源，API代理可用，未重启后端。
- `sem-replay-proof-targeted-1.log`：20 项通过、0失败／错误／跳过，包含真实 PostgreSQL 时间过滤。
- `sem-replay-proof-full-1.log`：最新完整包873项通过、0失败／错误／跳过，5分10秒。`sem-proof-full-runtime-identity.json`确认完整回归产物与实际运行JAR均为`c042…4a72`；计数来自本次日志最终Surefire汇总，不累加残留的旧XML报告。前一时间修复包的873项结果独立保留。
- `sem-replay-proof-deploy-1.json`：实际 JAR 与20项专项产物一致，health UP，67前端文件一致，frontendRebuilt=false。实际 JAR SHA256：`c042fccb1c0cf07880b401b052d054a9aaf4120d37c24c5864266f3dfbba4a72`。
- 自然语言问数 Run `9f52b2f6-8e40-4e9f-8c3f-1fb8ac92b80f`：普通成员正常批准执行、真实 QUERY 返回294.000元、正常确认结果正确。个人口径16／r6保留，公共版本10不变；独立 SQL为420×0.7=294，13个原生检查点。`sem-personal-query-lineage-1.json`12项交叉核对通过。
- 本页 A→B→A 未发送草稿切换正确，成员导航按角色显示。项目候选8条、历史冲突3条；组合筛选与API／数据库一致，成员／外部账号管理接口403。`sem-candidate-ui-crosscheck.json/.sql`5项通过。
- 回归空用例按钮实际禁用。之后用正常认证API初始化，用同一SQL含义、同一冻结预期，通过真实页面启动任务。
- 回归 job `28ccbc7b-bbb7-308e-b321-845ce5df3f99`／Run `6a95569d-5718-4dac-a751-0e5cc5b3fd26`：1项通过、0失败、safetyPassed=true；真实模型规划1次，实际源1、SQL有绑定参数、SQL执行392ms、真实结果420.00。`sem-replay-crosscheck-1.json/.sql`7项通过：认证API／真实作业表／Run事件一致，保留实际SQL与结果，独立业务DB oracle确认1月5单420元。
- `sem-replay-presentation-proof.json`：真实页面自动显示3条任务、0运行中，无额外创建。实际手机390×844、documentWidth390、抽屉左0右390；技术记录默认折叠。桌面实际通过结果与历史失败均打开核对；手机卡片直接打开真实420.00明细。
- 作业回放使用既有独立worker，不走交互聊天原生图，0个原生checkpoint；上面聊天Run的13个checkpoint不能混用。
- `sem-old-tab-before-deploy.json`／`sem-old-tab-after-deploy.json`：旧页面在不刷新情况下跨部署后打开项目概览成功。`sem-old-chunk-http-proof.json`验证旧哈希chunk HTTP200、JS类型与manifest SHA一致。最新保留3版，额外旧资源1,338,520字节，占用有界。`sem-asset-retention-unit-3.log`4项通过：版本上限、相同文件名冲突、篡改和路径越界。

## 失败与修复记录

- 初次回归 job `1f8d9883-0b48-389b-8b31-cddf199b2226`：任务SUCCEEDED，但1用例FAILED，因为00:00与00:00:00字符串比较；未执行SQL。真实页面现在显示“检查未通过”，保留历史失败。时间修复后同一用例不改预期，第二次和第三次实际成功。
- 初次保留资源部署 `sem-web-deploy-5.log` 因Nginx默认50x.html导致完整文件断言失败；`-6.log`因非root构建清理权限失败。`-7`起修复为构建时root清理、运行UID101，完整字节校验通过。
- `sem-web-verify-7.log`有TS隐式any错误，已修复，最新完整verify通过。
- `sem-asset-retention-unit.log`错误目录运行0项，不能计通过；正确根目录`-2`／`-3`各4项。
- 种子脚本首次重复检查暴露PGobject包装，不覆盖既有数据；修复后的`sem-golden-seed-3.json`复用原ID。UI解析采用相同实际返回结构。
- `sem-personal-query-review-1.json`严格要求真实模型事后语义审核失败：该条走DETERMINISTIC审核。独立数值／审批／血缘通过，不能宣称这条“模型事后审核闭环”通过。

## 截图和手动重跑

`sem-query-result.png`真实294元结果；`sem-chat-mobile-390.png`实际390×844；`sem-replay-results-visible.png`同时显示真实通过／失败；`sem-replay-drawer-desktop.png`、`sem-replay-drawer-failure.png`、`sem-replay-drawer-mobile-390.png`与`sem-replay-cards-mobile-390.png`为抽屉／手机卡片新界面；`sem-old-tab-navigation-success.png`跨部署旧页面成功。
最终部署后正常刷新并从任务18:25:41打开抽屉，`sem-replay-drawer-final-desktop.png`显示同一job的1次模型调用、392ms、1行真实420.00及默认折叠SQL／技术记录；未再创建回归任务。
`sem-governance-mobile.png`实际是桌面1274×720，不是手机证据。`browser-viewport-limitation.json`为早期未生效尺寸记录，后来tab5实际390已成功；本批结束恢复默认尺寸。

访问 http://127.0.0.1:3303/semevosql/projects/2?section=release → 验证与发布／测试与回归。既有本机账号在 ignored `deploy/.acceptance-private/accounts.json`，不复制到本文；通过正常登录。浏览器可运行同一用例并展开逐项依据。查询工作台直接输入中文，通过审批后查看SQL／结果并反馈。

```sh
cd $REPO_ROOT
UX_OUT="$(mktemp -d /tmp/semevosql-ux.XXXXXX)"
python3 scripts/seed-product-ux-golden-cases.py --output "$UX_OUT/seed-1.json"
python3 scripts/seed-product-ux-golden-cases.py --output "$UX_OUT/seed-2.json"
docker exec -i semevosql-acceptance-metadata-db-1 psql -X -v ON_ERROR_STOP=1 -U acceptance -d semevosql_acceptance_business < scripts/sql/acceptance-personal-amount-oracle.sql
docker exec -i semevosql-acceptance-metadata-db-1 psql -X -v ON_ERROR_STOP=1 -U acceptance -d semevosql_acceptance_business < scripts/sql/acceptance-golden-ordered-amount-oracle.sql
python3 scripts/verify-product-ux-replay.py --job-id 28ccbc7b-bbb7-308e-b321-845ce5df3f99 --output "$UX_OUT/replay-check.json"
(cd frontend && npm run verify > "$UX_OUT/web.log" 2>&1)
python3 scripts/deploy-tested-web-acceptance.py --test-log "$UX_OUT/web.log" --output "$UX_OUT/web-deploy.json"
python3 scripts/test-backend-with-databases.py --output "$UX_OUT/backend.log"
python3 scripts/local-acceptance.py up --backend-only --backend-test-log "$UX_OUT/backend.log" --web-test-log "$UX_OUT/web.log" --output "$UX_OUT/backend-deploy.json"
```

## 未测／剩余

本批不替代SQL-03/04全部定义范围和故障矩阵；E.3实际ASSOCIATE、300个独立题目及700次真实评测、完整重构／Git日期整理未完成。OrbisOps与惠多拼整体验收另列，不把这些算通过。`vite:preloadError`提示未做真实网络断网注入，旧页面跨部署成功已实测。手机完成主要问数／运维入口的验证，其他管理页逐项手机验收未全覆盖。

## 受阻

本批真实查询、审批、反馈和单用例回归没有新增凭据或依赖阻塞。严格模型事后审核是已测失败，其他全量评测及未覆盖功能是未完成，不计为通过。

## 同日后续验收

E.3自然语言完整定义确认、真实查询420.00与SQL核对、结果审批及反馈、管理员正常ASSOCIATE至已发布ordered_amount、8路幂等重放与权限拒绝已在后续批次实际完成。参见[自然语言定义与实际等价关联](2026-10-01-definition-association.md)。该批后端完整880项、前端23项通过，且实际部署与测试字节一致。上述“E.3未完成”是本文件原批次结束时状态，后续增量已补齐；其他全量评测与全部项目验收仍未完成。原始失败与未测项继续保留。
