# 当前交付状态与暂停点

## 可移植启动修复（2026-10-04）

空账户默认配置曾在云端 Docker 启动时触发 Spring 集合构造器的非空限制。现仅在单用户模式空注册表时使用框架原生 Map 构造器，不生成默认登录账户；开启认证却缺账户或使用明文密码仍拒绝启动。8 项真实 WebFlux 安全回归全部通过，原 2 项启动失败保留。完整 PostgreSQL / MySQL 回归为 1000/1000 通过、168 份新 XML、0 失败/错误/跳过；旧 full16 的 996/996 证据独立保留。

不可变新 JAR `36ce330f4b445c5efc31f3035068bbe50c41918aa247b65837f91c8925396952` 已正常部署到既有验收后端。全新隔离 PostgreSQL 上的默认普通启动与身份解析 6 项通过，既有账户环境的登录、角色、私有会话及 Web 代理 12 项实际检查通过。13 个业务表的内容哈希保持，Web18、执行 worker、数据库容器与账户配置未重建或修改；本轮未启动模型请求、Run 或正式评测。

首次部署报告的环境数组哈希比较失败保留：旧快照只有数组哈希，无法逐键核对旧环境，也不能断言仅由顺序变化导致。独立新报告验证当前 37 个变量与原字节保留的 Compose 私密配置和实际镜像默认值逐键一致，旧/新应用默认配置逐字一致。该报告与新权限、数据、产物读回共同支持当前部署验收，不回写首次失败。

Docker 构建补齐共享语义协议 schema 子目录，CI 对正常容器 JAR 的 4 个 schema 逐字核对源码，并验证后端与执行 worker 镜像相同。7 个制品校验正反例及新 JAR 的实际 schema 读取通过。原云端启动失败保留；修复后的可移植 Docker 全流程仍以最终主分支 Actions 为准，不把本机 JAR 启动称作云端 Docker 通过。

本轮证据入口：主工作区 `work/product-release-20261003/semevosql-security-empty-registry-1`，包含原云端日志引用、red/green XML、完整回归、不可变 JAR、默认启动、实际环境 map、权限 API/SQL、旧 JAR/镜像恢复入口及最终源码绑定。

## 发布依赖收尾（2026-10-03）

最终本地 Web18 已于 16:17 UTC 正常部署。GitHub round2 的跨平台锁缺项已在 Node 22.12 / npm 10.9 隔离复现；使用受支持的 Node 22.13 / npm 10.9.2 更新锁并完整验证：36/36 回归、lint、类型、knip、构建及审计 0 漏洞。npm 10/11 严格空目录安装通过，原共同依赖版本与 integrity 未变。72 个实际资产及八项原运行身份绑定保持，后端、worker、数据库和模型未重启。

CI 与文档统一 Node 22.13 最低版本；push 仅 main，保留 PR 与手动执行，按 workflow/ref 取消被新提交替代的在途任务，并保存失败诊断。原六项高危失败及 round2 锁失败均保留；最终 Ubuntu CI 结果以新主分支 Actions 为准，不把本地安装称作云端通过。

本地 Web17 已正常部署，仅更新前端依赖与 ESLint flat config：Axios 1.20.0、brace-expansion 5.0.12，移除尚无补丁的 braces 传递依赖链。`npm ci`、完整 `npm run verify`（36/36 回归、lint、类型、knip、构建）和 `npm audit --audit-level=high` 均通过，完整审计 0 漏洞。111 个旧/新完整有效 ESLint 配置逐项一致，未放宽规则或豁免审计。72 个实际资产匹配，API 代理通过；后端、worker 和数据容器未重启。

原 GitHub CI 的六项高危失败保留；修复后的云端执行以仓库 Actions 对应提交为准。此次依赖修复没有启动新模型查询、改动业务记录或重算正式评测。


更新时间：2026-10-04。用户已暂停剩余功能补全和大规模正式评测，当前仅推进页面、结构及常规发布缺陷修复。旧失败、Gold、数据库和发布版本完整保留。本文是便于以后继续的入口；逐批原始记录在 [实际验收记录](2026-10-02-fresh-initialization-and-frozen-evaluation.md)。

## 当前环境

- 页面：`http://127.0.0.1:3303/semevosql/`；API：`http://127.0.0.1:18093`。仅本地隔离环境。
- 当前后端 immutable release：`36ce330f4b445c5efc31f3035068bbe50c41918aa247b65837f91c8925396952`，1000/1000 检查通过、168 fresh XML，并已部署。旧 full16 `b4e7ccf86c1777c975a0b5ab61ffe6253e83ba797e58b32848794fa911033a6f` 的 996/996、167 XML 保留；均不等同业务全验收。
- 当前前端 web18：36/36 自动检查及 lint、vue-tsc、knip、Vite 通过，审计 0 漏洞；72 个当前静态文件实际部署一致，本轮未重建前端。页面验收边界见 [UX 发布检查](2026-10-03-frontend-ux.md)。
- 项目4正式 v15／页面 v1.2.0：6 个全文语义文档及1024维真实向量就绪，正常管理员审批后发布并激活，job274 DONE attempt9。
- 大小模型固定 `gpt-5.6-luna`／`gpt-5.6-terra`，HTTP/逻辑/Run预算未改变。共享Qwen由共享服务负责人管理。
- 发布前编码的47条历史 embedding 没有可信原子证明，保留 NULL，精确缓存必须 MISS，不补历史 revision 标签。

## 已实现、真实失败、尚未验收

这些编号存在交叉，不可相加为独立功能数。部分已有真实流程来自 earlier immutable JAR，在原记录中分别绑定；不能把旧流程称作最新产物全部重测。

| 编号 | 已有实现与真实证据 | 保留的问题或暂停的验收 |
| --- | --- | --- |
| SQL-00 / A | Codex+Skill 初始化协议、严格导入、物理绑定、版本校验、完整索引、正常发布激活 | 初始化整体问数质量仍未达到完整正式验证 |
| SQL-01 / B | PG/MySQL只读、成本、超时保护及两条SQL合法性；有正常审批执行案例 | 当前分页、多源完整模型流程未测完；公共口径选择4次实际超时无回答 |
| SQL-02 / G | 四类修复各2次持久预算、最多5次逻辑HTTP、绝对deadline、取消/恢复组件 | 最新部署完整故障矩阵与多次重审耗尽用户流未完成 |
| SQL-03 / C / D | 框架PostgresSaver、真实进程恢复、HITL快照、前端幂等和作用域保护 | 新页面验收只验证展示和导航，不重跑模型恢复矩阵 |
| SQL-04 / E | 正常问询、多轮换月/换题、修订/旧问题栅栏、审批纠正、CAS | 旧目标/歧义/上下文压缩完整用户流暂停 |
| SQL-04 / F | 个人完整定义、范围修订、历史撤回、独立用户确认、三人五次真实贡献、管理员审核及公共发布 | 显式选公共答案失败；兼容覆盖→个人更新提醒完整流程尚未验收 |
| SQL-04 / G | 真实检索/完整1024向量、学习冻结、当前可信原子缓存入口和严格项目/源/profile栅栏 | 重排有真实503/超时及回退，不能算全部质量通过；两版正常真实缓存复用未测 |
| SQL-05 | 冻结合成300题、15相关题族、SQL真值，计划700次；34条已终态 | 原8PASS/24FAIL/2ERROR；同Run独立v3为12PASS/20FAIL/2ERROR；666未入场 |
| SQL-05 值域 | importer/单位和范围组件已实现并验证 | 实际API/页面数值边界矩阵未完成 |
| D09 规则 | 现有全局 requireTimeFilter 保持 | 静态表时间门禁业务选择待用户答复，未擅自关闭 |

最新普通公共选择Run `24ecef63-5444-40eb-be51-cb6fc1cb627f` 在 b4e7 / web12 上自然FAILED（13:16:22–13:21:24UTC），7检查点，0问询/SQL/结果。实际5次HTTP，其中两个规划逻辑2次+1次HTTP；1次持久恢复后300秒期限停止，不是穷尽5次重试。私人两修订/两结构/六授权/五使用逐行不变属于状态保护通过，不属于业务答案通过。前三个失败也保留。

## 恢复后工作的准确入口

八项暂停：修复/取消故障矩阵；公共选择实际回答；兼容公共覆盖和私人更新提醒；分页/多源；上下文旧目标/歧义/压缩；数值范围页面API；余666正式执行；未来可信缓存两版正常发布复用。当前不启动CLONE、模型、缓存发布试验、700或正式矩阵。

复评分只读，同一Run、0新模型执行；原评分与校正评分并列，Gold原字节不改。合成15相关家族不能称外部生产基准或300独立模板。

可重跑入口在 `scripts/`：`local-acceptance.py`（受管服务）、`test-backend-with-databases.py`（完整数据库回归）、`deploy-tested-web-acceptance.py`（前端独立部署并保留旧资源）、`inspect-native-recovery.py`、`inspect-personal-definition.py`（只读记录）；前端 `npm run verify`。初始化入口见 [初始化部署](../../deploy/acceptance/catalog-initialization.md)。不要重建真实数据或删除容器卷。

本次机器证据目录：`work/semevosql-finish-20261002`（主Codex任务workspace内，未把日志/凭据打入源码）。重点文件：`task-status-user-pause-ux-scope-1.json`、`member-b-public-choice-terminal-deploy13-1.json/.sql/.png`、`member-b-public-choice-failure-diagnosis-deploy13-1.json`、`backend-full16-final-artifact-binding-1.json`、`deploy-13.json`。

前端候选证据：`frontend-ux-final-web16-1.json`、`frontend-source-binding-16-predeploy-1.json`、`frontend-deploy-16.json`、`ux-web16-runtime-final-1.json/.sql`。数据库只读核对8/8通过：后端容器身份/出生时间/PID/卷及JAR保持，原失败、公共发布DONE9和6条当前1024维向量保持，暂停后没有新增Run。业务规则确认页对当前发布版的记录读取仍返回400，页面显示错误；本轮没有启动初始化来掩盖该问题。
