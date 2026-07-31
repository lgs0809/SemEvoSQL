# SQL-03／04／05：业务查询、定义冲突与普通页面使用

使用既有本地project3／发布版本11／只读datasource2，保留原学习历史。关联种子包含200客户、50商品、2000订单、4000明细、204退款。以下是开发回归，不是300个独立问题或700次留出评测。

`deploy/acceptance/query-regressions-v1.json`固定八类问题、发布指标、输出字段及独立参考SQL：全部状态订单数、订单记录金额、包含NULL的平均金额、下单月份分组、固定取消状态、跨月支付时间、成功退款次数、成功退款订单去重数。最后两者分别按退款行及订单标识计数，不能混用。

`scripts/run-query-regressions.py`复用普通会话与审批接口。每个案例使用独立会话／Run；仅批准预期发布指标、版本和无个人绑定的计划，遇到额外问询或不同计划正常取消。真实模型规划、原生检查点、实际只读SQL、结果产物与独立SQL分别核验，不写审批或成功状态，不提交反馈来帮助结果通过。两工作线程的完整重跑8/8通过，每项10个检查；证据目录`sem-development-regression-full-precision-2`，汇总`sem-development-eight-family-verified-1.json`。

首轮7项中5通过、2失败，原目录`sem-development-regression-expanded-1`保留：一项实际SQL成功，但验收脚本将PostgreSQL高精度NUMERIC先解析成浮点数，误报平均金额不符；另一项真实模型请求达到既定两次恢复额度后失败，没有SQL。修正独立基准读取为精确小数字符串，整数不变，不引入误差容忍；9项证据校验测试通过，包含末位不同必须失败。另两次普通新查询各自通过，随后完整八类重跑通过。原失败不能倒改为通过，既定运行重试预算未调整。

浏览器另实际输入普通中文：

> 2026年第一季度成功退款共发生多少次？按退款成功的时间统计，同一订单有多笔成功退款要分别计数。

正常批准后，Run`7bfad27b-eebc-4299-ad50-4ed19ee50b2a`实际返回68；发布指标`successful_refund_count`，按`refunded_at`统计，范围2026-01-01至2026-04-01，不把退款次数改成退款订单数。独立参考SQL见`scripts/sql/quality-successful-refund-count-oracle.sql`，结果与实际回执、页面产物、权限、版本、原生检查点及模型规划14项核验通过。核验后通过页面“确认结果正确”，17项带采纳验证通过，反馈仍绑定原查询Episode。证据`sem-refund-count-browser-final-1.json`、`quality-2.json`、`feedback-1.json`、`feedback-quality-2.json`（后三个文件同一前缀）及对应SQL、截图保留。此查询采用确定性编译，不声称执行了必要模型后置复核。

反馈验证第一轮误填指标别名，三项检查失败；正确使用实际发布指标后通过，错误验证报告保留。其他平均值／网络旧失败也保留。

## 重跑

在仓库根目录执行，每次使用新目录；不会清库或覆盖既有报告：

```sh
SEM_PROOF="$(mktemp -d /tmp/semevosql-business.XXXXXX)"
python3 scripts/seed-quality-business.py --output "$SEM_PROOF/seed.json"
python3 -m unittest discover -s scripts -p test_result_acceptance_evidence.py
python3 scripts/run-query-regressions.py --workers 2 --output-directory "$SEM_PROOF/queries"
docker exec -i semevosql-acceptance-metadata-db-1 psql -X -v ON_ERROR_STOP=1 -U acceptance -d semevosql_quality_business_v1 < scripts/sql/quality-successful-refund-count-oracle.sql
```

手动页面地址：http://127.0.0.1:3303/semevosql/chat?projectId=3 。使用`deploy/.acceptance-private/accounts.json`中的既有sem_member_b账号，普通中文提问，正常审批，再查看结果、依据与执行流程。凭据不写入报告。完整开发问题集中的平均值保留精确小数，不先转浮点数。

当前后端仍为919项完整回归通过的`cb6b0ba68ec48deb97dc3a58d83699171547cd0b5fd991c5bd8a54eeeedc5c8f`；本批仅修改独立验收脚本及种子参考文件，无运行代码变更，不需要替换后端。既有四范围变更、共享、撤回及项目发布另有历史证据，完整依赖冲突／故障矩阵与规模留出评测仍需逐项收尾。

## 当前定义范围与公共冲突补验

原四种完整定义范围变更（USER→USER、USER→PROJECT、PROJECT→USER、PROJECT→PROJECT）已通过当前版本的只读历史复验：4个真实完成Run、33项检查通过；依据当时冻结问询／原范围与新范围，不用今天的个人head覆盖旧确认事实。`sem-retained-definition-four-scope-matrix-1.json/.sql`保留实际模型提议、完整定义文本／Hash、原生检查点及0次管理操作SQL／贡献使用。既有ASSOCIATE管理员决定的8路正常并发重放、同键异内容409、成员／外部／跨项目403再验11项通过；没有新增决定、发布任务或使用次数。

新的实际浏览器场景使用project2当前版本10，既有成员B。普通中文明确“以后份额金额按所有状态订单下单金额合计乘以零点六，按创建时间，单位元，不扣退款、不额外筛选”，并说明仅改未来默认、不否定旧结果，允许作为项目建议但不能直接覆盖公共定义。模型产生正常完整原文问询；页面确认原文及项目建议范围后才保存个人定义3／revision2。再正常批准执行，Run `3129831f-d1d3-43cf-86bf-99143542a0a6`使用CONSTRAINED_GENERATION、完整字段`p_3_2`，实际SQL返回252.000元，25个原生检查点。独立SUM(amount)×0.6为252，18项来源／数值核验通过；必要真实模型后置复核与最终SQL／产物13项核验通过。此前`31281a9c-5680-470b-99d5-a674fce31c2c`的INVALID_GOVERNED_SELECTION原失败不改写。

公共版本10及catalogHash原样保留；所有既有定义正文、Hash、来源和其他用户head未变。B原revision1的210元查询仍COUNTED／valid，新revision2只有本次实际QUERY计1次。新共享建议10为NEEDS_ADMIN_REVIEW／PUBLIC_CHANGE_REQUIRES_ADMIN，实际后台比较DONE，无发布版本；没有以确认个人定义代替公共覆盖审批。11项边界核验通过。正常页面“确认结果正确”后反馈5分／采纳、案例APPROVED，4项核验通过；刷新仍显示252元和已保存反馈。

证据前缀`sem-personal-public-conflict-`包含before、confirmation、approval、final、numeric、reviewed、boundary、feedback及refreshed-feedback截图；SQL旁存。当前查询是开发验收，不计入300个留出题。B个人默认已经正常更新为0.6，手动复测时不要仍预期210；历史旧查询仍保持旧定义与210元。

在仓库根目录执行只读复验：

```sh
SEM_CONFLICT="$(mktemp -d /tmp/semevosql-conflict.XXXXXX)"
python3 scripts/inspect-native-recovery.py --run-id 3129831f-d1d3-43cf-86bf-99143542a0a6 --output "$SEM_CONFLICT/run.json"
python3 scripts/verify-personal-query.py --evidence "$SEM_CONFLICT/run.json" --oracle-sql scripts/sql/acceptance-personal-share-60-oracle.sql --result-column p_3_2 --oracle-column personal_share_amount --expected-value 252 --require-text --account sem_member_b --output "$SEM_CONFLICT/numeric.json"
python3 scripts/verify-reviewed-result.py --evidence "$SEM_CONFLICT/run.json" --require-model-review --output "$SEM_CONFLICT/review.json"
python3 scripts/verify-query-feedback.py --evidence "$SEM_CONFLICT/run.json" --account sem_member_b --rating 5 --adopted --case-status APPROVED --output "$SEM_CONFLICT/feedback.json"
python3 scripts/verify-definition-conflict-boundary.py --help
```

最后一个验证器使用交付包的真实操作前快照、原有效使用证据、新查询数值和复核证据；不能用操作后新快照代替原baseline。四范围已经通过，剩余依赖冲突与故障矩阵须逐项清点，不再把已完成范围统一列为未完成。本批运行代码仍为cb6b／919通过版本，新增仅为验收工具、独立SQL及仓库记录。
