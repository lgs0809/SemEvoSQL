# 模型 HTTP 传输重试预算验收

对应交接 G（SQL-01／02／05 的模型请求打点与预算统一），2026-09-27。证据 E 为 `$LOCAL_EVIDENCE_ROOT/work/semevosql-20260927`。

## 先测量后修改

通过本地 Reactor Netty HTTP 故障服务，接入实际 `DynamicModelFactory`、Spring AI 1.1.0、ChatClient、StreamLlmService 和 SemEvoSQLModelGateway。前 7 个 HTTP 请求在响应头前停顿，第 8 个返回合法 SSE。`E/model-http-retry-baseline-1.log` 记录：**gatewayAttempts=2，actualHttpRequests=8**。这是原叠加行为的测量通过，不能当成预算要求通过，也不是真实远程模型业务闭环。

## 已修复

- 网关每个逻辑调用持有一个 `ModelTransportBudget`，通过 Reactor Context 传递，阻塞 SDK 通过限定作用域 ThreadLocal 传到 RestClient；结束后恢复线程原上下文。
- 网关已有 max-retries 配置保留：总请求上限为初始 1 次加重试次数。进入网关的 WebClient 不再叠加连接重试。没有网关的直接流式调用保留原连接重试配置；本轮没有把它与图恢复混作一次调用。
- 禁止 SDK RetryTemplate、Apache 隐式重试和聊天 Netty 自动连接重试在配置预算之外额外发送请求。服务不支持 reasoning 参数时的降级也占普通网关尝试。
- 分别记录网关尝试次数和实际 HTTP 尝试次数；`ModelCallResult.httpAttempts`、脱敏 callId/purpose/httpAttempt/maxHttpAttempts 日志及 `semevosql.model.http.requests` 指标可用于核对。没有观测到实际 HTTP 的单元模拟返回 0，不冒充真实请求。
- 取消机制保留，两个并发调用不共享尝试计数。401 等确定性错误不重试。流式半截结果不拼接到下一次响应。

改动文件位于 `backend/src/main/java/cn/lgs/semevosql/`：`model/{ModelTransportBudget,SemEvoSQLModelGateway}.java`、`service/aimodelconfig/DynamicModelFactory.java`、`service/llm/impls/BlockLlmService.java`、`observability/SemEvoSQLMetrics.java`。测试 `backend/src/test/java/cn/lgs/semevosql/service/aimodelconfig/ModelHttpRetryBudgetTest.java`。

## 自动化与部署

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home ./mvnw -o -pl backend -am -Dtest=ModelHttpRetryBudgetTest,ModelStreamCancellationTest,ModelGatewayFailureClassificationTest -Dsurefire.failIfNoSpecifiedTests=false test
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home ./mvnw -o -pl backend -am package
```

- `E/model-http-budget-tests-2.log`：**16 通过**。6 个真实 HTTP 预算测试包括头前故障、阻塞 SDK 503、reasoning 降级、401、并发隔离；5 个既有实际传输取消测试和 5 个分流测试一起通过。
- 修复后同一网关配置：**gatewayAttempts=2，actualHttpRequests=2**。故障一直持续时只发 2 个 HTTP 请求，等待覆盖重试窗口后仍无迟到请求。
- `E/model-http-budget-full-backend-1.log`：**343 通过，0 失败／错误／跳过**。与专项有重叠，不累加。
- `E/model-http-budget-deployment-1.json`：JAR `1c666af9c9315403d17c97c1beb9831269102518bc7b74a3cbdd33cf2dc462e1`，运行身份一致，63 个前端文件一致，本地健康 UP。模型仍为已有 luna/terra，未改模型或凭据。
- 首次编译因 Reactor 泛型推断为 Object 失败，修复显式类型后通过；`model-http-budget-compile-1.log`、`model-http-budget-tests-1.log` 保留为失败证据。

## 仍需区分

本批证明网关内部与实际 SDK 的请求上限，不代表完整 G 已完成。图恢复跨进程的同逻辑模型调用计数持久化、所有直接流式入口到同一预算的收敛、传输故障全矩阵和 token/Observation 重复记录仍待后续核对。实际远程模型页面验收在下方补录，不能以本地故障服务替代。

## 已部署的真实模型浏览器回归

Run `7849d0c5-d5d9-4e33-a6ff-be08b74fb7e9`：真实页面新建会话，输入一月按支付时间的已支付总金额（不扣退款），核对 January 1 至 February 1 边界后批准。最终页面和业务库为 **270.00**，同 Run 正常完成并自动收录案例。`E/model-http-budget-browser-assessment.json` 16 项通过、`E/model-http-budget-retrieval-assessment.json` 10 项通过；FTS/VECTOR/RRF/RERANK 都实际参与。

原始证据 `E/model-http-budget-browser-final.{json,sql}`，截图 `model-http-budget-browser-pass.png`，HTTP callId/purpose/实际次数日志 `model-http-budget-real-call-counts.log`。这次真实请求未故意破坏外部服务，证明修复后的正常真实模型路径；重试耗尽和并发故障仍以隔离本地故障服务测试为证，二者不混称。
