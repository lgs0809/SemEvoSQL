# 多 Todo 结果与业务口径对应修复（B.9／B.10 验收发现）

三 Todo 的执行成功后，真实页面暴露两处错配：前端按消息 ID 缓存第一份 artifact，后端同时提供 Run 最新计划的口径；即第一项 270 配上第三项三月。自然语言总结的 270／300／360 正确，不能据此忽略表格错误。

本批将每项表格、问题、列名和时间口径共同从该 Todo 的 `DONE + review=PASS` 持久化记录生成；不拿兄弟 Todo 或 Run 最新计划补缺。部分完成只展示已验收项，损坏记录显示读取错误。多 Todo 保留请求级诊断与全部 SQL，但不显示一个错误的全局时间口径。单结果缓存改以消息、Run、artifact 三者标识。

## 文件

- `backend/src/main/java/cn/lgs/semevosql/task/QueryTaskAnswerService.java`：一次读取同 Run 的已验收任务及其结果、计划。
- `backend/src/main/java/cn/lgs/semevosql/run/QueryExecutionExplanationService.java`：任务解释与请求诊断分离。
- `backend/src/main/java/cn/lgs/semevosql/conversation/ProjectConversationService.java`：多任务消息提供 `taskAnswers`，不再提供误导的单一 artifact。
- `frontend/src/components/chat/TaskAnswerSection.vue`、`AnswerCard.vue`：逐项显示表格和口径。
- `frontend/src/views/ProjectChat.vue`、`frontend/src/services/semevosql.ts`：结果身份缓存及类型。
- `backend/src/test/java/cn/lgs/semevosql/task/QueryTaskAnswerPostgresIT.java`、`scripts/verify-task-answers.py`：真实数据库、接口与已验收结果核对。

## 已通过

以下 E 为 `$LOCAL_EVIDENCE_ROOT/work/semevosql-20260927`。

- `task-answer-tests-2.log`：14 项定向检查，0 失败／错误／跳过。其中 5 项 PostgreSQL，涵盖三份同 schema 不同月份、部分未完成、验收未通过、不同 Run 相同 taskId、损坏记录、空结果。数据均在 Testcontainers 临时库。
- `task-answer-full-backend-1.log`：327 项默认回归通过，0 失败／错误／跳过。与定向测试重叠，不相加。
- `task-answer-web-2.log`：lint、类型、未用代码检查、6 项测试、前端构建通过。
- `task-answer-deployment-1.json`：健康 UP，运行／测试 JAR 同为 `29c7f291f3a397d8ea582f1474f53d2d8e74f4e600b9bdaba3293660354eb323`；63 个前端文件一致。
- computer use 实际重新打开三 Todo 历史会话，三份表格分别为一月 270、二月 300、三月 360，各自月份边界一致。截图 `three-todo-fixed-january-browser.png`、`three-todo-fixed-february-browser.png`、`three-todo-fixed-third-browser.png`。
- `three-todo-ui-assessment.json`：14 项 HTTP／数据库比对通过。对应 `-response.json` 保存真实响应；与 `three-todo-browser-final.json/.sql` 的每项 accepted payload 和计划逐项相等。
- 三 Todo 真实模型执行、三次审批和 424.499 秒执行期限证据见[Todo执行期限验收](Todo执行期限验收.md)。本次展示复测使用同一已完成请求，没有重新调用模型或更改其成功状态。

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home ./mvnw -o -pl backend -am \
  -Dtest=QueryTaskAnswerPostgresIT,GroundedRequestSynthesisServiceTest,QueryRunPublicPresenterTest,ProjectMcpQueryFacadeTest \
  -Dsurefire.failIfNoSpecifiedTests=false package
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home ./mvnw -o -pl backend -am package
cd frontend
npm run verify
cd ..
python3 scripts/local-acceptance.py up --backend-test-log "$E/新的后端验证.log" --web-test-log "$E/新的前端验证.log" --output "$E/新的部署证据.json"
python3 scripts/verify-task-answers.py --evidence "$E/three-todo-browser-final.json" --output "$E/新的展示核对.json"
```

访问 `http://127.0.0.1:3303/semevosql/chat?projectId=2`，打开列表中 2026/9/27 12:38:57 的会话；Run `1a5b1291-8bcf-41c6-b8a7-96b27f36065f`，会话 `f34e8a1e-a30f-407f-9b0c-1d819523ea82`。当前列表时间显示沿用应用保存的无时区时间，不作为执行耗时依据。

## 失败、未测、受阻

- 初始页面错配截图 `three-todo-result-browser.png` 保留。`three-todo-fixed-second-browser.png` 只显示第一项部分内容，完整二月证据使用上述 `fixed-february`，不混称。
- `task-answer-tests-1.log` 首次构建因新文件许可证头检查失败，未运行测试；修正后上述第二次检查通过。
- 本批未完成：运行中更新／跨 Run 快速切换的完整浏览器竞态矩阵；B.8 按需目录、B.9 整模型编码、SQL 取消与恢复全矩阵、B.10 需求纠正分类、Git 历史整理。不能把本次结果展示通过算成整个项目完成。
- 本批无外部凭据／组件阻塞。
