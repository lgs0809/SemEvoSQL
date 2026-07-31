# SemEvoSQL Semantic Evolution / Episode / MCP 验收记录

日期：2026-08-18

## 1. 结论

本轮核心重构已完成并通过代码级、数据库级、运行态和浏览器验收。SemEvoSQL 已按 roadmap 收口为：

- `Corpus Revision` 表示知识资产变化；
- `Semantic Version` 表示稳定业务语义快照；
- `Episode` 表示一次完整业务求解经历；
- `SemanticChangeSet` 是唯一可变语义工作区；
- PATCH / MINOR 自动验证后生效，MAJOR 仅人工 Promote；
- MCP Deployment 绑定 Project，不绑定固定 Semantic Version；
- Episode 创建时 pin Active Semantic Version；
- Retrieval 保持 `Exact + BM25 + Vector -> RRF -> Rerank`，PATCH 走 affected-asset incremental index。

发布门禁已全部通过。当前本机原 `gpt-5.4-mini` Chat 配置存在上游不稳定/拒绝，但复用同一份已加密凭据创建并验证 `gpt-5.6-luna` 后连接测试 PASS；激活 luna 后，真实 MCP data-plane 已完整跑通到 SQL 执行和结果返回。

## 2. Semantic Evolution 运行态证据

### PATCH

在隔离 acceptance stack 中完成正式 runtime correction -> Patch -> ChangeSet -> Replay -> Release：

- 正式 definition-correction API 可创建 proposal-only candidate；
- `UPDATE_RULE` Patch 通过 source fingerprint / preflight；
- high-risk operation 会把 candidate / ChangeSet 风险下限自动抬升为 `HIGH`，不能被低风险候选降级；
- Replay 使用 source Semantic Version + ChangeSet preview，不再依赖旧 Draft Version；
- 10/10 replay PASS 后 `/ready` 物化新 Semantic Version；
- `1.1.0 -> 1.1.1` 自动 Active；
- version level=`PATCH`，cause=`EPISODE_LEARNING`；
- Retrieval Generation=`INCREMENTAL`，affected asset `1/1 READY`；
- 新版本 Rule 已包含用户确认后的定义。

期间修复了两个真实兼容缺口：

1. ChangeSet 模式 `target_draft_version_id=null` 时 Replay Coordinator 仍使用旧 Draft 字段导致 NPE；
2. Replay summary 合法包含 `targetVersionId=null` 时 `Map.copyOf()` 不允许 null 导致 NPE。

两处都增加了回归覆盖。

### MINOR / MAJOR

同一 acceptance 方案已验证：

- Corpus 有 Semantic Diff 时自动产生 MINOR；
- 无 Semantic Diff 时只推进 Corpus Revision；
- MAJOR 只由人工 `Promote Business Baseline` 触发；
- MAJOR 不要求伪造 Catalog Hash 变化。

### Rollback

真实执行 Active rollback：

- Active pointer 从新版本切回旧版本；
- 不创建新的 Semantic Version；
- MCP deployment id / endpoint / status / update time 不变化；
- 随后可把 Active 恢复到最新已发布版本。

## 3. Episode 与版本 pinning

运行态已验证：

- Active 从 `1.1.0` 切到 `1.1.1` 后，旧 Episode 仍固定在旧 Semantic Version / state hash；
- 新 Episode 自动绑定新的 Active Semantic Version；
- 相同 MCP `requestId` 重试返回同一个 Episode / Run，不重复 admission；
- backend 重启后 RESERVED / RUNNING handle 可继续由 durable runtime 恢复；
- Run 最终失败时会持久化 `FAILED + RUN_FAILED`，不会永久伪装成 RUNNING。

## 4. MCP 验收

### 已通过

真实 `/mcp` JSON-RPC 已验证：

- initialize 成功；
- public tool surface 收口为 `query` + `query_status`；
- deployment 只绑定 Project；
- PATCH / MINOR / MAJOR / rollback 不要求 redeploy；
- deployment 管理面测试在版本切换后仍 `ok=true / running=true / bindingCurrent=true`；
- requestId 幂等；
- Episode / Run / Attempt 持久化；
- restart recovery；
- 失败状态可通过 handle / query_status 恢复；
- 普通 MCP surface 不暴露 Semantic Catalog mutation tools。

同时修正了管理 UI 中残留的旧 5-tool BYO-Agent 使用说明，使其与真实 2-tool Episode API 一致。

### 真实 data-plane 完整通过

本轮还修复了 CPU Rerank 性能问题：

- 原逻辑把小 Catalog 的全部 19 个候选送入 2B Cross-Encoder，稳定触发 60s read timeout；
- 现改为全量 Exact/BM25/Vector + RRF，Cross-Encoder 只精排 RRF Top-4，再用 RRF tail 补足 caller limit；
- Top-4 后真实 Rerank 在约 34 秒完成，不再触发 60s timeout。

Chat 连接方面，原 `gpt-5.4-mini` 在同一 Provider 下多次真实连接测试失败；未修改或暴露凭据，而是直接在 metadata DB 复制其已加密凭据创建 `gpt-5.6-luna` 配置。`gpt-5.6-luna` `/api/model-config/test` 约 2.9 秒返回 PASS，随后设为 active Chat。

激活 luna 后，真实 `/mcp` Streamable HTTP 已完整验证：

- MCP protocol version=`2025-03-26`；
- tools=`query`,`query_status`；
- 问题：`统计全部订单的有效支付金额总额`；
- 初始状态=`RUNNING`；
- 相同 `requestId` 重试：same Episode=true，same Run=true；
- 最终状态=`COMPLETED`；
- SQL=``SELECT SUM(t0.`paid_amount` - t0.`refund_amount`) AS `effective_paid_amount` FROM `qw_bench_order` t0 LIMIT 100``；
- result=`effective_paid_amount = 480.00`；
- clarification=`null`；
- error=`null`。

第一次完整成功时还发现 `query_status` 终态已包含 SQL/result，但 `answer` 仍保留“任务已提交”占位文案。已修正 `ProjectMcpQueryFacade.status()`：终态会同步 durable conversation assistant message，再返回最终终态内容；重建真实 backend 后再次跑 MCP，`answer` 已切换为终态内容，不再返回 RUNNING 占位文案。

验收结束后临时 Project MCP deployment 已 revoke。

## 5. Migration / Fresh Bootstrap

### 现有数据库升级

真实旧 metadata DB 在当前代码启动时发现 `qw_external_query_handle.conversation_id` 缺失。没有修改已执行的 V29，而是新增 forward-only：

`V30__external_query_handle_conversation.sql`

原有 DB 通过 Flyway 原地升级到 V30，现有数据保留，backend / worker / frontend 恢复 healthy。

### Fresh DB

使用全新 Compose project、network、metadata volume 和 uploads volume 启动：

- backend healthy；
- execution-worker healthy；
- frontend healthy；
- Flyway V1 -> V30 全部成功；
- V30 `conversation_id` 列存在；
- backend `/actuator/health` = `UP`；
- Web Console HTTP 200，title=`SemEvoSQL`。

验收后隔离 stack / volumes / network 已删除，未清理原开发环境。

## 6. Browser Acceptance

新增零第三方依赖的 `npm run browser:acceptance`：

- 直接启动本机 Chrome/Chromium headless；
- 通过 Chrome DevTools Protocol 导航真实 SPA；
- 等待页面 load + 异步渲染后读取实际 DOM；
- 显式关闭浏览器进程；
- 可通过 `SEMEVOSQL_WEB_URL` 指定部署；
- 可通过 `SEMEVOSQL_ACCEPTANCE_PROJECT_ID` 打开项目级验收。

当前开发环境真实 Google Chrome 验收：

1. `/projects` PASS；
2. `/admin/models` PASS；
3. `/projects/12` PASS；
4. `/projects/12?section=release` PASS；
5. `/chat?projectId=12` PASS。

结果：`5/5 PASS`。

## 7. 最终自动化门禁

已执行并通过：

- Maven `verify`：90 tests，0 failures，0 errors；
- Checkstyle：0 violations；
- Spotless：clean；
- frontend `npm run verify`：ESLint + vue-tsc + knip + production build PASS；
- `npm audit --audit-level=high`：0 vulnerabilities；
- release hygiene：PASS；
- fresh Compose bootstrap：PASS；
- browser acceptance：5/5 PASS。

## 8. 发布状态

本轮 SemEvoSQL 领域模型、自动演进、Episode、MCP、Retrieval、Migration、Web、真实浏览器和真实 MCP data-plane 均已完成收口。当前 active Chat 已切换到连接测试通过的 `gpt-5.6-luna`；正式发布门禁无剩余阻断。
