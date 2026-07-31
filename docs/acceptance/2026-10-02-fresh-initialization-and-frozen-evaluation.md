# 2026-10-02～03 独立初始化与冻结评测验收（进行中）

本记录只登记本轮实际执行。既有报告不替代当前部署验证，真实执行时间不改写为 Git timeline 的七月日期。

## 环境与边界

- 本机页面：<http://127.0.0.1:3303/semevosql/>；API：<http://127.0.0.1:18093>。
- 普通新项目：`4 / v12`，业务库 `semevosql_quality_finish_20261002`。
- 冻结新项目：`5 / v13`，业务库 `semevosql_quality_benchmark_20261002`。
- 两库均是可计算的合成商业样本，复用既有固定种子算法，在不同物理库与学习作用域运行；不是外部生产样本。
- 生成模型固定 `gpt-5.6-luna` / `gpt-5.6-terra`。Qwen 完整业务模型文档与 1024 维索引均保持原合同，不截断输入或人工置为完成。
- 证据根目录：`$LOCAL_EVIDENCE_ROOT/work/semevosql-finish-20261002`。

## 已完成的本轮事实

`backend-package-4.log` 完整执行 924 项后端测试，零失败、错误和跳过；包括真实 PostgreSQL/MySQL、原生 Saver、修订竞争、模型传输合同、SQL 30 秒超时取消与连接复用。随后 `backend-http-index-1.log` 执行安全异常处理类 6 项测试，包含新增实际 WebFlux HTTP 处理器选择验证；这不是另一轮 925 项全量测试。`web-verify-4.log` 前端 25 项测试、lint、类型检查、未用代码检查和构建通过。`scripts-tests-6.log` 17 项 Python 测试通过；Skill 独立虚拟环境测试 10 项通过。测试计数取本次日志，不汇总残留 Surefire XML。

`deploy-3.json` 核对运行 JAR SHA256 `b972a199a94846e6e31cee25e3edb4b8ce4f1bca9d4e6b977df1fd3248a2f59c`、本地 JAR 与全量测试日志绑定一致，68 个前端文件一致，健康状态 UP。短暂暂停本项目后端进行资源协调后，同一容器已恢复；`backend-restored-readiness-1.json` 再次核对同一运行 JAR 及项目 4 的真实查询就绪状态。

2026-10-03 恢复工作时，`backend-package-5.log` 无最终 Maven 汇总或 BUILD SUCCESS，只有 74 个已完成测试类、430 个测试的局部日志，原 runner 已不在运行。新增索引调度 PG 测试 7 项已完成，但残留 Surefire XML 不能证明整轮通过；`backend-results-5-incomplete.json` 单独保留中断事实。本人后端在共享 Qwen 空闲、无活跃 Run／索引工作时正常暂停，容器／镜像／挂载全部保留；这不是在途 Run 故障恢复测试。

两次正常种子初始化均通过 7 项检查，并保持业务关联、边界记录、只读角色与幂等；项目创建和数据源连接走正常 API。`deployed-accounts-2.json` 在当前部署通过 11 项真实 HTTP 权限检查，并交叉核对两名成员的数据库会话归属。

`deployed-accounts-3.json` 在 b972a199 部署重复通过同样 11 项真实权限检查。2026-10-03 两个隔离 fixture 正常重跑，新增统一五表 ANALYZE 维护 SQL 后各通过 8 项检查，统计行数均与实际五表一致、种子及业务数据 Hash 不变（`fresh-seed-statistics-1.json`、`benchmark/seed-statistics-1.json`）；没有修改语义资产、冻结题目或门禁阈值。

普通项目 4 的结构导出指纹为 `sha256:14aac3234130079874aa8d61d9e37afd1070ab36384791862eeac5a4998289ea`。Codex＋Skill 基于实际元信息和明确合成业务材料形成严格 1.2 包：6 模型、11 展开指标、6 维度、5 关系；同一订单表的全部状态与付款记录两个模型独立过滤。共享定义与公开字段映射经过 Python 校验和真实 Java 预览。受控缺失物理字段的负例确实被拒绝，正确原文件重新校验通过。

本轮 Computer Use 实际选择文件、检查预览、确认导入项目 4 草稿，并点击验证。图片为 `import-preview.png`、`import-committed.png`。结构导出的浏览器 Blob 下载等待曾超时，实际结构文件由正常 API 获得；不登记为浏览器下载成功。

## 索引故障、恢复与正常发布

`initialization-status-2.json/.sql` 保存当时项目 4 草稿已导入且分析完成、完整索引 5/6 的真实受阻状态；`paid_trade_orders` 第 9 次进入 RETRY，当时没有发布或活动版本。旧状态保留，不改写为成功。

第 9 次完整文档推理由后端记录 180.25 秒超时，Qwen 在 `2026-10-02T13:23:41Z` 记录 180 秒资源期限并自动回收。`qwen-retry-9-resources.jsonl` 显示计算线程 CPU 时间持续增长，同时有大量文件页回收与缺页；不能将其误述为始终阻塞在准入锁，也不能仅凭资源现象断言唯一根因。文档实际 1970 token，未截断，远低于 tokenizer 限额。

主智能体协调部署既有 Qwen 服务的加载前 CPU 线程预算，保留旧容器、同一模型缓存卷、权重、输入、维度与 180 秒期限；23 项服务兼容测试通过。`qwen-cpu-deployment-2.json` 实际 readiness 报告 intraOp=2 / interOp=1。同一输入仍为 7208 字符，SHA256 `6dc7d1854bffe99afc0eb53c283ad409dd6061710749c48784e2fe896f166a7c`。第 10 次正常文档重试仍超时；`embedding-model-retry10.log` 保留服务真实期限记录。名为 `semevosql-acceptance-backend-1-retry10.log` 的文件实际采集于新版容器部署之后，只有新版启动信息，不能作为旧后端超时日志。

随后主智能体可逆暂停其未使用的旧 EvoKB 服务释放内存，既有容器、卷和恢复命令保留；Qwen 仍为 2/1 线程。通过正常管理员维护 API 请求 revision 2，完整输入首次尝试仍超时，第二次在 `2026-10-02T14:04:05Z` 得到真实 `/v1/embeddings` HTTP 200。`index-maintenance-completed-1.json/.sql` 核对 6/6 当前内容哈希与 1024 维向量一致，维护工作 DONE、attempt=2，真实向量落库时间为 14:04:05.783113；维护覆盖共 27 个文档，但只对过期文档实际调用模型。文档工作仍处于第 10 次 RETRY，等待其原有下一次正常调度确认，未用 SQL 修改状态。CPU 预算、释放内存和缓存变化同时存在，不能从这两次结果断言单一根因。

资源采样 `qwen-maintenance-2-resources.jsonl` 覆盖第一轮及第二轮前段；`qwen-maintenance-2-attempt2-resources.jsonl` 开始于成功之后，只代表恢复后空闲状态。原始服务日志 `qwen-maintenance-2-log-2.log` 同时保存首次失败与第二次成功。

浏览器发现索引未准备好被全局 IllegalState 处理器泛化成 INVALID_STATE，明确进度只存在服务端。修复增加安全的类型化 409 `SEMANTIC_INDEX_NOT_READY` 和对象准备计数；技术故障详情仍留服务日志。新部署实际 HTTP 返回该错误码及 5/6 进度，浏览器保留对应提示（`validation-api-3.json`、`index-pending-typed-deployed.png`）；门禁未改变。恢复后在真实浏览器点击“验证业务模型”“发布业务模型”，正常状态依次变为已验证、已发布并自动激活；`fresh-published-active.png` 及 `backend-restored-readiness-1.json` 证明项目 4/v12 queryReady=true。业务 Run 质量仍需另验。

## 首条真实问数与一般框架修复

普通新项目第一条 CUA 问题为“2026年2月，每个商品分类卖出了多少件商品？按订单下单时间统计，包含全部订单状态，按商品分类名称排序。”，不属于冻结 15 族。模型没有历史案例，形成正确三对象计划，并在页面正常批准；独立 SQL 真值为五个分类的 536／507／441／469／534 件。Run `a18e4349-969a-4167-9e97-c79c8259cc07` 有 14 个真实 native checkpoint，EXPLAIN 回执成功，但 source sub-run 因全扫描估算 5322 > 5000 被门禁拒绝，进入原有有界 SQL 调整。随后 Luna 流式调用遇到 ReadTimeout，原生恢复一次后被绝对执行期限正常终止，最终 FAILED／INTERACTIVE_QUERY_TIMEOUT，零 QUERY 回执、结果工件和学习案例。`fresh-quantity-run-1.json/.sql`、`fresh-quantity-source-runs-1.json` 与失败截图／后端日志完整保留；不把正确计划或 EXPLAIN 当作问数成功。

产品实际 50 行而当时 PG 估算 700 行，种子初始化遗漏统计刷新。统一 ANALYZE 后，同一编译 SQL 的实际只读 EXPLAIN 全扫描估算为 4672；门禁仍为 5000，业务数据不变。`fresh-quantity-statistics-explain-1.json/.sql` 是维护后的计划检查，不是 Run 成功回执；新部署仍须用正常查询重新执行。

冻结项目首轮长文档超时之后，旧 `SemanticIndexWorker.processOne()` 仍返回继续处理，短暂远端不可用窗口内领取其他五文档，形成真实 503 级联。修复只在编码暂不可用时结束本轮 drain，保留未领取 PENDING、退避和租约栅栏，后续扫描继续。真实 PG 新增测试证明首次失败不消费其他文档的尝试数，下一扫描成功可继续处理；`backend-package-5.log` 的 7 项 PG 测试通过，完整新回归／部署仍待完成。

按用户确认，每次逻辑模型调用网络预算最多 5 个实际 HTTP 请求。Gateway 默认 4 次重试和旧 stream 建连 4 次重试共用 `ModelNetworkRetry`／`ModelTransportBudget`；Gateway 上下文关闭内层连接重试，SDK maxAttempts=1、Netty 自动重试关闭，选项降级也消费同一预算。调用者及 Run 绝对期限可提前结束，已发出的半段 stream 不在同一流中拼接重试。Run 原生恢复上限 2 与网络 HTTP 次数分别记账；不存在“所有 Run 共 2 次 HTTP”或“每次一定尝试满 5 次”的结论。

`backend-network-focused-1.log/.json` 中 34 项实际 loopback HTTP／SDK／取消／usage 测试通过，包含第 5 次恢复、5 次耗尽无迟到请求、网关不与 stream 重试相乘、期限不足 5 次即停止、半段不拼接和 blocking SDK 同一预算。该结果只证明传输合同，不冒充外部 Luna 质量。`scripts-tests-7.log` 18 项脚本测试通过，包含实际 readiness 接口的 `activeVersion.semanticVersionId` 合同。

2026-10-03 本地 06:22:12，`backend-package-6.log` 真正完整结束：932 项测试、160 个测试类，零失败、错误和跳过，BUILD SUCCESS／runner exit 0。`backend-results-6.json` 将本轮每个日志测试类与对应 XML 核对，没有合计残留报告。测试日志 SHA256 为 `343a0003a69aedb08cd3c1f031ea1aaffeff3fce95002ee75e548a2deccfbafc`，新 JAR 为 `91972747ac4086c0f4490d796684026766da23ec10fd858254e516281a4be971`。

`deploy-4.json` 证明该新 JAR 已运行、健康 UP，68 个前端文件与原已验证构建一致；`deployed-accounts-4.json` 在新部署通过 11 项真实 HTTP 权限检查。`scripts-tests-8.log` 19 项脚本测试通过。冻结 runner 统一按真实 Run 合同识别 EXPIRED 终态，异常清理先检查状态，保留 source sub-run 的实际成本及执行记录。

新部署通过正常扫描继续冻结索引。原有 7208 字符的付款记录模型输入 SHA256 `8574a7f1dc6ada869e9ff415389d7c2b057c4bfe9f822e08949335bdcb604793` 未改变，第 7 次正常尝试在 `2026-10-02T22:26:04Z` 成功，真实 1024 维向量落库，历史失败保留。`benchmark/index-new-worker-status-2.json/.sql` 保留完整文档身份与实际状态；截至 status-3 仍是 5/6 完成，最后 8429 字符订单模型正在处理，不能提前宣称索引全部成功。后台索引占用共享 Qwen 时，正常 readiness 探针实际遭遇 503；前端阻止新查询并保留历史记录，须等依赖恢复后正常重新验证。

## 冻结评测

`benchmark/frozen-300-v1.json` 已冻结 300 唯一 ID/问题、200 开发题和 100 留出题；题目族隔离为 10 开发族／5 留出族，每族 20 个相关参数变体。20 题要求未知净收入定义先澄清，再通过自然语言、QUERY 范围作答。真值来自实际只读业务 SQL。

冻结 manifest SHA256：`bc593f71b133a865659390b2fcd7ed1fdcad12f3d7757dc2256672756d4f2f4e`。主智能体只读审核唯一性、族隔离、问询标签、哈希，并在每族首／中／末重新执行 45 条 SQL，全部与冻结真值匹配。该套件明确是 **15 个相关合成题目族**，不是 300 独立模板、外部用户题集或生产性能结论。

项目 5 的真实数据库结构与项目 4 的导出完全一致；本轮浏览器真实 Java 预览后确认同一完整资产导入，`benchmark/import-committed-1.png` 显示 6 对象／11 指标／6 维度。`benchmark/import-index-status-1.json/.sql` 核对 COMMITTED 回执、同一完整 inputHash `sha256:956cca1da8ba9e319a79ca45a46f009ed22b3fc108e7066cf940ae0b62a03f67`、sourceFingerprint，以及六个真实工作与语义内容 SHA；随后 180 秒实际超时／503 保存于 `benchmark/index-qwen-1.log`、`benchmark/index-backend-1.log`、`benchmark/index-status-2.json`，仍未获得完整向量。`benchmark/frozen-learning-baseline-2.json` 中案例、模式、模板、案例文本／向量、个人定义文本／向量、项目定义候选／向量均为零。普通请求不能改变服务器管理员配置的冻结项目列表。

冻结控制复用统一案例捕获和轨迹学习入口，默认普通项目继续学习，项目 5 禁止产生可复用学习资产。运行器只将问题与实际问询后的自然语言答复送入正常会话 API，SQL／真值只在本地评测；每次正常计划批准、执行回执、原生 checkpoint、结果工件和错误取消都需留证。

正式计划为 200 次开发 + 100×5 次留出 = 700 次。本轮 `benchmark-preflight-1.log` 真实拒绝未初始化的评测项目；该预检阶段实际执行 0/700，不能登记 Pass@1、Pass@5 或 83.1→97.8 等历史指标。后续当前部署执行、学习隔离证明与正式进度见下节；旧受阻事实不改写。

## 2026-10-03 当前产物与实际用户流

`backend-package-7.log` 完整退出并报告 **937 项／160 类，零失败、错误和跳过**；`backend-results-7.json` 逐类绑定本轮日志与 XML。Maven 使用 2 CPU／512 MiB；Surefire 为单 fork、实际 JVM 默认资源配置，没有误称 fork 也继承了 Maven 内存上限。测试日志 SHA256 `59e74928b1f13c83103f9d45d6eca94a5d5412f66731b22f15fa9066c53cbb25`。

当前运行后端 JAR 为 **`d238ac74b0a938eb51857c451ba6763220da28c3cee9e167e8ea9ca5033c03fa`**，`deploy-5.json` 与 `deployed-accounts-5.json` 核对实际容器、健康 UP、11 项真实 HTTP 权限和 2 项 PG 会话归属。`web-verify-5.log` 为 25 项测试、lint、类型、knip 和构建全部通过；`web-deploy-5.json` 绑定 68 个实际文件，保留旧 24 个 chunk／3 个发行版本，前端更新未重启后端。当前组合是后端 deploy-5＋前端 web-deploy-5。

`benchmark/index-new-worker-completed-1.json/.sql` 核对普通与冻结项目合计 12 个真实文档／1024 维向量均完成，完整内容 hash 与持久 work 一致；此前 180 秒失败及旧 worker 503 级联全部保留。冻结项目经真实 CUA 验证、发布、自动激活 v13（`benchmark/published-cua-newjar-1.json/.png`）。新 worker 失败后停止本轮 drain 的真实 PG 队列回归属于完整 937 项测试。

当前 JAR 的真实净收入 Run `76870487-16df-41c8-8b59-639eabe60604`：自然语言问询、QUERY 范围补全、提交后实际杀进程、同一容器／JAR 重启、原生 checkpoint 恢复、重复回答幂等、他人 403、正常计划批准后返回唯一已确认输出 **143521.91**。`fresh-net-income-verified-newjar-2.json` 14 项通过。原旧 JAR `791e4984…` 的双列问题和旧审批、数字、工件独立保存；通用 scalar 输出身份修复保留左右指标／运算／确认 hash，多个确认仍拒绝，未修改 Gold 或发布目录。

当前 JAR 的实际锁恢复 Run `ffdb8929-e828-4c91-b9b6-8751fabfc0de`：临时 orders 表锁导致真实 EXPLAIN 超时、SQLState 57014 与确认回滚；清理后正常 SQL 修复 1 次、同一已批准语义计划继续执行，唯一结果 **622** 与独立 SQL 一致。失败 source sub-run 仍保留，`fresh-sql-lock-verified-newjar-2.json` 11 项通过，业务数据 hash 不变。脚本 namespace 身份守卫限制为具备原 fixture、项目版本与只读连接的隔离库。

普通项目数量 Run `adcfeaf6…` 在前一 919727 部署真实返回五分类件数并确认正确，学习仍正常。当前 d238 的净收入和 SQL 锁流程是当前产物真实证据，不能用之前 919727 的数量流程代替当前 JAR 全部运行。

发布只读版本页面现提供结构导出；真实 Chrome 保存 `$LOCAL_PATH (1).json`，6310 字节、5 表、SHA256 `b46e1447fff80f1b8b9004c68d9c2aefea78379726a28c5c0c56542dc563167b`，结构指纹与初始化一致。Blob 下载事件观察器超时独立保留；实际文件 mtime、下载后字节、正常 API、DOM 无导入入口、发布版本／就绪不变分别交叉核对，`source-schema-readonly-browser-verified-newweb-2.json` 9 项通过。

Luna／Terra 的逻辑模型调用统一最多 **5 次实际 HTTP**，SDK／Netty 不额外放大；流式已输出片段后不拼接重试。Run 原生恢复 2 次和模型 HTTP 分开记账，绝对 deadline 优先，实际少于 5 次不能称穷尽 5 次。聚焦真实 loopback HTTP 回归 34 项通过并纳入全量；生产真实成功日志实际多为 1 次。Qwen 业务 rerank 仍有 5 秒调用方超时、503 和 RRF 回退，服务端旧推理可能继续直到 180 秒 watchdog；连接探针成功不能算完整业务重排通过，暂不改变调用方预算。

## 正式冻结评测当前进度

额外预演独立保存，不计入 700：冻结两次普通查询成功且 14 项学习计数不增长；QUERY 预演首次因评测脚本错误提交空 selectedOption 被正常 API 拒绝并取消，修正为 null 后 Run `acc2a0ae…` 正常自然问询、审批、唯一 q 输出和 SQL 真值均通过 13 项，实际 237.06 秒。

正式运行 `benchmark/formal-700-newjar-1` 于 **2026-10-02T23:45:42Z** 启动，workers=2，原计划 200 开发各一次＋100 留出各五次，预检全部 300 条实际 Gold SQL 未改变。runner SHA256 `59f88cd8cee11aba1a08b6f5d27c0957925ec573048f1209d6412f2a9bd0391a`，Gold SHA 保持 `bc593f…f2f4e`。该旧产物批次现已安全停止为部分基线：**37 个真实 Run 均到终态，663 次未启动**。不能报告 700 次完成或最终 Pass@1／5。

资源协调仅 SIGSTOP 本地评测进程，未取消真实 HTTP、修改 runner 或后台状态。两条已经正常批准的 Run 随后实际到 FAILED／SUCCEEDED，无待审批且 Qwen idle；于 00:10:03Z SIGCONT，暂停 **960.743 秒**。`formal-pause-boundary-1.json`、`formal-pause-stable-2.json`、`formal-resume-boundary-1.json` 保留真实时点／Run finish_time，原 wall/machine 秒不覆盖，性能统计必须单列终态后的调度等待。

首份固定评分快照为原 **9 PASS／6 FAIL／2 尚未评分**。其中四条成功 H01 Run 的数值与日期正确，但原 scorer 要求原维度 code，编译计划合法使用唯一时间桶 alias。只读 `approved-time-bucket-identity-v2` 依据已审初始化包→COMMITTED inputHash→PUBLISHED v13 CatalogHash、原 requestId／审批序列／recoveryHash／精确恢复 plan，再绑定唯一相同来源、字段、粒度和表达式；拒绝 schema 不符、双匹配、多输出及错误依赖，不读取题号或答案值。`scripts-tests-13.log` 34 项脚本通过；`formal-output-identity-rescore-2.json` 单列首份快照 **13 PASS／2 FAIL／2 尚未评分**，新增模型执行为 0，原评分／Gold 不改。

旧批次最终原始快照 `formal-original-scoring-partial-final-1.json` 是 **13 PASS／22 FAIL／2 尚未收评分**。同 Run 的独立只读复评分 `formal-output-identity-rescore-partial-final-1.json` 是 **20 PASS／15 FAIL／2 尚未收评分**，只校正七个唯一时间桶输出身份；没有重跑模型。最后两个 Run 的数据库终态、实际 SQL／结果独立保存在 `formal-stop-terminal-D01-order-count-02-r1.json` 和 `formal-stop-terminal-D02-order-record-sum-02-r1.json`，没有覆写原快照中的 RUNNING 评分状态。`formal-oldjar-partial-stop-1.json` 核对 37 个已启动 ID 与数据库完全一致，最后两条先到正常终态才结束本地评测进程，停止没有发送取消请求。

本批评测暴露的普通时间常量 CAST AST 识别缺陷，以及合法共享 ATTRIBUTE 未进入分组候选的通用缺口，正在单独修复和验证。新源码尚未计入 d238 的 937 项通过证据；必须完整回归并部署新产物后再从新目录运行全量。静态表是否适用全局时间门禁仍待用户选择，当前规则不改。

真实失败仍保留：D09 静态商品模型无语义时间列，被系统 requireTimeFilter 全表硬门禁拒绝；未为题目关闭保护或添加假时间。此开关来自 application.yml:97／prod:10 和 SqlCostGuard:61，资料未发现已明确要求全表还是仅时间事实表。规则讨论由根代理询问用户，未答前不改。H01 一次额外问询实际取消也仍为失败。该套件仍是 15 个相关合成家族，不等同生产性能。

## 当前要求矩阵

13 组交叉要求不能相加为独立功能；以当前产物真实流程看，6 组已通过、6 组部分通过、1 组评测存在真实失败。当前 full9 的既有实现有全量产物绑定；随后发现的预算路径修复尚待 focused／完整回归及新部署，不混入 full9 的通过数。

| 编号 | 当前实际验收 | 当前缺口／限制 |
| --- | --- | --- |
| SQL-00 | 通过：新项目正常初始化、导入、完整索引、发布；当前只读结构下载 | 初始化质量与问数准确性继续按 SQL-05 统计 |
| SQL-01 | 通过：真实 PG/MySQL 保护组件及当前实际锁超时、回滚和连接恢复 | Qwen 失败不能替代 SQL 取消证据 |
| SQL-02 | 部分通过：统一 5 HTTP 合同、当前 SQL 修复成功且原额度持久化 | 完整语义／执行重规划失败与重审用户流待补 |
| SQL-03 | 通过：当前 JAR 回答后实际杀进程、原生恢复、幂等与他人拒绝 | 原始失败与恢复轨迹均保留 |
| SQL-04 | 部分通过：真实 12 完整向量／学习冻结；修订／CAS／权限组件通过 | 当前新项目个人定义与三人五次晋升完整用户流 |
| SQL-05 | 真实失败保留；旧批次 37 次部分基线已收束 | 新产物完整 700 与最终正确率、问询误阻断、失败类型、耗时、学习隔离统计待完成 |
| A | 通过：严格 1.2、真实 Java 导入、正常发布激活 | 离线 importerCompatibility 保持 not_verified，Java 实证单列 |
| B | 部分通过：当前双源定义输出、正常审批／执行；前版五分类数量通过 | 当前自然查询质量最终由冻结 700 评估 |
| C | 通过：当前 checkpoint／同一 Run／实际进程重启 | 不使用旧 JAR 流程冒充当前 |
| D | 通过：自然回答、刷新、幂等提交、实际恢复及索引进度 | 响应观察器超时与真实文件下载分别登记 |
| E | 部分通过：新用户自然问询、跨轮换月、换题、审批纠正及等待时重启 | 旧冻结文本实际超时保留；完整管理纠正及当前新产物预算待补 |
| F | 部分通过：新用户完整私人文本／结构、独立用户确认、正常分享、真实 Terra 评估 | 当前仅公共1人2次；B私人使用未计公共，三人五次发布／范围修订／局部撤回继续 |
| G | 部分通过：当前 SQL 保护／修复、统一模型传输合同和冻结隔离 | 业务全模型 rerank 有真实超时回退，正式运行错误收束仍需最终核对 |

## 可重跑入口

### 2026-10-03 新批次补证（真实时间）

完整 backend full8 实际 exit0：161 类、950 项测试、0 fail/error/skip。日志与所有 XML 已冻结在 `backend-package-8.log`、`backend-results-8.json`、`backend-reports-8/`；部署6实际 JAR 为 `5cca784d73a3378156ac9f68432299369b1ec14f0d46682b7351cd971e7fc245`，容器内 SHA 与受测产物相同。此构建同时完成时间 CAST 识别和显式 AST ATTRIBUTE 投影，不包括之后的历史 ATTRIBUTE 兼容修复。

该 JAR 上真实浏览器 Run `1baae4a1-5736-4243-acad-3aac9d51eabe` 对新日期范围的客户金额分组仍触发 DIM_MISSING；经普通取消问询正常收束为 CANCELLED，失败保留。最初“原包仅2 definitions，故不存在受治理 ATTRIBUTE”的诊断已更正：原始包确无显式客户 ATTRIBUTE AST，但通常 Java 导入已通过既有 `qw_register_legacy_model_assets` 建立不可变 LEGACY_PROJECTION 定义修订与精确模型／字段绑定。实际只读 PG 来源见 `legacy-attribute-existing-authority-1.json/.sql`；原 v12/v13 已发布指纹未改。

通用修复复用既有历史绑定，而不增加测试专属维度或业务含义：校验原 definition/revision、角色、model/assetKey/legacyAssetKey、完整19字段权限投影和直接字段；显式维度优先；临时维度携带原定义身份进入审批与序列化恢复。focused3 真实28项中1 FAIL（候选可见性仅检查 AST）；focused4 真实11项中1 ERROR（否定夹具的直接 SQL 权限改动被现有不可变门禁55000正确拒绝）。两批原始失败与 XML 保留。修复既有可见性入口、将否定测试改为断言真实拒绝后，focused5 的9项单元＋2条真实 PG 导入／候选／JDBC分组／冻结恢复链全部 PASS。历史兼容修复尚待新完整回归和新 JAR 浏览器实证，不计入 full8 成功。

前端 webverify6 的25项测试以及 lint/typecheck/knip/build 全部通过，实际仅修改欢迎示例：同一启用模型的指标与可展示维度、明确的时间字段，不跨模型拼接。webdeploy6 验证68个实际文件及 API代理，保留23个旧chunk，未重启后端。当前真实浏览器 `sem_member_a` 无旧会话，欢迎示例对应全部订单／下单时间／全部订单状态；证据 `welcome-same-model-time-axis-web6-1.json/.png`。该新用户正常自然问询“净收入”并保存 PRIVATE 个人文本修订28/1，模型明确发现付款与退款公式／时间归属缺口并问询；跨来源公式的后台 AST 结构化正确停留 TEXT_ACTIVE/STRUCTURING_UNSUPPORTED_CAPABILITY，不冒称已结构化。其实际审批、SQL及后续承接仍在本批验收中。

在仓库执行，输出目录使用新的证据路径，避免覆盖已有证据：

```sh
python3 scripts/seed-quality-business.py --namespace finish_20261002 --output "$EVIDENCE/fresh-seed.json"
python3 scripts/setup-quality-project.py --namespace finish_20261002 --output "$EVIDENCE/fresh-project.json"
python3 scripts/test-backend-with-databases.py --output "$EVIDENCE/backend-package.log"
npm --prefix frontend run verify
python3 scripts/prepare-frozen-benchmark.py --output "$EVIDENCE/new-unreviewed-manifest.json"
python3 scripts/inspect-local-inference-resources.py --output "$EVIDENCE/qwen-resources.jsonl"
```

重新生成 manifest 会产生新的冻结时间和哈希，必须独立审查后运行，不能替代已经审查的原文件。正式运行入口为 `scripts/run-frozen-benchmark.py --suite <原冻结文件> --expected-suite-sha256 <已审核哈希> --output-directory <新目录>`。完整输入未就绪时不绕过预检。


### 2026-10-03 full9 与新用户真实证据

full9 于01:44:06–01:53:14 UTC实际 BUILD SUCCESS，实际执行161类、953项、0失败／错误／跳过（原归档曾混入旧Java17类2项，2026-10-03 fresh-XML审计更正；原955报告保持）。Maven及单Surefire fork实际限制2CPU／512MiB。受测JAR SHA256 `dff9599bcf8d18167d09aaac017755dbae7e6cb5867f3f9eb2e2de71df61794e`，`backend-results-9.json`含33个后端源码文件与source-review12的精确绑定；部署7内JAR相同，`deploy-7.json`记录正常UP及文件校验。原v12/v13发布hash不变。

本批跨版本真实新用户流程：成员A的私人跨源净收入文本28/1保留完整公式，首次实际143521.91、同会话二月承接120378.71，分别18项SQL／工件／Oracle／冻结来源检查通过。该两条是在此前5cca版本实际执行，不能混算dff新Run；结构化后台能力不支持跨源个人AST，明确保留TEXT_ACTIVE。之后dff上结构化私人有效订单29/1正常审批纠正为三月，实际132；再承接一月158，各18项通过。首次旧5cca修改月份的03a8…绝对期限失败保留，不被新成功覆盖。

通用历史ATTRIBUTE实证：dff新Run `e0346bea-773f-41f1-9bb1-acaba8aa8bd3`以新日期2月3–12日按客户标识分组、金额降序前4，0问询正常审批，实际4行与独立SQL一致。`legacy-governed-attribute-verified-deploy7-1.json`8项通过，定义／revision／role／model／binding及完整19字段来源保持原v12身份，没有按题增加维度、模板或改hash。

前端web7修复实际审批纠正后旧摘要仍显示原月份的问题，统一读取当前Run的最新冻结APPROVAL_PLAN_SNAPSHOT；旧完成答案保留自身含义，坏JSON、其他Run、旧审批／未来快照均不替代。27项前端测试、lint/typecheck/knip/build全部通过，68个部署文件及正常代理通过。成员B新Run `d8587fcf-e0cf-41fe-86f0-f9f28adb05f8`正常从二月拒绝并改三月，当前聊天和审批卡均显示三月，截图`member-b-current-march-reapproval-web7-1.png`；审批后132、18项实际SQL／冻结结构／Oracle通过。

成员B最初采用未发布成员建议时，系统自然问询完整定义，没有未确认直接使用；选择保存私人定义30/1后WAITING_HUMAN正常重启。`member-b-native-waiting-restart-verified-deploy7-1.json`8项证明同Run／线程／冻结审批／答案／原生检查点／累计余额不变，SQL0；浏览器重新登录恢复原计划后正常批准。其冻结TEXT执行后触及绝对期限，Run `38ed64d4-ea74-4506-a0d6-5a90ed2c5bb2`真实FAILED，SQL0；恢复检查通过不等于问数通过。之后新Run使用已完成后台AST的相同私人30/1，结构化正常成功。`project-definition11-private-member-b-not-counted-deploy7-1.json`12项证明B真实私人使用未增加公共人数／次数，候选当时仍仅A的1人2次，未发布。

只读v3复评分限定冻结v13的31条原ATTRIBUTE及全部19不可变属性、唯一单表直接投影与批准快照；JOIN／CTE仍拒绝，未读取题号／答案来匹配。根代理审阅后执行`formal-governed-identity-rescore-v3-1.json`：原13PASS／22FAIL／2未收评分保持，v2和v3均20PASS／15FAIL／2未评分，新增模型执行0。`v3-review-binding-1.json`绑定scorer SHA `e6d585…`、复评分源`4b2aee…`、authority`6c625b…`、原Gold`bc593f…`和原评分字节。新700尚未启动。

静态预算审计另外发现活跃HumanFeedbackNode仍有legacy count≥3额外门槛、PlanExecutor校验另有count>2且成功后清零；这不符合清单SQL-02各类2次且无额外总门槛。已将用户拒绝／执行校验失败分别复用QueryRepairPolicy的REBIND_SEMANTIC／REPLAN_EXECUTION同一持久额度，成功批准／校验不消耗也不清零，legacy count仅观测。新增4个恢复与边界单测及1条真实PG原生checkpoint合同，尚待独立focused及完整回归、新JAR部署，不计入上述953实际已通过数。


### 2026-10-03 full10 与预算统一部署

full10 实际03:17:07 UTC退出0 / BUILD SUCCESS，162个fresh Java21 XML、958项全部通过，无失败、错误或跳过。新增4个统一审批／执行修复预算测试及1个真实PG checkpoint测试。源码38文件与source-review-14一致，JAR `63384358f848064dd05868f974f8bc9408206d270c244ff73aba837d52cc9625`，部署8运行同SHA、UP；现有web7的68文件相同。`backend-results-10.json`与`backend-report-freshness-audit-10.json`保留实际绑定。此前full9的原955报告未改写，另留`backend-results-9-correction.json`说明实际953与旧Java17 XML2项；该误差不影响原JAR或真实业务Run结果。

成员B在旧dff部署正常完成3个新的QUERY：三月132、二月111、一月158，各18项SQL／身份／审批／定义核验通过；私人范围期间仍不计公共贡献。正常分享30/1两次幂等请求后候选11为2人5次、评估DONE／继续累计，未伪造第三人或提前发布，真实浏览器达标筛选排除该候选。普通成员及局外访问他人private Run／个人口径403。

管理员贡献来源详情尚未展示，清单SQL-04第198行明确要求；补齐工作在full10冻结后另批实现，不包含在上述958条或部署8中。第三位用户、自动晋升／冲突和完整新700仍待真实验收，D09静态表全局时间门禁的业务选择仍待用户答复，维持原规则。


### 2026-10-03 full11、贡献明细与未来私人范围（真实部署9）

full11 于03:52:18 UTC实际 BUILD SUCCESS / exit0，163份fresh XML合计959项，0失败、错误、跳过；与Maven summary完全一致。测试脚本现在先归档原报告再执行，并验证每份XML时间与SHA，防止旧文件混入。本批44个后端文件与source-review-15冻结清单精确相同。JAR SHA256 `4a573f523850a02d8bd91d7d3927773baca3f2bb76968cd680480b64e065cec4`，后台全量日志SHA `6377c7a219fce65a600da0fe6d09f511856d501e849f8ffa5600cf7c813efbb4`。web8有27项测试以及lint/typecheck/knip/build通过。正常部署9的运行JAR一致、UP、68个前端文件匹配，旧产物镜像仍可恢复；地址仍为http://127.0.0.1:3303/semevosql/。

新管理详情复用原贡献资格入口和原fingerprint，不另建计数规则。历史明细仅展示曾经授权共享的记录，采用可信Run归属与相同project/candidate身份，不泄露成员私人问题正文。当前admin API15项实际通过，成员与伪造role403、跨项目404、分页边界400；CUA正常管理员登录、刷新web8、项目4语义治理展开，显示实际B3+A2五条授权使用、2人5次，第三人尚未达到。展开来源显示真实Run `626bbc8b-925d-493b-be9c-0bf68831d093`、v12、SUCCEEDED。证据`project-definition11-contribution-api-deploy9-1.json`及`admin-contribution-source-deploy9-1.png/.dom.txt`。

成员B在部署8正常发出“以后仅自己使用、过去三次贡献保留”的自然语言管理指令，Run `bfa2b00a-bb84-4357-a06f-b00b074f035b`经完整含义、未来范围与USER双轴问询正常确认，SUCCEEDED，SQL0。24项实际核验通过：私人定义30/1、AST、旧批准快照与v12 hash不变，旧三条per-use ALLOW保留，未来默认PRIVATE。模型网络实际QueryEnhance两次HTTP（首次60秒超时），不是穷尽五次；Terra管理解释一次实际HTTP。之后新Run `4528de20-9ca0-45d1-85f4-31f9d0390451`正常批准的一月查询得到158、18项实际SQL／结构／Oracle检查通过，个人使用可记录但不贡献公共次数；独立8项SQL/API证明公共仍2人5次。

首次只读验证参数误写`p30_1`造成Oracle列两项失败，保留`member-b-new-private-query-verified-deploy8-1.json`；更正为实际冻结字段`p_30_1`的同Run复核18PASS在`-2.json`，新增模型执行0，未改变输出或真值。当前新版只读assessment脚本按每条使用的明确授权优先于未来默认私人，5项边界测试通过，当前12项实际核验2人5次。旧Terra评估call `3967196e-307f-4ce3-b00f-57fb83e976bd`复用严格绑定内容修订、base/catalogHash、representation/dependency身份和旧已验证报告SHA，明确`MODEL_ALIGNMENT_RECEIPT_REUSED`，不算deploy9新HTTP。

第三成员的正常问询／实际QUERY／首次公共发布、未来撤回明细与冲突流程正在继续。正式新700尚未启动；旧37次基线及原始/v2/v3评分和所有真实失败仍完整保留。D09全局时间门禁规则选择仍等待用户，未修改门禁。


### 2026-10-03 deploy9 第三位成员与发布索引竞态

新成员 C 的首个 Run `69b37b0b-1618-4f77-bec7-f12b8acc4766` 在真实浏览器自然问询后，确认完整有效订单含义并允许分享为项目建议。新私人定义32/1正常结构化；原生恢复1/2后正常批准查询，返回一月158，`member-c-initial-verified-deploy9-1.json` 的18项冻结计划、SQL、工件和独立真值检查通过。首次无效模型时间区间、Luna一条逻辑调用的两次实际HTTP及绝对期限预算仍保留；不称穷尽五次网络重试。

`project-definition11-assessment-three-users-deploy9-1.json` 的12项检查核对公共候选11实际3名有效用户、6次有效使用、3条授权来源；B未来私人使用不纳入公共计数，过去显式分享的3次仍保留。系统正常创建AUTO_CREATE发布任务14与新草稿v14，v12/v13旧发布hash不变，尚未把待发布记为成功。评估复用原真实Terra回执，验证器新增模型执行为0。

v14首轮六文档中四个完整向量实际完成且为1024维；trade_orders完整10041字符输入截至04:52UTC保留五次失败/退避。另一products文档旧worker标记DONE但向量数0：编码期间catalogHash变更使数据库栅栏正确拒绝旧写入，旧IndexingResult却把该拒绝视作向量可用。这是一般索引完成状态缺陷。源码仅在全部stale文档真实写入成功时返回vectorAvailable=true，继续保留原catalog/content/source栅栏、输入和模型期限。

`catalog-index-race-native-1/` 用旧受测4a JAR精确类字节及真实PostgreSQL重现1 FAIL；新源码单方法green-3实际1 PASS，17.216秒，验证并发catalog变更、无旧向量落库、工作保留RETRY、两条持久义务正常推进、成功缓存不重复调用。模型向量为合成测试向量，不能算真实Qwen。前两份green因相反的调度顺序假设失败，原日志/测试字节独立保留；最终测试不要求时间戳队列先后顺序。该新修复尚待focused/full12与新部署。

04:52UTC确认本人无活跃Run、索引PROCESSING或发布BUILDING后正常暂停本人后端，旧4a镜像保留为`semevosql/backend:before-deploy10-4a573f52`。`own-backend-index-defect-pause-deploy9-1.json`保留过程与共享Qwen忙闲观察；未停止其他项目、删除数据或SQL制造完成。当前公共发布仍待正常索引/激活，后端暂停不等同部署健康通过。

300条原冻结SQL真值04:36UTC重新只读执行300/300匹配，61.29秒，Gold SHA仍为`bc593f…f2f4e`，新增模型执行0。新700尚未启动。当前SQL-04/F已补足第三用户真实查询与自动发布启动，但公共发布、范围修订/撤回和新完整评测尚未完成；D09仍等待用户业务规则答复。


### 2026-10-03 full12 与维护失败停止入场

focused9实际16项/3fresh XML全PASS，26.718秒；full12实际05:29:13UTC自然BUILD SUCCESS，10分37秒、960项/163fresh XML全PASS、0失败/错误/跳过。45后端源哈希与source-review16完全一致，Jar SHA `8862c0ed05c4e4cc79679ee2f12d629e7fcfb770bbdc057ceecf55a678d3bc0f`，日志SHA `239286…1f605`，完整XML、原Jar和`backend-results-12.json`均保留。该产物包括catalog栅栏拒写后的完成判断修复，尚未部署，不包括下一维护结果合同。

只读审计发现全局维护processOne原返回值仅表示已领取工作；失败后worker未区分失败与成功，继续领取普通文档。这与既定失败停止本轮drain的合同不一致。`maintenance-drain-race-native-1/` 使用full12原Jar抽取的精确运行类和真实PostgreSQL，旧行为实际1 FAIL：维护已RETRY，仍有两文档被领取并完成，providerCalls=3。此故障注入使用合成provider，未请求Qwen，不冒充生产故障。

最小修复增加NO_WORK/COMPLETED/DEFERRED显式结果，保留原processOne的claim观察语义和既有claim测试，worker在DEFERRED后结束本轮。真实PG green实际1 PASS、10.189秒，维护失败时doc attempts=0、vectors=0，保留两个PENDING；正常持久重试完成后复用staged向量推进两doc；维护队列为空时第三条普通文档仍正常完成。未改变模型、维度、文本、超时、既有退避或租约/代际栅栏。下一full13与新部署证据将单列，不计入full12已通过数。

### 2026-10-03 full13、部署10与完整输入 CPU 对照

full13 实际05:44:45UTC自然BUILD SUCCESS，8分1秒，961项/163份fresh XML全PASS，0失败/错误/跳过。46个后端源码SHA与source-review17精确一致，JAR SHA `1600b8b2546900e4412e8957e4dbb77e2600bbf3344067ffdef583640b24d009`，日志SHA `98bce697cfb517f9940efec20f09af60b2afdf0dc705952c5ac3db1018442837`；原JAR、fresh XML、日志与审计报告分别冻结保存。本批包含维护显式结果修复，不混入先前full12。

部署10于05:51:25UTC正常UP，容器内JAR相同，保留web8且68实际文件一致；新权限核验11条真实HTTP+2个PG归属通过。普通管理员async reindex HTTP202启动原正常维护revision3，未SQL改队列、向量、审批或发布状态。完整10041字符trade_orders单输入在CPU2/1的doc attempt6超180秒，维护保留首轮503以及后续3轮180秒超时/退避。维护失败结束本轮drain；下一正常scan在维护退避期间领取已到期doc、产生attempt7的503，这是独立wake，不能伪称没有失败。products旧falseDONE/无向量仍保留，v14当时4/6当前1024向量、待发布，active仍v12。

500秒/50次原生采样表明两轮180秒期间容器aggregate CPU约345/346秒、VM available约4–6GiB，容器OOM均0；既有请求无唯一request ID，不能仅凭时点把共享日志全部归属本任务，也不能单一归因内存或锁。06:05:44UTC核对maintenance/doc/query三项inflight均0后仅暂停本人后端，保留数据/镜像/队列，等待shared watchdog正常回收与reload，不杀kernel。

实际token-count按已加载模型原preprocess、prompt/chat template计数：同一10041字符/11881字节/文本SHA `ea4a995ba4426008ee003e8c9effd8a250754a91c53dc741e7c5b9a5b9bd0002` 为2754tokens，模型max262144，fits=true、truncated=false。根代理授权后复用Orbis现有部署入口，只补通用CPU CLI与新证据路径。06:09:45UTC以原精确image `ad9bf8…5a4c2`、原model-cache部署CPU4/1，sourceChanged=false，不build/pull/download；CPU2/1旧容器及精确restore命令保留。06:10:18UTC同受测1600后端正常恢复旧maintenance due做一次对照；当前尚未完成，不能仅凭readiness记长输入或发布PASS。

新JAR正常CUA登录看到此前自动连接验证503留下的向量/重排FAILED；v13索引6/6就绪不等于模型配置验证已恢复，需通过正常验证入口再开始查询。新700尚未启动；所有旧Run、Gold及original/v2/v3评分保持。D09业务选择仍待用户，未修改规则。

### 2026-10-03 CPU8 完整编码与自动发布实际完成

CPU4/1对照仍实际180秒失败；busy样本区间176.25秒、aggregate CPU488.14秒（平均2.77核）、major faults仅+5、VM available3.56–4.94GiB、OOM0，不能只归因于共享锁或内存。CPU8/1同image/cache/model/完整2754tokens于06:16:43UTC正常READY；doc attempt9实际06:17:32–06:20:21返回HTTP200、169393ms，当前1024维向量与完整content/source/catalog身份匹配。随后正常全局维护复用该向量，仅补完整products3230字符、HTTP200/27038ms。06:20:50UTC维护revision3正常DONE，39文档覆盖一致，v14六文档均DONE/current1024；`publication14-complete-index-deploy10-cpu8-1.json`的6项实际检查通过。CPU8原生样本平均4.87核、major faults+797、VM available4.09–5.42GiB、OOM0；此为本机对照，不冒称通用最优性能。旧CPU2/4失败与可恢复容器均保留。

06:23:41正常发布退避到期后，系统AUTO_CREATE任务14第7次自然DONE并发布/激活v14，未SQL改审批、状态、贡献或版本。`project-definition11-publication-verified-deploy10-1.json/.sql`13项真实检查全部通过：精确candidate/job/operator/action、唯一immutable event/evidenceRevision/contributionFingerprint、正常发布激活审计、公共身份与私人分离、完整公式/含义、旧公共快照及所有私人文本/结构修订保持，六条向量当前一致。真正受测运行Jar仍1600…d009、web8未变。

共享窗口06:21释放，Orbis正常恢复existing worker后原209队列自然变更，不能继续称独占。正常CUA向量模型验证06:22:50遇到真实shared busy503，FAILED仍保留；待顺序正常重测后才运行新冻结查询。发布成功不等于重排、正式700或全部E/F验收通过。另本地暂停证据探针第二次误覆第一JSON，当前副本和工具原回执/独立资源日志保留，`terminal-pause-evidence-path-correction-1.json`如实说明第一文件原字节未完整保留，并补fresh --output前置拒绝，未改任何业务数据。

### 2026-10-03 当前部署连接验证、冻结烟测与新正式批次

普通管理员CUA顺序验证后，向量配置于06:27:55UTC、重排配置于06:29:29UTC实际PASSED；`model-validation-normal-cua-deploy10-1.json`绑定1600…d009 JAR和固定Luna/Terra/Qwen配置。这里只证明正常连接验证，不将业务查询中的rerank超时/RRF fallback计为真实rerank通过。正式v1.1.0及3人6次已发布状态另有当前CUA截图，公共发布13项PG检查通过。

冻结project5/v13的两条新烟测于06:29:53–06:31:30UTC正常执行审批和SQL，分别Run `80b29b41-bf0a-4606-9a41-9b9f92cabc58`、`c4b4b1bf-3bcf-4d68-9135-31b9b762d55c`，各13项检查通过。学习前后cases/patterns/templates/sqlPatterns/private/candidate及其向量均0，正常文档/向量6/6；2次烟测与正式700分开。

原冻结suite SHA `bc593f71b133a865659390b2fcd7ed1fdcad12f3d7757dc2256672756d4f2f4e`、runner SHA `ac389c168b44c24bbcb57286a65c8ae4c1e6f7d494c4f8aa4cd76849129f995a`不变。独立启动封存`benchmark/formal-deploy10-admission-1.json`只读重新核对实际运行JAR、68前端文件、发布catalog、学习隔离、模型配置及已审核v3源码；预检本地import和误用表名的两次失败在工具回执保留，未发模型或写业务状态。最终封存通过，正常runner重新验证300条SQL真值后于06:40:33.130158UTC启动fresh `benchmark/formal-deploy10-700-1`，PID86593、workers2、100留出每题5次，总计划700。原始评分先保存，v3只读复评分单独输出，不覆盖Gold、模型结果或旧37次原评分；旧37次与新烟测不计入本批。当前批次进行中，不能称700完成。

本套是15相关家族的合成系统评测；旧部分执行及通用缺陷修正已发生，不能称外部生产或从未查看的盲测。D09全局时间条件业务选择尚待用户，现门禁不改、真实策略拒绝仍在分母。Orbis正常后台已恢复，当前CPU8/1、原模型/完整输入/请求1024维/服务180秒保持，但不声称共享资源独占；实际busy/503/回退及本地admission pause时间分别保存。

### 2026-10-03 部署10成员B修订、历史撤回与同名冲突

普通浏览器首次复合修改Run `77df9503-3584-41db-b84a-0b110a0a167f`实际FAILED，未产生问询或语义写入。真实原因是新定义未满足完整当前用户原文校验；原始无效模型响应正文未持久保存，不能据此进一步断言模型改写了哪些字符。后续按已确认规则将历史撤回与新公式分别正常问询提交，未增加按题语义或模板。

Run `3e63cfd6-071c-4984-b417-a57e1f33adf9`通过OTHER自然语言补充明确旧答案仍正确，再确认USER/撤回指定四次共享，实际SUCCEEDED。四个真实使用和APPROVED查询案例保留，最新共享认可均false；其他成员贡献不变，候选11当前2人3次而公共v14仍保持激活，未实施尚未确认的自动降级政策。原撤回校验报告有1项错误地要求仅范围补充也必须产生新公式，原27/28报告及旧验证器字节保留；通用只读验证器改为严格核对六个模型响应键、源身份、修订/hash、本人原文片段、提案的新文本（允许仅范围提案null）及确切历史目标，7项边界测试通过，独立校正报告28/28通过，0新增模型执行。公共/其他用户/旧catalog稳定性另7项通过。

两标签页产生真实确认竞争：主Run `838db20e-3b71-42f9-a88f-f4b55c424182`等待修改公式时，另一Run `18f5d7e3-0c60-4441-bae8-d622835b96e6`先仅授权以后PROJECT使用。旧问题 `3cda2ad1-144d-4aa2-bbdd-700aa8bd5979`提交被SUPERSEDED且无答案写入；UI展示最新范围并以新问题重新确认，正常提交后个人30/2生效、仅供本人，旧30/1和旧四次贡献不迁移。实际29项PG/问询/身份校验全部通过；重建问询复用原模型提案，不冒称额外模型调用。

新个人30/2含义为PAID或REFUNDED，按下单时间自然月统计。真实Run `8b521dfe-59b4-48cd-a2e0-654a20c83f01`正常计划批准后1月返回290，与独立只读COUNT(DISTINCT order_id)真值一致；当前STRUCTURED_ACTIVE的paid_trade_orders确有两状态源过滤，旧trade_orders表示仍保留，18项实际审批/模型/SQL/结果/身份校验通过。另Run `0d61224b-c1c9-49e4-aa5c-a6fbabba4b37`正常取消新的“所有状态订单”提案，13项校验确认没有提交receipt、SQL或结果，个人两修订/两结构/五使用前后字节不变。

Run `da5567b2-ccb8-4bb1-8513-b94b580b2d01`正常PROJECT/CONFIRM_VALID_HISTORY提交，实际SUCCEEDED、10检查点/1问1答；26项检查确认只把30/2的一次已认可真实使用纳入共享，30/1四次仍撤回，五个查询案例保留，公共v14不变。正常后台形成候选12，1人1次、阈值false，实际Terra评估DONE/当前，12项评估验证通过；生命周期NEEDS_ADMIN_REVIEW、阻塞PUBLIC_CHANGE_REQUIRES_ADMIN，未自动覆盖同名PAID公共指标。管理员处理及显式公共查询仍待后续实际结果，不以候选准备好代替公共发布通过。

### 2026-10-03 当前管理员审核与 web9（08:19UTC阶段）

当前实际组合为后端deploy10/JAR `1600b8b2546900e4412e8957e4dbb77e2600bbf3344067ffdef583640b24d009` 与前端web9。`frontend-verify-9.log`实际27PASS、0失败／跳过，并完成lint/typecheck/knip/build；`frontend-deploy-9.json`核对68文件及nginx/API200，backendRestarted=false。`frontend-source-binding-9.json`记录两处源文件SHA与运行JAR，不能将前端更新当作后端新构建。项目列表与health独立读取，刷新/离开取消旧请求并拒绝迟到响应；health未确认不开放列表查询按钮。

实际CUA刷新先显示四项目行及“正在读取状态”，入口仍可打开项目；健康返回后正常显示就绪及查询入口。`project-list-independent-health-verified-web9-2.json`的6项两阶段核验通过。第一份截图/DOM保存时health已经完成，首个验证器错误套用pending断言而FAIL，原`-1.json`及截图/DOM保持，独立`-2`记录真正pending阶段和更正；未注入Mock或网络拦截，取消HTTP字节未独立采集，不能声称有网络层取消抓包证据。

候选12的管理员正常GUI处理：DEFER决策10、RESUME决策11及后续RENAME决策12分别通过12／16／11项实际HTTP和PG身份核验，含8个并发幂等重放、同key异内容409、成员/伪造身份/跨项目403、原私人和已发布公共身份保持。正常尝试OVERWRITE被“候选与目标模型角色不同，不能直接覆盖”拒绝，没有因此生成决策或发布任务；保留截图与原失败，不放宽角色合法性。随后重命名为“已付款或退款订单数”通过正常管理员审批，任务274准备v15，候选1人1次的提前发布由真实管理员授权，未伪造人数门槛。

v15完整6输入保存于`publication15-complete-inputs-deploy10-1/`，全部保持原完整模型文本。08:11UTC实际PG核对products DONE/1024，其余五文档RETRY或PROCESSING（5次），任务274 RETRYABLE_FAILURE，active仍v14；不得把审批或readiness等同发布完成。Shared Qwen容器重启与DockerVM OOM计数增长、真实503和退避保存在原生资源文件，根代理接管通用模型加载/容量故障；当前不改变模型/输入/1024/180秒，不SQL制造完成。v14、原v12/v13 hash与私人修订保留。

显式“只用公共口径”两个真实Run `3f06298a-9ece-4b95-8595-42d093d099bf`、`67205927-9efa-4611-bac1-b0246185e5d7`均因绝对期限失败且没有SQL，不登记公共优先选择成功。07:37–38UTC元数据库发生Broken pipe/peer termination/recovery，容器restart0/OOMfalse；同时Evo数据库也恢复，不能未经证据单一归因。完整本侧日志与容器身份分别为`metadata-recovery-during-formal700-deploy10-1.log/.json`。

正式新700于07:50:49UTC仅暂停下一case入场，没有中断在途HTTP/审批；已启动34条全部终态。原评分8PASS/24FAIL/2ERROR；批准v3对同Run独立只读评分12PASS/20FAIL/2ERROR，新增模型0，尚有666未执行。两次ERROR由正常runner检查后实际取消并保留，没有进行中遗留。原Gold、runner、旧37次及所有原始失败均不覆盖，暂停耗时单列。证据入口为`task-status-after-deploy10-web9-admin12-2.json`；13任务组仍6通过、6部分、SQL-05正式不足，子检查通过数不冒充全功能完成。

### 2026-10-03 发布进度展示合同与共享模型诊断窗口

已独立核对 full13 原日志、961项/163份fresh XML、exit0及immutable `1600…d009` JAR；失效的工具session不作为正在运行或失败的证据，未重复执行已经通过的完整检查。web10实际27项、lint/typecheck/knip/build通过，并以独立前端文件和源码SHA绑定部署；“模型连接已验证／最近验证通过”表示既有验证记录，不承诺当前推理空闲或长输入成功。

原web10真实CUA在候选12上只展示“后台处理：评估完成”，PG发布任务274却在RETRYABLE_FAILURE。通用读端现在分别返回评估与公共发布进度；新增publication字段只暴露任务身份、状态、尝试数、下次重试、错误、准备版本和完成时间，并严格绑定同一candidate/project/contentRevision/sourceRepresentationHash。既有管理员权限入口不变，不返回租约、owner token或私人原文；没有新发布状态写入或业务规则变化。前端分别展示评估与发布及服务器时间，退避时保留正式版本可用的真实含义。

`publication-status-native-1/` 以旧1600精确类字节和真实隔离PostgreSQL执行该合同。首次red的fixture连接失败保留为bootstrap failure（1 discovered/0 started），不算缺陷重现；改为既有localhost映射后red-2实际1方法失败，证明完成评估隐藏待重试发布。green-2覆盖单一新Repository类后实际1PASS，验证精确发布身份、修订/representation变化不带入旧任务、跨项目隔离和白名单字段。provider未被调用，属于数据库合同证据，不能算模型或真实公共发布成功。red-2日志SHA `d7f603222a35cb019779345ce9ff6c37d5c4d484a7556cec8e5907a91861f284`；green-2日志SHA `689757d006d81684ac260de6a39d074a08d27b45d6fc488e9a66fb9f8afed261`。

web11于09:13:49–09:14:25UTC完整verify通过27项及lint/typecheck/knip/build，日志SHA `2df9b75e28b858f16f71cf5430b12ee4fc89680aa27a8e28caaf477622ebb426`。本批新后端完整构建与web11部署/CUA尚未完成，不能归入1600旧产物通过数。当前证据入口`status-scoped-publication-native-web11-1.json`明确区分新源码、原生合同和旧部署。

按根代理共享Qwen诊断窗口，09:07:57UTC通过本人受管compose正常SIGTERM暂停1600后端，原container/image/mount/数据及恢复命令保留，前端web10仍运行；`owned-backend-runtime-repair-pause-deploy10-2.json/.sql/.log`记录PID birth、任务前后快照，未SQL制造发布或索引完成。根代理独占诊断完整2754token输入，保持原模型、bfloat16、1024维、8/1线程和180秒资源期限。短权重切换验证、readiness或框架小张量通过均不能解除v15长输入发布受阻；真实180秒失败、503和退避继续保留。

六份`publication15-full-http-replay-inputs-1/*.request.json`从当前PG完整文本和实际Java请求合同重建，明确不称之前HTTP的抓包字节；每份有文本/body SHA、字符/UTF-8字节、旧v14同文本比较及实际旧1024向量。trade_orders完整10041字符/11881字节、text SHA `ea4a995ba4426008ee003e8c9effd8a250754a91c53dc741e7c5b9a5b9bd0002`、body SHA `5ae3b3d83af46bf2cf1168042a8873dc329bf85edb17016af7328bbd307736f3`，2754token归属orders而非customers。新增只读runtime绑定脚本核对九文件、实际runtime dtype/attention和单模型驻留，身份PASS与质量/耗时结果分别记录。正式700保持34条终态/666未执行，不恢复入场。

### 2026-10-03 full14 新产物冻结（尚未部署）

full14于09:34:15–09:47:56UTC实际执行全量Test+IT，2 CPU／512 MiB，JDK21的JAVA_HOME与PATH一致，保留现有单fork配置；13分29秒后自然exit0／BUILD SUCCESS。163份fresh XML和Maven最终汇总精确一致：962PASS、0失败、错误、跳过，包含新发布进度合同、真实PG/MySQL取消及连接复用、重索引失败停止drain、持久恢复和升级矩阵。46个后端源文件与source-review19完全相同；只读验收脚本和文档后续变化不混入该后端源码冻结。

新JAR SHA `93ce232ed5f409176e96d4ffb7bdea43896e40098356fc4f0367f824877ee1c7`、完整日志SHA `4165b6ce3d5cf321c28fbdefee428a80aa5e52be976db71d4eaa0ad5529bbd4b`，原JAR及所有新报告分别保存于`backend-package-14.jar`、`backend-reports-14/`、`backend-results-14.json`。尚未部署，不能把新检查作为1600旧运行实例的真实流程验收。

web11源码12文件与source19匹配，68构建文件及27PASS验证由`frontend-source-binding-11-predeploy-1.json`冻结；线上仍web10。新只读`verify-project-definition-publication-status.py`已编译，待部署后执行实际API/PG逐项状态核对和成员、外部用户、跨项目403验证，不计为已通过。共享Qwen九文件source `adaa6da5964f846c7f652c9020458fa7b78fb5412bb0d2d31746487b4f03602d`、image `74974efeb67d05bf1b650c3b0fb40e853c4afb92d6b92a5d1f3038e630eb5dce`，09:33只读绑定12项身份通过；当时expanded-kv、实际BF16 SDPA、8/1、180秒、single residency均一致。根代理对同完整输入的native182.06秒和expanded180.526秒均失败，身份通过不等同质量、性能或v15发布通过。后端保持正常暂停，正式700仍34终态／666未执行。

### 2026-10-03 deploy11 / web11 发布进度实际验收

以上“尚未部署”是09:47阶段记录。09:52:37–09:53:07UTC经既有受管compose构建/启动入口正常部署full14 immutable `93ce232ed5f409176e96d4ffb7bdea43896e40098356fc4f0367f824877ee1c7`，随后部署web11并核对68文件、health UP与正常认证API代理HTTP200。`deploy-11.json`、`frontend-deploy-11.json`及过程日志保留。旧1600后端及web10镜像分别保留为`semevosql/backend:before-deploy11-1600b8b2`、`semevosql/frontend:before-web11`；无数据删除、无发布/索引状态SQL写入，未恢复正式700入场。

当前地址`http://127.0.0.1:3303/semevosql/`（backend `http://127.0.0.1:18093`）。正常owner登录后项目4→验证与发布→语义治理→成员建议与演进，候选11“评估完成／发布已完成”，候选12“评估完成／发布暂未完成等待重试”。实际DTO与native PG的candidate/project/contentRevision/representation身份一致；只暴露七字段，成员、外部用户、跨项目管理员请求均403。`project-definition12-publication-status-api-deploy11-1.json`10PASS；desktop实际CUA/部署/当前PG联合9PASS在`project-definition12-publication-status-cua-verified-deploy11-1.json`。完整截图和DOM分别保存，不将状态展示算作发布完成。

正常刷新后任务274为8次RETRYABLE_FAILURE，准备v15，下次12:16:07服务器时间。390×844实际窄屏显示独立评估/发布卡、完整确认含义、真实重试时间；DOM宽384≤390，无横向溢出，检查后恢复桌面viewport。唯一完整含义筛选显示1/2，无匹配显示0/2，清除恢复2/2。`publication-mobile-filter-deploy11-2.json`9PASS，与再次正常GET/PG/权限10PASS相绑定。首份联合验证错误要求移动紧凑卡出现桌面专有尝试数措辞，保留为FAIL；第二份按真实移动重试时间与桌面8次分别核对，零新增模型执行。最初搜索REFUNDED同时匹配两条完整含义（公共含义明确排除REFUNDED），不属于筛选缺陷，未为此改实现。

10:03UTC独立只读队列快照`publication15-current-retry-window-deploy11-2.json/.sql`确认v15四条完整1024向量DONE、trade_orders与paid_trade_orders各保留10次RETRY，下一次10:41:30／10:41:35；active仍v14。该快照纠正前一份窗口证据把较早PG快照与新readiness时间并列的freshness错误，旧报告保留。当前API/CUA合同通过不解除两条长输入端到端索引或v15发布待验。

精确跨版本向量复用的源码审计发现现有EmbeddingEncodingIdentity仅是注册provider/modelName/baseUrl/path/dimension配置哈希，不存加载权重实际revision、tokenizer/prompt/normalization/runtime身份。旧v14向量不能由后来的ready状态倒灌可信revision，也不从本地历史vector文件导入；未知profile必须走正常encode。后续原子response encoding_identity合同由共享服务负责人统一提供，合同稳定前不扩表或把always-miss占位实现声称为复用完成。根代理共享CPU attention候选与完整输入诊断独立留证，不归入本仓库JAR/Web测试通过数。

本批13组状态仍为6已验、6部分、SQL-05正式不足：新发布进度、筛选和窄屏仅补B/F/G子项。真实700原34条8PASS/24FAIL/2ERROR，批准v3同Run12PASS/20FAIL/2ERROR，666未入场；未改Gold/旧Run，未重开700。D09全局时间过滤业务选择仍等待用户，原规则不变。

### 2026-10-03 v15 六条真实索引与依赖恢复修复（待 full15 / 新部署）

10:45:41UTC最新原生PG与10:45:47 shared ready确认无PROCESSING/due索引、无未完成维护、无shared active kernel。v15 trade_orders与paid_trade_orders正常attempt11完成，其余四条原正常完成向量保留。`publication15-complete-natural-index-deploy11-1.json/.sql`26PASS，逐项核对六条全文SHA/字符/UTF8字节与09:10冻结重建HTTP输入完全相同、current content/source身份、真实DONE/租约清空、1024有限L2。未导入根代理独占回放向量；六条均在原子metadata合同采集前编码，实际profile仍unknown，未来精确复用必须miss。任务274仍RETRY8、next12:16、active14，不能将全6索引当作公共发布完成。

一般恢复缺陷：ProjectDefinitionPublicationWorker将已知SemanticIndexNotReadyException也记为PUBLICATION_PREPARATION_UNAVAILABLE，实际依赖已恢复后仍按长指数退避等待；当前GUI无专门发布重试，RESUME仅恢复评估，不可复用来冒充发布。新实现复用既有Job，已知索引等待记录PUBLICATION_WAITING_FOR_INDEX，索引成功事务发after-commit通知，持久scan作为丢通知恢复；同一candidate/content/evidence/representation/contribution/base/管理员批准、prepared version及materialized catalog均精确匹配，当前完整索引ready才提前原任务due。通知本身不创建新审批/新版本，不写DONE/activate；未知失败保留原退避。

正常管理员新增“检查发布准备”按钮，不要求用户JSON。服务再次检查项目管理权限和上述原批准身份，5/6未就绪返回当前准备数量且不改变退避；全6ready只安排原任务，重复请求不反复提前时间或增加attempt。旧274泛型失败不自动伪分类，只能正常管理员明确检查后继续。scope/role/内容变化仍拒绝。新按钮、endpoint和worker尚未部署，完整full15后才执行实际CUA/API/PG验收。

`publication-dependency-native-1/red-result.json`使用old93ce实际单方法FAIL，10:36:12–10:36:26，确实丢失已知依赖原因；old测试字节和日志保存。green实际PASS，后再增强真实Spring事务代理（而非直接对象替代事务）验证 readonly Stale不污染外层事务，新green-2于10:47:32–10:48:06单方法PASS。真实PG覆盖不完整依赖/错catalog/撤销管理员/变更representation/已批准同源身份/重复wake不写DONE与event/未知异常退避保留，以及四并发正常检查一QUEUED三ALREADY_QUEUED。新索引finish事务通知验证commit后才交付、rollback/失败/stale不交付；scoped controller/worker分类和其他任务不被坏依赖饿死的focused native六项于10:45:06–10:46:15全部PASS。受控readiness/provider属于组件fixture，不算真实模型质量或实际生产审批。最后readonly事务代理改动由green-2单列绑定，完整full15将统一核对全部源。

web12实际verify27PASS与lint/typecheck/knip/build，`frontend-verify-12.json/.log`保留；线上仍web11，不提前将新按钮算浏览器通过。新只读runtime脚本同步共享十文件与expanded-kv-fp32，`qwen-atomic-runtime-readonly-binding-deploy11-2.json`12项身份检查通过，source `41c4c4278cfb9e0db17c5b5992d7b88295bcbd2cdf218dff94190a9c3f1f46ea`，只报告CPU attention上采样计算，BF16模型参数/输出与完整输入、180秒原边界不变。首份11/12错误把正常loading状态排除，原FAIL保留；第二份核对loadingKind和≤1驻留的一致性，busy仍如实记录，不称新模型质量/耗时通过。共享原子encoding_identity合同由根代理实际部署认证；本仓库未来跨版本精确复用实现未完成，未知旧向量不补标签。

### 2026-10-03 full15b / deploy12 / v15 正常发布与公共选择失败

full15首轮11:04:40–11:04:52UTC在Spotless新文件许可证检查前退出，0测试，原失败保留；仅补既有标准版权头后full15b于11:06:52–11:23:46自然exit0／BUILD SUCCESS。JDK21 PATH/JAVA_HOME一致、2CPU/512MiB、原单fork，全Test+IT 967PASS／164fresh XML／0失败错误跳过，Maven最终计数与XML一致。immutable JAR SHA `f6857a415cc90084f125b970594f0939ec3f22a42f142100d2b292d72af30c4f`，51后端源与source-review23精确相同；`backend-full15b-source-artifact-binding-1.json`绑定源、JAR与完整日志，旧full15、full14及所有失败不覆盖。web12实际27PASS及lint/typecheck/knip/build，12源／68构建文件冻结。

受管`deploy-tested-publication-ready-12.py`正常构建/启动tested JAR与web12，health UP、API代理200；`deploy-12.json`、`frontend-deploy-12.json`留证，old93ce/web11镜像保留。地址仍`http://127.0.0.1:3303/semevosql/`、backend `http://127.0.0.1:18093`。新API八项越权/错误修订/representation拒绝与PG无副作用检查通过，随后正常owner CUA于11:27:21点击“检查发布准备”，只使原管理员已批准job274到期，实际worker attempt9于11:27:23.295963UTC DONE、candidate12 PUBLISHED、v15 ACTIVE。完整6current vectors、审批/源/fingerprint、旧v14及个人含义稳定、正常PUBLISHED/ACTIVATED审计等13项通过；同DTO/PG七字段十项、实际pending按钮禁用→刷新终态页面/任务十项分别通过，不能将并行检查数当模型Run数。首份completed截图实际仍VALIDATING，原文件保留，后续completed-2/expanded真实v1.2.0及九次已完成。

B用户正常基于v1.2.0新会话，以自然语言明确只用公共“有效订单数”、统计2026年2月、按下单时间Asia/Shanghai自然月、不修改私人默认/范围。Run `ccc8eb17-cba7-4e08-b171-c4accc4d125e`于11:35:08–11:40:09UTC实际FAILED／INTERACTIVE_QUERY_TIMEOUT，8检查点、0问答／SQL／结果，租约清空。HTTP共6：QueryEnhance1、Decomposition1、两个SemanticPlanning逻辑各2。第一逻辑119945ms、第二120016ms因120秒预算失败，正常持久恢复1/2、2/2，第三恢复在300秒绝对期限前进入retrieval，未再发规划HTTP。不能说每次已穷尽5次网络尝试；最多5HTTP、逻辑120秒和Run300秒分别记账。私人30/2两修订／六授权／五使用逐项保持是状态保护PASS，不算业务回答PASS。实际embedding timeout及rerank503/RRF另记，不算真实rerank质量通过；shared ready后来busy=false仅为时点证据、不强归属全局日志。

源码诊断发现system26992字符为分散治理/执行说明，user29292字符为106授权资产；不是system/user重复整份目录。planner仍固定gpt-5.6-terra/medium、60秒HTTP尝试、120秒逻辑及300秒Run。新通用说明收敛及完全相同definitionBinding的无损引用处于源码阶段，所有候选/身份/完整含义保留，未部署未宣称模型改进。只读公共选择验证器从旧project2推广到已授权隔离fixture作用域、可明确版本15并独立Oracle；旧字节保留，已编译但本Run无SQL，未评分为通过。

`task-status-after-deploy12-web12-publication15-1.json`为本阶段入口，13组仍6已验、6部分、SQL-05正式不足；七个原剩余项加未来atomic identity精确复用共八项实际待验。700保持34终态／666未入场，原8PASS24FAIL2ERROR与同Run独立v3 12PASS20FAIL2ERROR并列，0重评分新模型，不恢复旧runner、不改Gold。旧v14及本次v15六向量编码于atomic metadata之前，profile未知必须cache miss，不由后来ready补历史revision。


## 2026-10-03 13:07UTC：通用提示词与未来精确缓存 full16／deploy13

本批沿用正常模型 gpt-5.6-luna／gpt-5.6-terra，不改60秒HTTP尝试、120秒逻辑规划及300秒Run预算。旧ccc8公共选择Run保持FAILED：6次真实HTTP中两个逻辑规划各2次HTTP后120秒预算耗尽，持久恢复2/2后绝对300秒停止；无SQL与回答，不记为公共选择成功。Semantics完整授权事实保持；重复definitionBinding仅经逐项相等校验后采用字典引用，完整解引用语义不变且仅在JSON字节更短时使用。真实旧f685字节码与新冻结源码的四种提示配置逐一输出核对；该B问题personal=true/shared=false，26992字收敛至13771字。此处没有把文本长度改进当模型效果证明。

原子缓存接入共享服务同次成功响应的qwen-encoding-v1合同：完整profile及SHA、实际model/revision/tokenizer/preprocessor/pipeline/package/runtime计算身份、每条完整原文UTF8 SHA和IEEE little-endian float32向量SHA。V63只新增nullable证明与来源字段，旧向量不回填。仅同项目、完整原文与builder/source fingerprint、授权物理绑定、注册model/version/dimension和可信实际profile完全相等的较早PUBLISHED版本可复用；正常目标写入同时重核锁定目标、来源、发布状态与实际向量。未知profile或正常驱逐返回503时仍MISS并走原编码入口。组件测试包括完整输入/权限/来源/model/dimension/project/发布状态/实际revision反例、来源与目标竞态、八worker重复领取、独立Python校验格式。共享历史真实1024响应的Java只读解析另4项通过，0新模型调用，不是本仓库新索引正例。

`source-review-24`保留工作区patch、全部changed-sources.tar.gz及SHA清单。`scripts/test-backend-with-databases.py --output .../backend-package-16.log`于12:47:36UTC启动、13:02:32UTC真正Maven BUILD SUCCESS/exit0，JDK21路径、2CPU/512MiB、原单Surefire fork、全*Test与*IT。167份fresh XML与Maven计数一致996/996PASS，0FAIL/ERROR/SKIP，64个后端改动源稳定。原子身份5、适配器/真实loopbackHTTP7、真实PG缓存19、完整Payload3均在本全量产物内通过。外层driver归档finalName错误实际exit1独立保留原脚本与trace；`finalize-backend-full16-artifacts.py`仅从实际成功的semevosql-backend.jar归档绑定，0重复测试。

immutable JAR为`b4e7ccf86c1777c975a0b5ab61ffe6253e83ba797e58b32848794fa911033a6f`，正常受管backend-only deploy13已完成，web12原68文件保持。旧f685镜像保留为`semevosql/backend:before-deploy13-f6857a41`。`deploy13-atomic-migration-state-preservation-1.json`原生8项通过：旧284 Run终态、45 document工作义务、v15六vector正文和attempt保持；V63正常迁移、原47 embedding rows全部证明NULL、pub274仍DONE9且active15。该报告最初boundary文本误写45历史vector，实际facts与检查均为47embedding/45document；此处明确更正数量，既有报告原字节保留。CUA正常重新登录sem_member_b，恢复原v1.2.0及ccc8失败history截图；尚未发送新问题。

入口仍 http://127.0.0.1:3303/semevosql/ 与 http://127.0.0.1:18093。下阶段先正常成员B公共选择Run／真实审批／SQL与独立111真值，再通过管理员现有CLONE/validate/publish两内容等价技术版本验收可信未来缓存，不改v15/旧向量/个人grants，不把未激活技术版本当业务变化。当前缓存实现和组件完成、真实新发布复用未测，B/E/F及其余原8具体闭环仍待验。正式评测仍34已执行／666未入场，原评分8P24F2ERROR与同Run v3 12P20F2ERROR并列，原Gold和旧失败保留；不恢复旧700循环。


## 2026-10-03 13:21UTC 公共选择实际失败与用户切换范围

新 b4e7 / web12 的正常 member B Run `24ecef63-5444-40eb-be51-cb6fc1cb627f`于13:16:22.133912–13:21:24.065038UTC自然FAILED／INTERACTIVE_QUERY_TIMEOUT：7 checkpoint、0问询／答复／SQL尝试／结果，owner与lease清空。实际5次HTTP：增强1、分解1、首逻辑规划2、恢复后规划1；首逻辑119923ms超时，持久恢复1/2后绝对300秒停止。因此不能称穷尽5次HTTP或恢复2次。规划系统提示13771字符、用户29294字符，提示收敛已部署，但未产生回答。个人定义30/2、两个结构、六授权与五使用逐行不变；这是状态保护PASS，不是业务回答PASS。`member-b-public-choice-terminal-deploy13-1.json/.sql/.png`及`member-b-public-choice-failure-diagnosis-deploy13-1.json`保留当前Run和完整实际HTTP/恢复记录；旧三个失败Run仍保留。

用户现在明确暂停剩余功能补全和大规模正式验收，转为全页面产品UX／视觉／导航使用逻辑优化和代码结构整理。所有新CLONE、缓存发布试验、模型问题、700和正式矩阵停止入场，已启动Run均正常终态，不SQL改变结果。原子缓存实现与组件检查已经部署，正常两次公共发布的真实缓存复用尚未验收。700仍34终态／666未执行；原8P24F2ERROR与同Run独立v3 12P20F2ERROR并列，Gold不改。D09时间门禁业务规则尚待选择，保持原规则。其余八项实际缺口详见`task-status-user-pause-ux-scope-1.json`，以后恢复时从这些证据继续，不把暂停或UI优化当业务验收完成。

当前运行：backend immutable `b4e7ccf86c1777c975a0b5ab61ffe6253e83ba797e58b32848794fa911033a6f`，完整996项／167fresh XML通过；frontend web12／68文件。入口 http://127.0.0.1:3303/semevosql/，API http://127.0.0.1:18093。接下来仅前端共用导航、页面状态与响应式一致性、API领域模块拆分、前端完整verify和只读真实CUA验证；后台、发布规则、预算、已有业务数据不变。统一README、Git历史和push由根代理处理，本子任务不commit/reset/push或重写历史。
