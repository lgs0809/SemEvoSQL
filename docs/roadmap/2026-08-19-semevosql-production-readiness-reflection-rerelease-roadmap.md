# SemEvoSQL 1.0.0 Production Readiness、全栈复盘与重新发布实施方案

日期：2026-08-19  
项目：SemEvoSQL  
工作区：当前 SemEvoSQL 仓库根目录  
目标分支：`main`

---

## 1. 本轮目标

本轮不是简单追加一个 1.0.1 补丁，而是把当前已经公开的 SemEvoSQL 1.0.0 当作“候选发布版本”重新做一遍生产化收口。

最终交付目标：

1. 修复当前已经确认的生产级问题；
2. 补齐 Web Console 的完整生产认证闭环；
3. 收紧 standalone / production 部署边界；
4. 加固 execution worker 与代码执行镜像；
5. 收敛主链错误模型，不再用裸 `IllegalStateException` / 500 表达可识别业务失败；
6. 扩展 CI，使 prod profile 配置/安全门禁、浏览器、数据库升级、发布卫生都成为正式门禁；
7. 在以上修复完成后，重新从产品经理、前端工程师、后端/架构工程师三个角度完整审视整个项目；
8. 对复盘发现的问题继续真实重构，而不是只写建议；
9. 重新做全量功能、真实模型、MCP、浏览器、数据库迁移、安全和发布验收；
10. 最后重新组织公开 Git History，要求每个 commit 都是可独立构建、可运行、可理解的产品版本；
11. 最终公开版本仍为 **SemEvoSQL v1.0.0**，重新覆盖 GitHub 上当前发布历史与标签；
12. 不保留“为了凑日期/凑数量”的 checkpoint commit；不允许周末 commit 日期。

本轮完成前，不把当前 GitHub `v1.0.0` 视为最终定稿。

---

# 2. 已确认的问题基线

以下问题已经通过当前真实代码或运行时验证确认，不需要新 Session 再花时间重新争论是否存在。

## 2.1 P0：`prod` profile 当前无法启动

`backend/src/main/resources/application-prod.yml` 存在重复顶层：

```yaml
semevosql:
  ...

semevosql:
  ...
```

真实运行最终 1.0.0 JAR：

```bash
java -jar backend/target/semevosql-backend.jar --spring.profiles.active=prod --server.port=0
```

Spring 在 ConfigData 阶段直接报：

```text
DuplicateKeyException: found duplicate key semevosql
```

因此当前 prod profile 是确定不可用，而不是潜在问题。

## 2.2 范围修正：1.0.0 不接入企业账户

用户已明确 SemEvoSQL 1.0.0 当前没有企业账户/统一身份源接入，因此本轮不得新增 Web Console OIDC、PKCE、登录页、token 生命周期或 logout，也不得把这类能力作为发布 blocker。

产品边界进一步收口为 `SELF_HOSTED_SINGLE_USER`：后端不再保留 OAuth2 Resource Server、Bearer JWT 或面向浏览器/项目成员的 RBAC 产品能力。内部 `EDITOR / REVIEWER / PUBLISHER / ADMIN` 仅允许作为语义变更状态机/治理动作的技术级别，不代表多用户账号权限。Web Console 的正式验收以本机单用户模式为准。

## 2.3 P1：standalone 默认可被错误暴露到公网

当前 Quick Start 方向是 `SELF_HOSTED_SINGLE_USER` 本地体验，不存在 security mode / development-mode 开关。

但 Compose 默认端口若绑定所有宿主机接口仍会造成错误暴露，因此必须默认 loopback：

```text
23000:8080
28065:8065
```

这会绑定所有宿主机接口。对于云服务器用户，容易出现“直接照 Quick Start 启动后暴露未认证 Console”的误用。

## 2.4 P1：execution worker 具备宿主机高权限

当前：

```yaml
execution-worker:
  user: "0:0"
  volumes:
    - /var/run/docker.sock:/var/run/docker.sock
```

虽然真正执行用户 Python 的子容器有 `network=none`、read-only root、低权限 UID、CPU/内存/PID 限制，但持有 Docker socket 的 worker 本身仍等价于高宿主机权限边界。

## 2.5 P1：代码执行镜像不可复现

当前默认：

```text
continuumio/anaconda3:latest
```

问题：

- `latest` 不可复现；
- 镜像巨大；
- 软件供应链面过宽；
- 后续依赖漂移可能导致同一个 SemEvoSQL 版本得到不同执行环境。

## 2.6 P1：主链存在可识别错误直接变成 500

`Nl2SqlServiceImpl` 在模型返回不符合期望 JSON 时仍：

```java
throw new IllegalStateException(jsonContent);
```

源码已有 TODO：

```text
目前异常接口直接返回500，未返回异常信息
```

而 `Nl2SqlService` 仍被 SQL 生成、Table Relation、Semantic Consistency、SQL Execute、多源执行等主链真实调用，因此不能当作遗留死代码忽略。

## 2.7 P1：CI 目前只覆盖 standalone 路径

现有 CI 已有：

- Maven verify；
- Checkstyle；
- Spotless；
- frontend lint/typecheck/unused/build；
- npm audit；
- release hygiene；
- Docker fresh-start smoke。

但缺：

- prod profile 配置加载 / fail-closed smoke；
- 自动浏览器验收；
- previous-release DB -> current DB 升级验证；
- 核心上下文 coverage gate。

这次 prod YAML duplicate key 未被 CI 捕获，就是现有门禁缺口的直接证据。

## 2.8 P2：DDD 仍是“新结构 + 旧骨架并存”

新核心已经出现明确的：

```text
domain
application
infrastructure
adapter
```

但整个后端仍长期并存：

```text
bo/
dto/
entity/
mapper/
service/
workflow/
```

并存在多个 50~80KB 级 Application/Service 类。

当前更准确的定位是：

> DDD-oriented modular monolith，而不是已经完全完成 bounded-context 重构。

这类问题不在第一阶段盲目大改，必须等生产问题修完并通过回归后，再进入第二阶段整体复盘和渐进重构。

## 2.9 已确认不存在的问题

已对公开 `main` 全部 12 个 commit 做过历史扫描，排除检测规则自身后：

```text
audited_commits=12
history_hygiene=PASS
```

没有发现：

- `<开发者主目录>`；
- `$LOCAL_PATH`；
- `$LOCAL_PATH`；
- 真实 `sk-*`；
- `api.0-0.pro`；
- OrbisOps；
- enterprise-knowledge-base；
- market 项目路径。

GitHub canonical 仓库 `git@github.com:lgs0809/SemEvoSQL.git` 已确认存在，`main` 指向当前发布 SHA。

---

# 3. 第一阶段：Production Readiness 修复

本阶段只处理已经确认或可直接验证的生产问题，不做大范围美化型重构。

## 3.1 修复 prod 配置并建立配置结构门禁

### 实施

合并 `application-prod.yml` 的两个 `semevosql:` 根节点，确保以下配置都在同一树中：

```yaml
semevosql:
  code-executor:
  sql-execution:
  mcp:
  security:
  operator:
```

同时检查所有：

```text
application*.yml
*.yaml
Docker Compose
GitHub Actions YAML
```

是否存在 duplicate key 风险。

### 新增自动测试

增加 Spring 配置加载测试：

1. `local` 能加载；
2. `standalone` 能加载；
3. `prod` 能成功解析 YAML；
4. prod 缺 issuer / encryption key / secure executor 等时，由 `ProductionConfigurationGuard` 给出预期 fail-closed 错误，而不是 YAML parser 失败；
5. prod 安全配置满足要求时能启动 ApplicationContext。

CI 必须执行这一组测试。

---

## 3.2 保持 Web Console 无企业账户模式

### 产品原则

SemEvoSQL 1.0.0 不新增账号体系、OIDC/PKCE、登录页或浏览器 token 生命周期。不要为了“production readiness”虚构当前产品不存在的企业身份能力。

### 实施

- 删除本轮误加的 Web OIDC/PKCE 前端代码、callback 路由、logout 与运行时 auth config；
- 删除 `web-client-id`、`web-scope`、JWT issuer、security-mode 等企业认证配置；
- 删除浏览器/项目成员层面的 Resource Server/JWT/RBAC 产品路径，HTTP Operator 固定为本机单用户治理上下文；
- Web Console 继续采用本机单用户模式，并通过 localhost-only 默认绑定控制暴露面；
- 若未来正式决定接入企业账户，再单独设计身份源、用户生命周期、登录 UX、RBAC 与部署契约，不混入当前版本。

### 验收

```text
Web Console 不出现登录/退出/企业账户配置
standalone 主流程真实 Chrome PASS
HTTP/项目访问不依赖浏览器账号、JWT 或项目成员 RBAC
仓库源码无 OIDC/PKCE/JWT Resource Server 产品路径残留
```

---

## 3.3 standalone 与 production 部署模式彻底分开

### standalone

目标：开箱即用，但默认只允许本机访问。

Compose 默认端口改成：

```yaml
127.0.0.1:${SEMEVOSQL_FRONTEND_PORT:-23000}:8080
127.0.0.1:${SEMEVOSQL_BACKEND_PORT:-28065}:8065
```

或提供：

```text
SEMEVOSQL_BIND_HOST=127.0.0.1
```

再由用户显式改成 `0.0.0.0`。

standalone 与 production-like 都保持同一 `SELF_HOSTED_SINGLE_USER` 身份边界；差别来自部署安全配置，而不是账号/认证模式切换。

README 必须明确：

> 默认只绑定 loopback。若需要远程访问，必须先提供可信外部访问层，再显式设置 `SEMEVOSQL_ALLOW_REMOTE_BIND=true`。

### production-like

提供清晰的 production env example：

```text
SPRING_PROFILE=prod
secret encryption key
metadata TLS / secure metadata credentials
execution worker secure config
MCP public base URL
SEMEVOSQL_BIND_HOST=127.0.0.1（默认）
```

`ProductionConfigurationGuard` 继续对 secret、worker、数据库等运行安全项 fail closed，但不再检查不存在的 JWT/security-mode。

---

## 3.4 Execution Worker 安全加固

本轮不要为了“完美沙箱”过度扩平台，但要把明显高风险边界做清楚。

### 最低要求

1. Worker 容器不再默认 `user: 0:0`，如 Docker socket 权限确实要求特定 GID，则使用专用用户 + docker group GID 映射；
2. 不授予 `privileged`；
3. worker root filesystem 尽可能 read-only；
4. 明确挂载路径；
5. worker 只接受 backend 内部 token 认证请求；
6. 请求体大小、代码大小、requirements 大小继续限流；
7. 子执行容器继续 `network=none`；
8. 子执行容器继续低权限 UID、只读 root、tmpfs、PID/CPU/memory limit；
9. 禁止用户传任意 Docker image；
10. 所有 image 必须来自配置白名单/固定 runner image。

### Docker Socket 边界

若当前版本仍必须使用 host docker socket：

- README / security docs 明确这是 trusted control-plane component；
- 不把 worker 对公网开放；
- CI 检查 worker 只存在内部网络入口。

后续 1.x 可再评估 rootless Docker / dedicated executor daemon，不要求本轮上 Kubernetes。

---

## 3.5 固定 SemEvoSQL Python Runner

新增独立 Dockerfile，例如：

```text
deploy/execution/Dockerfile
```

构建：

```text
semevosql/python-runner:1.0.0
```

原则：

- 固定 Python major/minor；
- 只包含产品真正需要的 numpy/pandas 等分析依赖；
- 不使用 `latest`；
- 尽可能固定依赖版本；
- 默认非 root；
- 无 shell daemon；
- 无额外网络工具；
- 执行时仍由 Worker 强制 network none/read-only 等约束。

Compose 默认使用该固定 image。

CI 构建 runner，并跑最小代码执行 smoke。

---

## 3.6 统一主链 Error Contract

不要继续让能分类的问题最终表现为：

```text
500 Internal Server Error
IllegalStateException
```

建立明确异常层次，例如：

```text
SemEvoSQLException
  ModelOutputInvalidException
  ModelUnavailableException
  SemanticClarificationRequiredException
  SemanticPlanningRejectedException
  QueryPolicyRejectedException
  QueryExecutionFailedException
```

对模型 JSON/结构化输出异常：

```text
模型响应
 -> structured parse
 -> repair/retry（有上限）
 -> 若仍失败，映射 MODEL_OUTPUT_INVALID
 -> 对用户返回可理解 message
 -> Run/Event 中保留技术 evidence
```

要求：

- 用户错误信息不泄露 raw provider response / secret；
- diagnosis 可以看到分类；
- audit/event 可以保留必要 trace；
- HTTP 状态码与业务错误码一致；
- MCP `query_status` 也返回同一个业务错误模型，而不是另一套字符串。

优先修正 `Nl2SqlServiceImpl` 两个裸 `IllegalStateException(jsonContent)`。

---

# 4. 第二阶段：修复完成后重新做一次全栈产品反思

第一阶段所有 P0/P1 通过回归后，才开始这一阶段。

本阶段不能只写分析报告。流程必须是：

```text
审视 -> 列问题 -> 定优先级 -> 对值得修的问题继续实现 -> 再验收
```

## 4.1 产品经理视角重新串完整生命周期

按真实用户任务，从头到尾走：

```text
首次启动
 -> 模型配置
 -> 数据源接入
 -> 新建 Project
 -> 初始化 Semantic Catalog
 -> Grill-Me / onboarding
 -> 校验
 -> 发布
 -> 问数
 -> 澄清
 -> 多轮追问
 -> 错误诊断
 -> 纠正
 -> Query Case
 -> Semantic Evolution
 -> Replay
 -> 版本发布/激活/回滚
 -> MCP 部署
 -> 外部 Agent query/query_status
 -> 审计/维护
```

重点审：

1. 是否存在重复入口；
2. 是否让用户理解内部对象，而不是业务目标；
3. 页面是否出现只有开发者才需要知道的字段；
4. Project / Version / Semantic Version / Corpus Revision 概念是否仍冲突；
5. 管理员/维护者/问数用户权限边界是否自然；
6. 异常状态有没有明确 next action；
7. 初始化和发布是否过长、过碎；
8. Evolution 是产品闭环还是后台调试器；
9. MCP 是真正“一键接入”还是只生成一堆参数；
10. 当前信息架构是否仍有可合并页面。

形成 P0/P1/P2 backlog，并直接实施 P0/P1 以及低风险高收益 P2。

## 4.2 前端工程师视角重新审视

不仅看文案，还看：

- layout 一致性；
- responsive；
- loading/empty/error/skeleton；
- button hierarchy；
- destructive action confirmation；
- form validation；
- focus/keyboard；
- table overflow；
- large JSON/code viewer；
- accessibility；
- toast 是否滥用；
- route state / refresh recovery；
- 401/403 UX；
- SSE reconnect；
- long-running job progress；
- mobile/tablet 最低可用性；
- icon 重复和语义；
- 中英文术语统一；
- 普通用户与管理员技术细节分层。

### 前端工程质量

重新检查：

- API client 是否集中；
- Axios interceptor 是否唯一；
- service 文件是否过大；
- View 是否过大；
- composable 边界；
- TypeScript interface 是否与后端 DTO 漂移；
- role capability 是否只由前端推断；
- `localStorage` 是否保存不合适的敏感数据；
- error contract 是否统一。

值得重构的直接做，不只留 TODO。

## 4.3 后端/架构工程师视角重新审视

### 主链边界

重新画真实调用链，而不是 README 架构图：

```text
HTTP/MCP
 -> conversation/episode
 -> retrieval
 -> blueprint/planner
 -> compiler/preflight
 -> sql execution
 -> review
 -> run/evidence
 -> learning/evolution
```

检查：

- 是否还存在双主链；
- legacy service 是否仍绕过新语义约束；
- `Nl2SqlService` 中哪些职责应下沉/拆出；
- workflow node 是否承担过多 domain logic；
- application service 是否变成 God Service；
- transaction boundary；
- blocking call on WebFlux；
- boundedElastic 是否滥用；
- DB repository 是否跨 context；
- domain 是否依赖 infrastructure DTO；
- Event/Run/Episode 的事实源是否唯一；
- Semantic Version 与旧 Project Version 是否还有重复抽象；
- legacy API compatibility 是否应继续保留。

### DDD 重构原则

不要一次性全项目搬目录。

按 bounded context 渐进：

```text
project
episode
semantic
evolution
query/run
external-mcp
model
connector
```

每个 context 优先形成：

```text
adapter
application
domain
infrastructure
```

旧：

```text
bo/dto/entity/mapper/service
```

只有在职责归属明确时才迁移。

禁止为了“目录看起来像 DDD”创建无意义 wrapper。

### God Service 拆分

重点检查目前体积明显偏大的类：

- `ProjectOnboardingApplicationService`；
- `ScenarioResolutionService`；
- `SemEvoSQLProductionService`；
- `SemanticReplayService`；
- `SemanticBlueprintGenerationService`；
- `RuntimeClarificationService`；
- `SemanticCatalogApplicationService`。

拆分标准不是行数，而是：

- 多个独立 use case；
- 多种 transaction boundary；
- 同时负责 orchestration + domain rules + persistence；
- 测试难以隔离。

## 4.4 删除真正无用代码

审视完成后做：

```text
unused class
unused endpoint
compatibility shim
old brand compatibility
legacy workflow branch
old DTO
old config
obsolete migration helper
```

但删除前必须做引用/impact search，并跑相关测试。

不要只因为命名旧就删仍在主链上的能力。

---

# 5. 第三阶段：质量门禁升级

## 5.1 CI 必须至少包含

### Backend

```text
mvn verify
checkstyle
spotless
core tests
prod profile config test
```

### Frontend

```text
npm ci
lint
typecheck
unused
build
npm audit
```

### Docker

```text
Compose config
fresh metadata DB
backend
frontend
execution worker
python runner
```

### Browser Smoke

最少自动化：

```text
/projects
/admin/models
```

如果 CI demo project 能稳定初始化，则扩为 7 条：

```text
/projects
/admin/models
/projects/:id
/projects/:id?section=release
/projects/:id?section=external
/projects/:id?section=evolution
/chat?projectId=:id
```

### Production Profile

CI 必须显式解析 prod profile，并执行 ProductionConfigurationGuard / YAML 配置门禁；当前没有真实企业身份源时不伪造 Web 登录环境。

允许因缺真实 JWT issuer/DB 而在 `ProductionConfigurationGuard` 按预期 fail closed，但不允许：

- YAML parse fail；
- bean definition fail；
- duplicate config key；
- missing class；
- circular dependency。

若本轮没有可用的真实 JWT issuer，不为测试临时引入 OIDC/企业账户；prod 验收以 YAML/Context 可加载、ProductionConfigurationGuard 正确 fail-closed 和 standalone 全链路可用为准。

## 5.2 Migration Upgrade Matrix

从本轮重新发布开始保存 release baseline DB fixture / migration baseline。

至少验证：

```text
fresh -> latest
v1.0.0 baseline -> latest
```

以后每个 minor：

```text
latest previous minor -> current
```

Flyway：

- validate；
- checksum；
- no out-of-order；
- no destructive migration without explicit design。

## 5.3 Coverage

可以引入 JaCoCo，但不要用一个全项目 80% KPI 驱动无意义测试。

优先对核心 context 做 branch coverage 观察/门禁：

- semantic version；
- episode；
- evolution release；
- retrieval；
- SQL preflight/guard；
- MCP；
- security；
- migration-sensitive services。

先建立报告，再决定合理阈值。

## 5.4 GitHub 开源治理

补齐：

```text
SECURITY.md
CONTRIBUTING.md
.github/dependabot.yml
issue templates
pull request template
```

可选：

```text
CODEOWNERS
Dependency Review Action
OWASP Dependency Check
```

不要为了“看起来企业级”引入大量没人维护的模板。

---

# 6. 第四阶段：完整真实验收

只有代码和产品重构全部结束后再做最终验收。

## 6.1 静态/构建验收

必须通过：

```text
./mvnw -s .github/maven-settings.xml verify
frontend npm run verify
npm audit
release hygiene
Docker build
Compose config
```

## 6.2 数据库

验证：

```text
fresh V1 -> latest
existing current DB -> latest
release baseline fixture -> latest
```

## 6.3 standalone

重新从空环境：

```text
git clone
init-deployment-env
start-semevosql
```

不复用开发机器已有数据库/volume 才算 fresh acceptance。

验证：

- metadata DB；
- backend；
- worker；
- frontend；
- model config；
- datasource；
- project；
- initialize；
- publish；
- query。

## 6.4 production-like / prod

至少验证一次真实 `prod` profile 的配置加载与安全门禁：

- `SELF_HOSTED_SINGLE_USER` 身份边界保持不变；
- 默认 loopback bind，非 loopback 必须显式 opt-in；
- secure metadata DB config；
- execution internal token；
- encryption key；
- execution worker hardened config；
- MCP credential path。

当前没有企业账户/身份源，因此验收不得引入 JWT/OIDC/RBAC 模式。production-like 的含义是更严格的部署、凭据和执行隔离，而不是切换到另一套账号系统。

## 6.5 真实模型

用实际可用 Chat + Embedding + Rerank 做轻量但完整 smoke。

无需大规模烧 token，但至少：

- 简单聚合；
- 时间范围；
- 枚举过滤；
- 多表/复杂查询；
- 澄清；
- correction；
- post review；
- semantic retrieval；
- evolution patch/replay。

外部模型偶发波动可以重试，但必须区分 provider outage 与产品 bug。

## 6.6 MCP

真实协议验证：

```text
tools/list -> exactly query + query_status
query -> RUNNING
same requestId -> idempotent same Episode/Run
query_status -> terminal
restart recovery
credential rotate/revoke
semantic version change -> no MCP redeploy
old Episode pinned
new Episode current Active
```

## 6.7 Browser

真实 Chrome，不只 jsdom。

覆盖：

- standalone 7 条主路由；
- auth-enabled 登录跳转；
- VIEWER；
- EDITOR/REVIEWER；
- ADMIN；
- 401；
- 403；
- refresh/recovery；
- durable run reconnect。

## 6.8 Security

重新扫描：

- hardcoded secret；
- local absolute paths；
- private provider；
- old project dependency；
- security disabled production config；
- exposed Docker socket documentation/boundary；
- default public bind；
- plaintext datasource/model credentials；
- logs 是否泄露 key/token/raw secret。

---

# 7. 第五阶段：修完后重新做最终产品/前端/后端反思

即使第四阶段全部 PASS，也必须再做一次最后复盘，而不是直接发版。

这是为了避免“测试都绿，但产品/架构仍然别扭”。

输出一个 final review，至少包含：

```text
产品：是否还有用户无法理解/不自然的流程？
前端：是否还有内部工具感、权限错位、错误状态差？
后端：是否还有双模型、双状态源、明显 God Service、绕约束主链？
部署：clone 后是否真的独立？
开源：README 与真实产品是否一致？
```

原则：

- 若发现 P0/P1，继续修，不能带着问题去重构 Git History；
- P2 只修高收益、低风险、明确影响维护性的项；
- 不为了“零问题”无限延长 1.0.0。

只有 final review 结论为“无 release blocker”才能进入 Git History 阶段。

---

# 8. Git History 重新构造要求

这是最后一步，严禁在功能尚未收口时提前做。

## 8.1 版本

最终公开版本：

```text
SemEvoSQL v1.0.0
```

不要在最终公开历史里留下 1.0.1 hotfix 痕迹；本轮是对 1.0.0 发布历史重新定稿。

## 8.2 日期

继续沿用之前约定的三周工作日窗口：

```text
2026-07-13 ~ 2026-07-31
```

规则：

- 只允许周一至周五；
- 周末 0 commit；
- 不要求每个工作日都有 commit；
- 不为了“每天多个”硬凑；
- 日期分布要符合真实开发演进逻辑。

## 8.3 Commit 质量

每一个最终公开 commit 必须同时满足：

1. 非空；
2. 是真实功能/架构里程碑；
3. message 能解释该阶段产品变化；
4. Maven 至少可构建；
5. 前端至少可构建；
6. 关键阶段最好测试也 PASS；
7. checkout 该 commit 后不是半成品；
8. 不存在只改 checkpoint 文档来凑提交数量的 commit。

如果两个原始变更单独 checkout 不可用，就合并成一个版本。

提交数量由真实产品阶段决定，不设固定 12/15/30。

## 8.4 推荐演进主题

最终历史可以大致体现：

```text
1. project foundation / data source / basic NL2SQL
2. semantic catalog + retrieval
3. semantic blueprint + verified SQL
4. durable run + clarification
5. query learning + diagnosis
6. SQL safety + preflight
7. independent deployment + model configuration + rerank
8. productized Web Console
9. Episode + Semantic Version + Evolution
10. MCP integration
11. production security + auth + release gates
12. final product/architecture refinement
13. release 1.0.0
```

但不要机械照这个数量，一切以最终真实代码边界为准。

## 8.5 历史卫生

重写完成后扫描每个 commit：

```text
<开发者主目录模式>
$LOCAL_PATH
$LOCAL_PATH
sk-*
private API host
other project path/name
plaintext password/token
```

并排除 hygiene script 自身的正则自引用误报。

## 8.6 标签

最终：

```text
v1.0.0 -> final release commit
```

标签与 `main` final release SHA 一致。

---

# 9. GitHub 重新推送

最终验收与 history verification 通过后：

1. 确认 canonical remote：

```text
git@github.com:lgs0809/SemEvoSQL.git
```

2. fetch 远端；
3. 记录远端旧 SHA；
4. 用 `--force-with-lease` 更新 `main`，禁止裸 `--force`；
5. 重建并更新 `v1.0.0` tag；
6. push tag；
7. 重新 `ls-remote` 验证 GitHub 上：

```text
main == final SHA
v1.0.0 == final SHA
```

8. 检查 GitHub Actions；
9. 检查 README badge / clone URL / release URL；
10. 检查 GitHub 页面最终展示。

如 remote 仍使用 QueryWeaver redirect，应把本地 origin 正式改成 canonical SemEvoSQL URL。

---

# 10. 实施顺序（强制）

必须按以下顺序，避免重复返工：

```text
A. 确认 workspace/git/runtime

B. Production Readiness
   B1 prod YAML
   B2 keep Web Console account-free; no OIDC/PKCE
   B3 standalone localhost-only
   B4 worker security
   B5 fixed Python runner
   B6 error contract

C. Production 回归
   Maven/frontend/Docker/prod/browser/MCP

D. 全栈重新反思
   产品
   前端
   后端/DDD

E. 按反思结果继续重构

F. CI/quality/release gates 补齐

G. 最终完整验收
   fresh standalone
   prod
   DB migration
   real model
   MCP
   Chrome
   security

H. 再做一次最终产品/前端/后端 review
   有 blocker -> 回 E/F/G
   无 blocker -> I

I. Git History 重构

J. 对每个最终 commit 做构建验证 + history hygiene

K. v1.0.0 tag

L. force-with-lease 重新推送 GitHub

M. 远端 SHA + GitHub Actions + README 最终验证
```

---

# 11. 不做的事情

本轮不要因为“生产化”扩成外围平台项目：

- 不引入 Kubernetes；
- 不引入 Kafka；
- 不引入复杂 service mesh；
- 不做异地灾备；
- 不做自研 IAM；
- 不做大型多租户计费；
- 不重写成微服务；
- 不为了 DDD 全目录搬迁；
- 不为了 coverage 数字写无价值测试；
- 不恢复已废弃的 Typed Semantic Plan / Semantic Query Plan / old Dry Plan；
- 不取消 Rerank；
- 不取消 RRF；
- 不重新引入 Live Semantic Overlay 或 EvoSQL 16x3 candidate search。

---

# 12. 最终 Definition of Done

本轮只有同时满足以下条件才算完成：

## 产品

- standalone 新用户可独立完成从启动到第一次问数；
- Web Console 不引入当前产品不存在的企业账户/OIDC 登录；
- 普通用户不面对无必要的实现细节；
- 管理员/治理人员保留必要 SQL、版本、风险、回归、审计能力；
- MCP 真正 project-scoped、两工具、可恢复、可轮换凭据。

## 后端

- prod profile YAML/Context 配置可解析，缺真实外部 issuer 时按设计 fail-closed；
- security fail closed；
- SQL safety 不被 prod 配置覆盖/漏加载；
- 模型结构化输出失败有明确业务错误；
- Episode/Semantic Version/Evolution/MCP 语义一致；
- 无明显绕过新架构的 release blocker。

## 前端

- 不存在本轮误加的 OIDC/PKCE/login/logout 残留；
- 角色可见性与现有 operator-context 逻辑不回归；
- 服务端错误文案仍能稳定呈现；
- 关键路由真实 Chrome PASS；
- durable reconnect PASS。

## 部署

- standalone 默认 localhost-only；
- production 明确安全配置；
- worker 安全边界清晰；
- runner image 固定版本；
- fresh clone 可独立启动；
- 不依赖开发者机器/其他项目。

## 测试

- Maven verify PASS；
- frontend verify PASS；
- npm audit PASS；
- release hygiene PASS；
- prod profile 配置/安全门禁 PASS；
- fresh DB PASS；
- upgrade DB PASS；
- real model smoke PASS；
- MCP acceptance PASS；
- Chrome acceptance PASS。

## Git / Release

- 最终公开历史只含有意义且可用的产品版本；
- 2026-07-13 ~ 2026-07-31，仅工作日；
- history hygiene PASS；
- final version `1.0.0`；
- `main` 与 `v1.0.0` 指向最终 release commit；
- GitHub Actions PASS；
- canonical repository 为 `lgs0809/SemEvoSQL`。

达到以上标准后，SemEvoSQL 1.0.0 才算真正最终定稿。
