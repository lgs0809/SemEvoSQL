# 资产交换契约 1.0／1.1

权威字段定义见 schemas，不按教学片段自行加字段。本协议用于离线产物；目标后端需要对应的导入适配和契约测试，不能把文件直接发送到未知接口。

## 元信息

`source-schema.json`：formatVersion、dialect(mysql/postgresql)、tables。每表标识 datasource/schema/table；columns 包含 name、标准 dataType、nativeType、nullable、可选comment；primaryKey、uniqueKeys、foreignKeys 显式列出。没有物理约束时数组为空，不能根据名称猜外键或唯一键。元信息可注明原生类型，但程序只按标准类型做v1检查，标准类型映射的真实性由导出程序负责。

指纹算法：整个元信息对象按对象键排序，数组顺序保持，UTF-8、ensure_ascii=false、JSON紧凑分隔符，不含额外换行；SHA-256前加`sha256:`。不要在元信息里加入自指纹字段。使用配套指纹脚本计算。报告里的catalogSha256则是资产文件原始字节Hash，两者不要混用。

## 资产

根字段：formatVersion、sourceSchemaFingerprint、catalog、evidence、unresolvedIssues。

- entities：code/name/description/grain/primaryKey/source/attributes/filters。code是逻辑标识，SQL名称单独存映射中。
- source.tables：物理来源及alias；base指定根alias。joins顺序接入新alias，只支持同数据源 left/inner 等值连接。右侧连接键必须覆盖元信息中的主键或唯一键，以免改变根实体粒度。
- entity.primaryKey：逻辑属性名列表，必须映射到根表非空唯一键。宽表抽订单、历史选当前、没有可靠唯一约束的实体需先解决映射能力／身份依据，v1不能只写一段description代替可执行规则。
- attributes：直接映射 `{source: alias, column: physicalName}`；dataType为integer/decimal/string/boolean/date/datetime；unit可选；enumValues只列有依据的值，不宣称自动穷尽全部值。
- filters：实体固定筛选或指标聚合前筛选；列表按AND结合。支持eq/ne/gt/gte/lt/lte/in/not_in/is_null/is_not_null。任意OR树、子查询过滤尚不在v1。
- metrics：单实体聚合，timeAttribute为已定义的日期属性或null（确实无默认时间维度时）。实体固定筛选与指标筛选共同生效；unit记录最终输出单位。
- dimensions：命名维度引用实体属性；v1不包含自由表达式维度。
- relationships：实体间连接键与one_to_one/many_to_one/one_to_many，唯一侧须覆盖声明的实体主键；关系存在不表示任意JOIN不会放大指标，消费时仍需粒度检查。
- rules：v1仅属性数值闭区间规则，显式minimum/maximum，不从百分号推导0～100。没有约束就不生成。

1.0 继续拒绝所有新字段。1.1 使用 `semantic-catalog-1.1.schema.json`：实体、属性、指标、维度、关系、规则可附带 `retrieval: {queryExpressions: [...], queryContexts: [...]}`，两个数组必填但可为空，各最多16个不同字符串，每项1～300字符且不能全为空白或包含控制字符。问法与场景只进入完整模型的检索投影；正式业务描述仍是 description，不能借此创建同义绑定、公式或权限。

1.1 的指标还可带 `valueRange: {minimum?, maximum?, minimumInclusive?, maximumInclusive?}`，至少一个数值边界，开闭标记须有对应边界，默认闭区间，拒绝空区间和反向范围。范围针对指标最终输出尺度。缺省范围的增长率150%和-20%不因单位被拒绝；属性规则和指标输出约束不能混用。

## 指标表达式

表达树而非可执行Python或任意SQL。属性叶子 `{attribute: code}`，指标引用 `{metric: code}`，数值字面量 `{literal: number}`。

- 单参数聚合：sum/avg/min/max/count_distinct，参数名`arg`；count_rows无参数。
- 二元算术：add/subtract/multiply/divide，参数名left/right。
- divide必须显式`onZero: "null"`，只在业务认可该处理时使用。不能偷偷把零改一或返回零；业务要求不同则记录能力缺口。
- 属性表达是行级，聚合是汇总级；禁止嵌套聚合和聚合结果与未聚合字段混算。指标必须含聚合。
- metric引用只允许同实体、无自身独立filters的指标；禁止循环。被引用指标的时间维度必须与调用方一致。独立过滤／时间的分子分母、多阶段计算需扩协议及编译器，不能把条件丢掉。
- NULL遵循待接入后端的SQL聚合语义：count_rows计行、count_distinct忽略NULL，sum/avg/min/max忽略NULL且无非空输入时NULL。不得自行填零；nullability、时间时区等仍需业务确认及后端契约验证。

## 依据和未决项

target语法：`entity:order`、`entity:order/attribute:paid_amount`、`metric:payment_amount`、`dimension:region`、`relationship:order_user`、`rule:amount_range`，总体问题用`catalog`。

每个资产和属性至少关联一条依据：kind=document/user_confirmation/schema，source、location、statement必须具体。物理结构只能证明物理事实，不证明金额口径；user_confirmation只有用户实际回答才能使用。脚本检查覆盖与引用，不验证材料真实性、推断准确性或用户身份。

unresolvedIssues包含target/question/blocking。影响公式、粒度、单位、时间或映射的缺失必须blocking=true并阻止交付。非阻塞项仅用于不改变已交付计算含义的补充事项，不能把关键缺失改成warning规避。未纳入子集的需求在初始化说明中单列，不创造未知target。

## 校验结果

退出0＝必需离线检查完成且无错误；1＝输入或资产错误；2＝依赖／校验器／报告写入故障。报告包括valid、validationScope、validatorVersion、formatVersion、catalogSha256、sourceSchemaFingerprint、schemaContractFingerprint、importerCompatibility、errors、warnings；初始化故障时部分字段可缺失或null。

`valid=true`不保证导入器兼容、不保证当前远端结构未变、不证明公式业务正确。目标后端须对这份协议和相同正反例测试，再完成权限、幂等、版本和事务导入。报告不参与授权。


## 1.2 共享含义及明确模型角色

严格Schema和完整正反例位于 `schemas/semantic-catalog-1.2.schema.json`、`examples/shared-catalog-1.2.json` 及 `examples/shared-negative-cases-1.2.json`。catalog增加definitions、bindings和enumDictionaries三个必需数组；定义以code＋revision定位，绑定以model＋code定位。定义kind区分METRIC／DIMENSION／ATTRIBUTE，specification使用声明类型的参数；模型字段映射必须恰好覆盖这些参数，类型及指标grainKeys必须匹配。绑定不能另带公式或JOIN。角色与已确认aliases不同于retrieval问法。字典以code＋revision定位，条目value／label／aliases保留独立身份；绑定可显式转换代码，不自动推导模型间关系。定义、字典和绑定分别需要 `definition:code@revision`、`dictionary:code@revision`、`binding:model/code` 证据。

后台保留不可变定义修订并在每个版本登记适用绑定，旧资产分别迁移为各自原模型的绑定，不按名称合并。旧包和旧已发布版本的内容指纹保持兼容。新的共享定义仅通过受控参数 AST 编译；历史表达式迁移保留为隔离的legacy表示，不允许在1.2输入中伪装新AST。

直接属性的展示／分组复用其已有 ATTRIBUTE 定义及精确模型／字段绑定；显式命名维度优先。显式共享属性使用 AST_V1_2；正常导入中未共享的属性使用不可变 LEGACY_PROJECTION，并保留原定义 revision、角色、字段与权限，不另造业务含义或修改已发布指纹。仅直接字段、启用模型和属性、allowProjection=true 且 allowSendToLlm=true 可以成为查询投影；计算、时间粒度、连接和多义角色仍需独立治理或自然语言确认。查询临时投影身份进入审批及冻结恢复，不能靠名称或结果值重绑定。

报告中的semanticReview只生成当前资产事实、证据与复核方面，businessQuality明确为not_assessed_by_program。初始化模型应结合真实资料执行复核和问询，在说明中保留决策；此字段不授权导入或发布。
