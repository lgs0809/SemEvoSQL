# 前端工作区与模块边界

前端使用 Vue3、Vue Router、Element Plus 和 TypeScript。已有业务规则由后端决定；本次导航与视觉整理不改变授权、发布条件、模型角色、请求预算或评分。项目详情保留全局导航，查询工作台保持专注布局。

## 页面与用户路径

| 页面 | 用途 | 主要下一步 |
| --- | --- | --- |
| `/login` | 使用现有工作区账号登录 | 恢复目标页或打开项目 |
| `/projects` | 项目、当前模型与状态 | 打开项目；正式模型可用时进入查询 |
| `/projects/create` | 依次填写项目、连接、资料并创建草稿 | 进入模型准备 |
| `/projects/:id` | 概览、模型准备、验证发布、持续改进、外部接入 | 由真实项目状态提示下一步 |
| `/chat?projectId=…` | 自然语言查询、业务问询、个人口径和可追溯结果 | 确认含义/范围与审批；查看历史依据 |
| `/connections` | 工作区数据库连接 | 在项目草稿绑定允许查询的表 |
| `/admin/models` | 模型配置与最近验证 | 修改配置后重新验证 |
| `/admin/settings` | 已配置/已验证的运行事实 | 需要时进入模型服务 |

管理员页面入口仅对既有管理员展示；成员项目详情回到可见概览。页面隐藏不是安全边界，所有请求仍由后端校验。查询页面中的个人定义通过自然语言描述，分享建议与正式公共口径保持区别，旧定义和历史使用不自动变更。

项目子页以 `section` 查询参数定位。`semantic` 指模型准备中的业务模型，`evolution` 指持续改进中的模型建议，二者不会混用。旧 `tab`、`versions`、`integration` 链接仍可读。`useProjectNavigation`统一地址→标签的同步和标签→地址写入，浏览器返回、刷新和成员权限回落遵循相同规则。

## 源码组织

- `src/views/`：路由页；`components/project/`：项目具体工作区；`components/chat/`：查询、问询、结果和诊断。
- `components/common/PageHeader.vue`、`WorkflowGuide.vue`：统一页头/步骤说明，不包含业务请求；`QueryHelp.vue`只提供自然语言使用指引。
- `composables/`：状态ful Vue 逻辑；`utils/`：无UI的纯规则/展示函数。不要把网络调用或权限决策放进展示工具。
- `services/semevosql.ts`：原114方法/53公开类型的兼容入口；页面原import路径不变。
- `services/semevosql/{projects,catalog,materials,onboarding,query,definitions,learning,governance}.ts`：领域API；只构造请求，不缓存或推断业务状态。
- `services/semevosql/contracts/`：按领域组织的原类型；`http.ts`：统一URL、错误消息处理和治理写操作的请求身份。认证/CSRF仍使用原`localSession.ts`。
- `styles/global.css`：基础样式与原通用帮助类；`styles/workspace.css`：产品主题、Element Plus令牌与跨页面视觉节奏。框架CSS先加载，产品主题后加载。页内特有样式保留scoped。

领域方法除 `answerClarification` 将同一个operator查询从 `this.currentOperator()`显式转为 `projectsApi.currentOperator()`外，其请求方法、URL、参数和头保持不变。问询身份在operator异步读取之前冻结，保留原幂等合同。

## 修改与验证

使用 Node.js 22.x（22.13.0 起）或 24+；CI 固定 22.13.0。运行 `npm run verify`，顺序为lint、vue-tsc、knip、Node原生测试和Vite构建。路由测试覆盖准备/改进同名子页区分、刷新链接、旧地址、成员隐藏标签回落；API测试覆盖请求作用域、真实参数/幂等身份、异步问询快照和审批默认。组件测试不冒充真实模型或发布成功。

Web-only部署使用`python3 scripts/deploy-tested-web-acceptance.py --test-log <成功verify日志> --output <新证据文件>`，保留旧静态资源且不重启后端。部署后通过Computer Use检查实际登录、全部页面/项目子页、历史结果与现有发布状态，并检查390宽度与桌面。新业务或模型请求需要单独恢复授权的原验收范围；当前UX验收不会创建发布/评分成功状态。
