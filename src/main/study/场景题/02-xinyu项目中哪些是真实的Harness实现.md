# 场景题：xinyu 项目里哪些能对应到"Harness"的实现？

> 提醒：这道题的诱惑是"编造"一些听起来很完整的机制去凑"Harness"的标准组件（熔断器、Hook 等）。真实策略应该是：先把项目里本来就存在、有文档背书的东西对应过去（够讲了），没有的部分诚实说"没有，这是可以优化的方向"——面试官追问细节时经得住考验，比编造更有说服力。

## 一、可以直接讲、有据可查的 Harness 组件

| Harness 组件 | xinyu 项目里对应的真实实现 | 出处 |
|---|---|---|
| **Agentic Loop（编排引擎）** | LangGraph `StateGraph`，每个 Agent 是 validate→generate→validate_output→translate→persist 这样的节点链，条件边做错误路由 | `02-系统架构介绍.md` |
| **Tool 系统 / MCP Client** | `MultiServerMCPClient` + `langchain-mcp-adapters` + `create_react_agent`，6 个 MCP 服务（角色/用户/短对话历史/星梦库存/记忆画像/世界观） | `02-系统架构介绍.md`、`04-面试要点总结.md` |
| **Context/Prompt 管理** | XML 结构化提示词（12 个语义分段），`DEFAULT_PROMPT_VERSION` 版本化管理，新版本新增函数不覆盖旧版本 | `02`、`04` |
| **Output Parser（输出鲁棒性层）** | 4 层兜底：剥离 `<think>` 块 → 提取 JSON 围栏 → `json.loads` → 去尾逗号重试 → `json_repair` 终极兜底 | `02-系统架构介绍.md` |
| **Error Classifier / 错误码体系** | `PARAM_INVALID` / `LLM_FAILED` / `PARSE_FAILED` / `SCHEMA_FAILED` / `RULE_FAILED` / `MCP_FAILED` / `MCP_NOT_CONFIGURED`，LangGraph 条件边按错误码路由到 END | `02-系统架构介绍.md` |
| **Model Gateway（多模型路由）** | AgentRun 提供 OpenAI 兼容网关，批处理走 `deepseek-v4-flash`，实时场景走 `qwen3-max` | `README.md`、`02` |
| **可观测性 / Execution Logger** | `execution_log.py` 结构化日志，`request_id` 贯穿全链路，`LOG_FULL_PROMPT` / `LOG_FULL_AGENT` 调试开关，ARMS 监控 | `04-面试要点总结.md` |
| **限流/Guardrail（业务侧权限把关）** | 星梦邀请四维限流（10分钟冷却/每日8次/拒绝5分钟冷却/完成30分钟冷却）、Redis 分布式锁防重复触发、预扣费机制 | `05-业务面试20问.md` |
| **幂等设计** | `inviteId` 幂等、`idempotencyKey` 格式 `short_chat_summary_ready:{userId}:{saveId}:{sessionId}:{cursor}` | `短对话全链路解析.md` |

这几条拼起来就是一个完整、真实的"自建 Harness"故事——跟《Claude Code 与 DeepSeek Harness 对比与实践》里给"DeepSeek 自建 Harness"画的架构图（Model Gateway / Error Classifier / Output Parser / MCP）几乎能一一对应。**不是编的，是本来就存在，只是之前没用"Harness"这个词去命名它。**

## 二、可以合理"类比"，但措辞要留有余地的部分

| 类比 | 真实情况 | 建议措辞 |
|---|---|---|
| Subagent 隔离 | 每个 Agent 是独立部署的 Serverless 函数，不是同一主循环内派生的临时子代理 | 说"每个 Agent 是独立上下文的执行单元"，不要说"实现了 subagent 机制"（这是 Claude Code 特定概念，生搬硬套会被识破） |
| Permission Gate | 没有 Claude Code 那种"工具调用前弹审批"的机制，但有业务层的预扣费/限流/规则引擎兜底 | 说"业务层的 Guardrail 把关"，不要说"权限审批系统" |

## 三、目前真的没有、别硬说"已经做了"的部分

| 组件 | 实际情况 | 面试话术建议 |
|---|---|---|
| **熔断器（Circuit Breaker）** | 文档里没有滑动窗口失败率 + half-open 探测这类机制，只有"重试 + 记录失败 + 不阻塞其他角色"的尽力而为策略 | 不要说"我们实现了熔断器"。可以说："目前是重试+错误码兜底，没有做熔断器，如果要进一步加固，我会加一层滑动窗口熔断，失败率超阈值切到 qwen3-max 兜底"——诚实的同时展示工程思考，是加分项 |
| **Hook（PreToolUse/PostToolUse）** | xinyu-agents 没有这个概念 | 不要提，这是 Claude Code 专属机制 |
| **重试的指数退避细节** | 文档没有明确写退避算法，只写了"局部失败不阻塞整体" | 如果被问具体退避策略，诚实说"没有细化到指数退避，目前是失败即跳过记录，重试逻辑比较简单"，然后转向确实做了的 4 层 JSON 修复兜底 |

## 建议的表达框架（一句话总结）

> "我们没有用'Harness'这个词，但 xinyu-agents 本质上就是一个自建的多模型 Harness：LangGraph 做 Agentic Loop，MCP 做工具解耦，4 层 JSON 修复 + 错误码体系做输出鲁棒性，AgentRun 网关做模型路由。跟业界成熟的熔断器/Hook 机制比，我们目前偏简单——这也是我认为可以继续优化的方向。"

这个说法完全基于真实文档，经得起追问，还顺带展示了"知道业界更完整的方案长什么样、也知道自己项目的边界在哪"——这比编造更有说服力。

## 出处

- `02-系统架构介绍.md` — MCP集成模式、错误处理策略、JSON解析鲁棒性设计
- `04-面试要点总结.md` — MCP使用细节、技术亮点总结
- `05-业务面试20问.md` — 邀请卡频率限流设计
- `短对话全链路解析.md` — 幂等键格式
