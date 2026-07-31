# 模型重试 Token 记账验收

对应交接 G 的真实请求数／token 观测。本批不改变 Luna／Terra 模型、HTTP 重试次数或业务修复额度。

证据 E：`$LOCAL_EVIDENCE_ROOT/work/semevosql-20260927`。

## 复现与修改

旧 `SemEvoSQLModelGateway.captureUsage` 在整个逻辑调用上取所有响应用量的最大值，导致已返回 usage 但答案为空的失败尝试被少算。真实 loopback HTTP＋当前 Spring AI SDK 验证：第一请求 11 输入／2 输出、第二请求 17 输入／5 输出，旧结果仅 17／5，正确已报告合计为 28／7；两次空答案均失败时，旧失败指标仅 11／2，正确合计 22／4。

源码 `backend/src/main/java/cn/lgs/semevosql/model/SemEvoSQLModelGateway.java`：每次订阅尝试独立累计 usage 最大值，以正增量写入逻辑调用总额。一次流中的累计／重复帧不重复加，不同重试即使 provider response id 一样也分别计入；失败已观测用量保留。结果 DTO 与成功／失败 Micrometer 指标使用相同累计值。

## 实际测试

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home ./mvnw -o -pl backend -am -Dtest=ModelTokenUsageHttpTest,ModelHttpRetryBudgetTest,ModelGatewayFailureClassificationTest,ModelStreamCancellationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

`E/model-token-usage-tests-3.log`：22 通过、0 失败／错误／跳过。其中新增 `ModelTokenUsageHttpTest` 6 项使用真实本地 HTTP、生产 factory／SDK／gateway 与 SimpleMeterRegistry；仅注册表接线使用 mock，不替换网络或模型客户端响应逻辑。

- 阻塞响应为空→实际再次 HTTP→成功，2 次请求，28／7。
- 两次均空而失败，失败指标 22／4，逻辑失败只计 1 次。
- 流式累计帧 11／2、重复 11／2、11／4，最终仅 11／4。
- 流式已报告 11／2 后停滞→超时→新请求 17／5，最终 28／7；失败文本 discarded 不进入 recovered 答案。
- 两个逻辑调用并发、复用相同 provider id，各 28／7，指标总 56／14、逻辑调用 2、HTTP 4，不互相污染。
- 首次无 usage、下次有 usage，仅记录实际已观测 17／5，不根据重试次数编造用量。

## 失败与边界

`model-token-usage-baseline-1.log` 是新测试版权头格式失败，`-2.log` 是测试错误地把当前 SimpleMeterRegistry 当 AutoCloseable 的编译失败；均修正后保留。`-3.log` 4 项中 3 项失败，其中两项阻塞响应确定复现生产记账缺陷；首次断流 fixture 只有一个帧，SDK 尚未把它交给 gateway，不能把这一项的失败也写成已证实漏记。

`model-token-usage-tests-1.log` 修复后仍有上述单帧 fixture 失败。核对 [Spring AI v1.1.0 OpenAiChatModel 源码](https://github.com/spring-projects/spring-ai/blob/v1.1.0/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatModel.java)：其流处理中使用相邻两帧缓冲，把 usage 从下一帧带到上一帧。fixture 增加第二个同 usage 帧，确保故障前用量实际到达 gateway；没有通过编造生产计数让测试变绿。随后 `tests-2.log` 4 项通过，补并发和缺失用量后 `tests-3.log` 22 项全部通过。

用量仅表示 SDK 实际交付的 provider usage。远端未返回、连接断开前未交给 gateway、或提供方未报告的消耗仍未知，不宣称等于计费账单。本批未实现跨进程逻辑调用去重、所有直接流式入口预算统一或原生 Observation 与 Langfuse 整体去重；不能据此把交接 G 标为全部完成。HTTP 用量测试使用合成协议响应，不宣称真实模型理解质量。

## 全量与部署后的页面回归

`E/model-token-usage-full-backend-1.log`：382 项后端测试全部通过；专项 22 与全量存在重叠，不相加。`E/model-token-usage-deployment-1.json` 核对运行 JAR SHA-256 `3337e54814f1db67d88137b102b21f45682786d04f4a8e8e8de4611ecb648a00` 与被测试包相同、63 个前端文件一致、健康 UP。

真实浏览器新建会话，输入“请查询2026年1月已支付订单的支付金额总计，按支付时间筛选，不扣退款。”，页面确认 1 月 1 日至 2 月 1 日、paid_at 后批准执行。Run `4cda19dc-8bbf-4976-a5d0-1acb45063478` 实际 SUCCEEDED，结果 270.00；没有点击“确认结果正确”。`model-token-usage-browser-final.json`／`.sql` 保存数据库证据，`model-token-usage-browser-assessment.json` 16 项交叉核验全通过。截图 `model-token-usage-browser-pass.png`。这是正常真实模型查询回归；故障 token 用量的精确数值仍由上述本地 HTTP 注入验证，不把自然请求包装成已发生模型故障。
