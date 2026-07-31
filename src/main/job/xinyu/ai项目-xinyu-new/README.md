# 心屿 App AI 后端系统 — 项目深度解析

> 面向面试准备的完整技术文档，从工程师视角系统拆解两个核心项目。

---

## 项目概览

心屿 App 是一款以 AI 角色为核心的沉浸式社交产品，后端由两个项目组成：

| 项目 | 技术栈 | 定位 |
|------|--------|------|
| `xinyu-world-simulator-core` | Java 8 + Spring Boot 2.7 + Dubbo 3 | 业务核心微服务集群 |
| `xinyu-agents` | Python 3.12 + LangGraph + AgentRun | AI Agent 内容生产集群 |

两者通过 **AgentRun HTTP API** 解耦：Java Core 作为调度方，Python Agent 作为 AI 推理执行层。

---

## 目录结构（本文档集）

```
ai项目-xinyu-new/
├── README.md                              ← 本文件：项目总览与学习指南
├── 01-业务介绍.md                          ← 产品业务背景与核心功能
├── 02-系统架构介绍.md                      ← 技术架构设计决策说明
├── 03-数据模型说明.md                      ← 核心数据模型与数据库设计
├── 04-面试要点总结.md                      ← 面试高频问题与回答要点
├── 05-业务面试20问.md                      ← 业务场景面试题 20 道
├── 06-记忆系统设计.md                      ← 记忆系统完整设计文档
├── 07-记忆系统面试题精选.md                ← 记忆系统面试题 15 道
├── diagrams/
│   ├── 01-整体系统架构图.puml               ← C4 Container 级别架构图
│   ├── 02-陌生角色新鲜事生产流程.puml       ← stranger-feed-agent 流程
│   ├── 03-熟人新鲜事生产流程.puml           ← acquaintance-feed-agent 流程
│   ├── 04-星梦内容批处理流程.puml           ← xingmeng-content-agent (5阶段)
│   ├── 05-星梦开场白实时改写流程.puml       ← xingmeng-opening-rewrite 序列图
│   ├── 06-微服务架构图.puml                 ← 13个微服务全景组件图
│   ├── 07-星梦核心业务序列图.puml           ← 星梦完整生命周期序列图
│   ├── 08-端到端数据流图.puml               ← 请求→AI生成→落库全链路
│   ├── 09-关系等级与内容生产状态图.puml     ← 关系等级状态机 + 内容策略联动
│   ├── 10-MCP工具调用架构图.puml            ← MCP集成架构图
│   ├── 11-xinyu-agents子系统架构.puml       ← 五大Agent内部节点详细架构（含topic-intent）
│   ├── 12-短对话星梦联动流程.puml           ← 意图识别→邀请卡→星梦接受/拒绝完整时序
│   ├── 13-LLM调用全链路.puml               ← Java Core + Python Agent 双侧LLM调用路径对比
│   ├── 14-完整业务流程图.puml               ← 用户完整旅程：初见→短对话→星梦→内容沉淀
│   ├── 15-技术栈全景图.puml                 ← 技术栈全景图（含 HTML 交互版）
│   ├── 16-prompt-token-预算分配.puml        ← Prompt Token 预算分配图
│   ├── 17-prompt-token-裁剪流程.puml        ← Token 裁剪流程图
│   └── memory/                              ← 记忆系统专题图集（01~10，见06-记忆系统设计.md）
└── 简历相关/
    ├── xinyu-简历内容.md                    ← 简历项目条目 v1（详细版）
    ├── xinyu-简历内容-v2.md                 ← 简历项目条目 v2（精简版，推荐使用）
    ├── xinyu-技术栈详解.md                  ← 技术栈选型深度解析（含与竞品对比）
    ├── xinyu-埋点与解答.md                  ← 简历埋点 & 面试官追问参考答案
    └── xinyu-遇到的问题.md                  ← 面试 STAR 问题深度版（3个难题）
```

---

## 快速认识这套系统

### 用一句话理解

> 用户与 AI 角色互动（聊天 → 关系升级 → 触发星梦 → 沉浸式线下见面体验），AI 后端持续生产个性化内容投喂给用户。

### 核心创新点（面试重点）

1. **星梦系统** — 业界首创"AI 线下见面"沉浸式剧本互动
2. **关系等级驱动内容** — Lv0~Lv5 等级机制驱动内容生产策略动态变化
3. **5 阶段内容生产流水线** — 每日 06:00 批量为所有用户生产 12 时间窗个性化内容
4. **MCP 工具链** — Agent 通过 MCP 读写 Java Core 业务数据，完全解耦
5. **LangGraph 状态图** — 复杂多步 AI 工作流的工程化实现

---

## 两个项目的分工与关系

```
┌─────────────────────────────────────────────────────────────────┐
│                    xinyu-world-simulator-core                   │
│  (Java 微服务 — 业务规则 / 状态机 / 数据持久化 / 推送通知)           │
│                                                                 │
│   BFF(:8080) → character / user / trade / short-chat /         │
│               group-chat / star-dream / memory / core /        │
│               auto-conversation / inbox / admin / common       │
│                         ↕ Dubbo Triple                         │
│   PostgreSQL + Redis + RocketMQ + Nacos + Hermes               │
└──────────────────────┬──────────────────────────────────────────┘
                       │ HTTP AgentRun API (POST /openai/v1/chat/completions)
                       ↓
┌─────────────────────────────────────────────────────────────────┐
│                       xinyu-agents                             │
│  (Python AI — 内容生产 / 意图识别 / 自然结束判断 / 开场白改写)       │
│                                                                 │
│   stranger-feed-agent  (陌生角色新鲜事：8条多语言，MCP落库)         │
│   acquaintance-feed-agent (熟人新鲜事：1条，Java落库)              │
│   xingmeng-content-agent  (星梦批处理：5阶段，12时间窗)             │
│   xingmeng-opening-rewrite-agent (实时改写：<2秒)                │
│                         ↕ MCP HTTP Streamable                  │
│   6个 MCP 服务 → Java Core 数据读写                               │
└─────────────────────────────────────────────────────────────────┘
```

---

## 核心模块快速索引

### xinyu-world-simulator-core 微服务

| 服务 | 端口 | 核心职能 | 关键技术 |
|------|------|----------|----------|
| `soul-haven-web-service` | 8080 | HTTP BFF 网关 | Spring MVC + Dubbo Client |
| `soul-haven-user-service` | 13010 | 用户认证&档案&新手引导 | OAuth + Redis Token |
| `soul-haven-character-service` | 13020 | 角色卡&关系&羁绊 | 关系等级计算 |
| `soul-haven-core-service` | 13030 | Feed动态&Push&星球 | Feed系统 + 推送 |
| `soul-haven-short-chat-service` | 13040 | 短对话&意图识别 | Hermes长链 |
| `soul-haven-group-chat-service` | 13050 | 群聊&可靠消息 | Hermes子频道 |
| **`soul-haven-star-dream-service`** | **13080** | **★ 星梦核心业务** | **AgentRun + Hermes + 预扣费** |
| `soul-haven-admin-service` | 13070 | 运营后台配置 | Prompt模板管理 |
| `soul-haven-memory-service` | 13090 | 记忆&RAG | 向量库 + 相似度检索 |
| `soul-haven-auto-conversation-service` | 13100 | AgentRun调度 | 多Agent编排 |
| `soul-haven-inbox-service` | 13110 | 收件箱通知 | 已读/未读状态 |
| `soul-haven-trade-service` | 13120 | 贝壳&支付&订阅 | Apple IAP + Google Pay |
| `soul-haven-common-service` | - | 翻译&审核&LLM网关 | DashScope + 网宿 |

### xinyu-agents 五个 Agent

| Agent | 触发时机 | 单次产出 | 是否落库 | 模型 |
|-------|----------|----------|----------|------|
| `stranger-feed` | 陌生角色卡片曝光 | 8条多语言动态 | Agent MCP落库 | deepseek-v4-flash |
| `acquaintance-feed` | 用户离线时 | 1条单语动态 | Java Core落库 | qwen3-max |
| `xingmeng-content` | 每日06:00 Cron | 50+条星梦+星事 | Agent MCP落库 | deepseek-v4-flash |
| `xingmeng-opening-rewrite` | 用户进入星梦时 | 1条改写开场白 | 仅审计日志 | qwen3-max(快) |
| `topic-intent` | 短对话每10轮触发 | 意图分类结果 | 无（驱动邀请卡） | qwen3-max |

---

## 推荐学习路径

### 第一步：理解业务（30 分钟）
1. 阅读 [01-业务介绍.md](01-业务介绍.md) — 理解产品定位和核心业务
2. 看图 [diagrams/01-整体系统架构图.puml](diagrams/01-整体系统架构图.puml) — 建立系统全局认知

### 第二步：理解 AI Agent 层（1 小时）
1. 看图 [diagrams/02-陌生角色新鲜事生产流程.puml](diagrams/02-陌生角色新鲜事生产流程.puml) / [03](diagrams/03-熟人新鲜事生产流程.puml) / [04](diagrams/04-星梦内容批处理流程.puml) / [05](diagrams/05-星梦开场白实时改写流程.puml) — 四个 Agent 的工作流程
2. 看图 [diagrams/10-MCP工具调用架构图.puml](diagrams/10-MCP工具调用架构图.puml) — 理解 MCP 集成模式
3. 看图 [diagrams/08-端到端数据流图.puml](diagrams/08-端到端数据流图.puml) — 从请求到落库的完整链路

### 第三步：理解微服务层（1 小时）
1. 看图 [diagrams/06-微服务架构图.puml](diagrams/06-微服务架构图.puml) — 理解 13 个服务的职责划分
2. 看图 [diagrams/07-星梦核心业务序列图.puml](diagrams/07-星梦核心业务序列图.puml) — 最复杂核心功能的完整时序
3. 看图 [diagrams/12-短对话星梦联动流程.puml](diagrams/12-短对话星梦联动流程.puml) — 意图识别→邀请卡→星梦的跨服务联动
4. 阅读 [02-系统架构介绍.md](02-系统架构介绍.md) — 架构决策的 Why

### 第四步：深入 AI 与记忆系统（1 小时）
1. 看图 [diagrams/11-xinyu-agents子系统架构.puml](diagrams/11-xinyu-agents子系统架构.puml) — 五大 Agent 内部节点全景
2. 看图 [diagrams/13-LLM调用全链路.puml](diagrams/13-LLM调用全链路.puml) — Java Core + Python 双侧 LLM 调用路径对比
3. 看图 [diagrams/memory/09-短对话记忆完整流程.puml](diagrams/memory/09-短对话记忆完整流程.puml) — 记忆写入/读取全链路（含数据模拟）
4. 阅读 [06-记忆系统设计.md](06-记忆系统设计.md) — 记忆系统完整设计文档

### 第五步：面试准备（30 分钟）
1. 看图 [diagrams/14-完整业务流程图.puml](diagrams/14-完整业务流程图.puml) — 用户完整旅程全貌，面试叙述框架
2. 阅读 [04-面试要点总结.md](04-面试要点总结.md) — 高频问题与标准回答
3. 理解状态图 [diagrams/09-关系等级与内容生产状态图.puml](diagrams/09-关系等级与内容生产状态图.puml) — 关系等级机制（常被问到）
4. 准备亮点：LangGraph 工作流 / MCP 集成 / 记忆系统双层设计

---

## 技术栈一览

### xinyu-agents (Python)
- **运行时**: Python 3.12+，AgentRun Serverless SDK
- **工作流**: LangGraph (StateGraph) + LangChain
- **数据模型**: Pydantic v2
- **MCP 客户端**: langchain-mcp-adapters (MultiServerMCPClient)
- **部署**: 阿里云 AgentRun (Function Computing) + Serverless Devs

### xinyu-world-simulator-core (Java)
- **框架**: Spring Boot 2.7.18 + Spring Cloud 2021.0.5
- **RPC**: Dubbo 3.3.6 Triple 协议 + Protobuf 3.25.5
- **ORM**: MyBatis-Plus 3.5.5
- **数据库**: PostgreSQL + Redis (Redisson 3.27.2)
- **消息队列**: RocketMQ 5.2.0
- **长连接**: Hermes 3.5.0
- **服务发现**: Nacos (Spring Cloud Alibaba 2021.0.5.0)
- **文档**: SpringDoc OpenAPI 1.7.0
