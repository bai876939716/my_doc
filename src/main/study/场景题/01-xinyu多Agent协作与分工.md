# 场景题：xinyu 项目中多个 Agent 是怎么协作的？怎么分工的？

## 一句话答案

xinyu 的多 Agent **不是"互相调用、互相协商"的编排模式**，而是"各自独立、单一职责，由 Java 业务层按事件分别触发，通过数据库/MCP 间接联动"。容易被误解成 intent-router 直接分发给 worker agent 的编排模式（比如 LangGraph 里常见的 Orchestrator-Worker 模式），实际上完全不同，这个区别是面试里最容易讲清楚工程理解深度的点。

---

## 一、五个 Agent 各自独立部署、职责单一，互不调用

| Agent | 触发方 | 触发时机 | 产出 | 模型 |
|-------|--------|----------|------|------|
| `stranger-feed-agent` | Java Core 批处理 | 陌生角色卡片曝光 | 8条多语言动态 | deepseek-v4-flash |
| `acquaintance-feed-agent` | Java Core | 用户离线时 | 1条个性化动态 | qwen3-max |
| `xingmeng-content-agent` | Java Core Cron | 每日06:00 | 50+条星梦/星事（5阶段批处理） | deepseek-v4-flash |
| `xingmeng-opening-rewrite-agent` | star-dream-service | 用户进入星梦瞬间 | 1条实时改写开场白 | qwen3-max（快） |
| `topic-intent-agent` | auto-conversation-service | 短对话每10轮 | 意图分类结果 | qwen3-max |

它们是 5 个独立的 AgentRun Serverless 部署，代码里**没有任何一个 Agent 会去 HTTP 调用另一个 Agent**。

## 二、真正的"协作"发生在 Java 微服务层，Agent 只是被单点调用的推理算子

以真实代码路径（`短对话全链路解析.md` + `diagrams/12-短对话星梦联动流程.puml`）为例，走一遍"意图识别 → 邀请卡 → 星梦"这条最能体现"多 Agent 协作"的链路：

```
1. 用户发短对话消息 → short-chat-service 走正常 LLM 回复（这一步不经过Agent，是Java侧直接调LLM）
2. short-chat-service 计数 roundCount，每10轮 → 异步通知 auto-conversation-service
3. auto-conversation-service → 调用 topic-intent-agent（意图识别）
4. topic-intent-agent 返回 intent=star_dream_invite → 回调 auto-conversation-service → 再回调 short-chat-service
5. short-chat-service 拿到意图结果后，Dubbo RPC 调 star-dream-service 创建邀请卡（这一步不经过任何Agent）
6. 用户接受邀请 → star-dream-service 建星梦 → 如果关系等级和批处理生成开场白时不一致
   → star-dream-service 才会调用另一个 Agent：xingmeng-opening-rewrite-agent 实时改写开场白
```

**关键点**：`topic-intent-agent` 和 `xingmeng-opening-rewrite-agent` 全程没有直接通信过，是 `auto-conversation-service`、`short-chat-service`、`star-dream-service` 这几个 **Java 服务在中间充当"调度中枢"**，把上一个 Agent 的产出变成业务状态（邀请卡、星梦记录），下一次业务事件触发时再决定要不要调用下一个 Agent。

## 三、批处理型 Agent 和实时型 Agent 之间是"接力"而非"调用"

`xingmeng-content-agent` 每天 06:00 批量生产好开场白存进库，`xingmeng-opening-rewrite-agent` 在用户真正点进星梦那一刻，读取批处理产出的旧开场白 + 当前最新关系等级，做二次加工重写。这是一种"上游产出、下游按需消费再加工"的接力关系，中间的衔接介质是**数据库记录**，不是 Agent 间的消息传递。

## 四、分工的两条切分原则

- **按实时性**：批处理型（stranger-feed / xingmeng-content）走 Cron，用便宜的 `deepseek-v4-flash`；实时型（topic-intent / xingmeng-opening-rewrite）走同步/准同步调用，用响应更快的 `qwen3-max`
- **按业务场景**：每个 Agent 只服务一种业务场景，提示词和 MCP 工具集互不重叠，出问题时天然容易定位是哪个场景的 Agent 出错

## 五、共享状态靠 MCP + 数据库，不靠 Agent 间通信

所有 Agent 需要的"跨 Agent 共识信息"（角色关系等级、历史记忆、库存内容）都是**各自独立通过 MCP 工具查 Java Core 拿到的**，而不是从上一个 Agent 的输出里继承。比如 `xingmeng-content-agent` 和 `xingmeng-opening-rewrite-agent` 都会查关系等级，但都是各自查一次，不存在"谁把等级信息传给谁"的直接依赖。

## 面试话术小结

> xinyu 项目里的多 Agent 更准确的说法是"多个单一职责的 Serverless 推理服务，由 Java 业务层按状态机和事件驱动分别调用"，而不是"一个 Orchestrator Agent 用 LangGraph 把多个 Worker Agent 编排在一条图里"。好处是每个 Agent 可以独立扩容、独立换模型、互不影响；代价是跨场景的业务规则（比如"意图识别之后要不要建邀请卡"）都写在 Java 层而不是 Agent 层，Agent 本身很"薄"，复杂度都在 Java Core 的状态机和消息队列编排里。

这和练习项目"灵犀智能客服"里 `intent-router-agent` 直接用 LangGraph 分发给 `refund-negotiation-agent` 等 worker agent 的编排方式是两种不同的多 Agent 协作范式——面试时如果被问"你怎么理解多 Agent 协作"，这个对比本身就是一个很好的展开点。

## 出处

- `README.md`（xinyu-new 项目文档）— 五个 Agent 汇总表
- `短对话全链路解析.md` — Rolling Flush 主编排器代码路径
- `diagrams/12-短对话星梦联动流程.puml` — 意图识别→邀请卡→星梦接受/拒绝完整时序
