# 心屿 AI 项目 — 技术栈详解

> 每项技术说明三件事：**是什么**（一句话定义）、**在项目中的应用场景**、**为什么选它**（与竞品对比的选型理由）

---

## 一、Java 微服务层

### 1. Dubbo 3 + Triple 协议 + Protobuf

**是什么**：Apache Dubbo 是 Java RPC 框架，Triple 是 Dubbo 3 的新一代传输协议（兼容 gRPC），Protobuf 是 Google 的二进制序列化格式。

**在项目中的应用场景**：
- BFF 层（soul-haven-web-service）调用各业务微服务（短对话服务、记忆服务、星梦服务等）的内部通信全部走 Dubbo Triple
- 每个业务域拆分为 `xxx-service-api`（Protobuf IDL 接口定义）+ `xxx-service`（实现注册为 Dubbo Provider）
- 服务消费方只依赖 API jar，不依赖实现，接口变更有版本控制

**为什么选它（对比 HTTP REST）**：

| 维度 | Dubbo Triple + Protobuf | Spring Cloud HTTP REST |
|------|------------------------|----------------------|
| 序列化 | Protobuf 二进制，强类型契约 | JSON，弱类型，字段名拼写错误运行时才发现 |
| 服务发现 | Nacos 自动注册，无需手写 URL | 需要额外配置 Ribbon/Feign |
| 性能 | Protobuf 序列化约快 5~10 倍 | JSON 解析 CPU 开销相对高 |
| 版本管理 | IDL 接口版本字段内置 | 需要手动维护 API 版本路径 |
| 适用场景 | 内部高频调用（BFF → 业务服务） | 对外公开 API 或跨语言边界 |

选 Dubbo 的核心原因：服务间调用频率极高（每次 AI 推理触发 3~5 次 MCP 工具调用，每个工具调用内部涉及 Dubbo 调用），Protobuf 的性能优势和强类型在这种调用密度下效果显著；且团队已有 Dubbo 使用经验，历史服务存量大。

---

### 2. RocketMQ

**是什么**：阿里巴巴开源的分布式消息队列，支持可靠消息投递、延迟消息、事务消息。

**在项目中的应用场景**：

| 场景 | 生产者 | 消费者 | 原因 |
|------|--------|--------|------|
| 记忆总结触发 | 短对话服务（达 40 轮时投递） | memory-service 的 MemorySummaryProcessor | 不阻塞 AI 回复主链路，PolarDB 宕机时消息不丢 |
| 星梦结束后多任务并发 | 星梦服务（结束时投递） | 回忆卡片服务、私信服务、消息 Tab、记忆服务 | 4 个下游任务并行执行，主流程 30ms 响应不等待 |
| 活动行为消息（规则引擎） | 各业务服务 | 规则引擎 Processor | 解耦行为来源，支持重放 |
| 关系等级升级通知 | 关系服务 | 内容生产调度服务 | 次日批处理的触发信号 |

**为什么选 RocketMQ（对比 Kafka / RabbitMQ）**：

| 维度 | RocketMQ | Kafka | RabbitMQ |
|------|----------|-------|----------|
| 延迟消息 | 原生支持（18 个级别） | 不支持，需额外方案 | 插件支持，不原生 |
| 事务消息 | 支持（两阶段提交） | 不支持 | 不支持 |
| 消息可靠性 | 同步双写，ACK 确认 | 依赖副本数配置 | 持久化可靠 |
| 阿里云集成 | 原生集成（ARMS、SLS） | 需额外配置 | 需额外配置 |
| 适用场景 | 业务消息（可靠性优先） | 日志流（吞吐量优先） | 短任务调度 |

项目中大量使用延迟消息（星梦 24 小时过期计时器、活动结束后延迟下架）和可靠投递（记忆总结不能丢），RocketMQ 是首选；且团队在阿里云体系，监控运维成本低。

---

### 3. PostgreSQL

**是什么**：开源关系型数据库，支持 JSON 类型、全文索引、行锁、MVCC 多版本并发控制。

**在项目中的应用场景**：

| 数据表 | 存储内容 | 关键设计 |
|--------|---------|---------|
| `tb_short_chat_message` | 短对话消息原文（role + content） | `memory_summarized` 字段控制 AI 可见窗口，历史消息永不删除 |
| `tb_memory_summary_task` | 记忆总结任务记录（miniMerge 计数） | 查 DB 行数代替内存计数器，服务重启不丢状态 |
| `tb_xingmeng_stock` | 星梦内容库存 | 12 个时间窗分区，Phase3 优先从此表读取 |
| `tb_relationship_level` | 用户与角色关系等级及积分 | 次日批处理 Phase1 扫描 `upgrade_pending = true` |

**为什么选 PostgreSQL（对比 MySQL）**：
- PostgreSQL 的 JSONB 类型方便存储 Agent 输出的结构化内容（开场白 JSON、时间窗配置），无需额外表设计
- MVCC 实现更纯粹，读不阻塞写，批处理写入时不影响实时读
- `tstzrange` 时间范围类型方便做"时间窗"区间查询（12 个窗口的起止时间）
- 团队技术选型统一，减少运维多套数据库的成本

---

### 4. Redis

**是什么**：内存型键值数据库，支持多种数据结构（String、Hash、Set、ZSet、List、Bit），天然支持原子操作和分布式场景。

**在项目中的应用场景**：

| 用途 | Key 设计 | 数据结构 | TTL |
|------|---------|---------|-----|
| 记忆总结幂等键 | `memory:summary:idempotency:{userId}:{charId}:{sessionId}` | String | 7 天 |
| 星梦分布式锁 | `xingmeng:accept:lock:{userId}:{saveId}` | String（SETNX） | 30s |
| 星梦邀请频率限制 | `xingmeng:invite:count:{userId}:{date}` | String（INCR） | 当日 |
| 节点信息本地缓存（规则引擎） | `rule:node:config:{nodeId}` | Hash | 5min |
| 活动名单布隆过滤器 | `activity:bloom:{activityId}` | Bit Array（手写） | 活动期间 |
| Nacos 配置缓存 | 由 Nacos SDK 管理 | 本地 Map | 实时同步 |

**选型说明**：Redis 在本项目中不做持久化存储，只做缓存、锁、计数三类场景。记忆总结的幂等键之所以用 Redis 而非数据库唯一索引，是因为幂等键只需要"存在/不存在"的语义、TTL 自动过期、且读写在关键路径上需要亚毫秒响应。

---

### 5. Nacos

**是什么**：阿里巴巴开源的服务注册中心 + 配置中心，支持服务发现、健康检查、配置热更新。

**在项目中的应用场景**：
- **服务注册**：13 个 Java 微服务启动时自动向 Nacos 注册，Dubbo Consumer 通过 Nacos 订阅 Provider 地址，无需硬编码 IP:Port
- **配置热更新**：记忆总结的关键参数（触发轮次 `trigger-round-count`、合并阈值 `mini-merge-threshold`、PolarDB 超时 `confirm-timeout-ms`）全部放 Nacos 动态配置，调参不需要重新部署服务
- **Token 裁剪预算**：各层 Token 上限（短期记忆最大轮次、长期记忆最大条数）通过 Nacos 管理，换更大 context window 的模型时只改配置

**为什么不用 Spring Cloud Config + Eureka**：
项目整体在阿里云体系，Nacos 与 Dubbo 3 的集成是原生的（Dubbo 内置 Nacos 服务发现适配），无需额外适配代码；Nacos 同时提供服务注册和配置，一套系统替代两套，运维成本减半。

---

### 6. XXL-Job

**是什么**：轻量级分布式任务调度框架，支持 CRON 表达式、任务分片、失败重试、执行日志。

**在项目中的应用场景**：

| 任务 | CRON | 并行度 | 说明 |
|------|------|--------|------|
| 每日内容批处理（5 阶段流水线） | `0 30 4 * * ?`（每日 04:30） | 1 | 串行执行 5 Phase，Phase 内多线程 |
| 记忆总结失败重试扫描 | `0 */10 * * * ?`（每 10 分钟） | 8 | 扫描 `FAILED_DEGRADED` 记录重投 |
| 活动节点下架清理 | `0 0 2 * * ?`（每日 02:00） | 4 | 规则引擎节点自动下架 |
| 星梦库存健康检查 | `0 0 5 * * ?`（每日 05:00） | 1 | 检查各时间窗库存量，提前告警 |

**为什么选 XXL-Job（对比 Spring Scheduler / Quartz）**：
- `@Scheduled` 本地调度无分布式支持，多实例部署时任务会重复执行
- Quartz 需要维护数据库锁表，集群模式配置复杂，UI 功能弱
- XXL-Job 提供 Web 控制台（可手动触发、查执行日志、失败告警），运营排查批处理问题直接在控制台查，不需要看日志文件

---

### 7. Hermes（长连接推送）

**是什么**：公司内部的实时消息推送服务，基于 WebSocket/长轮询实现服务端到客户端的实时推送，采用 Topic 发布-订阅模型。

**在项目中的应用场景**：

| 推送类型 | Topic 格式 | 推送时机 |
|---------|-----------|---------|
| AI 回复流式分片（打字机效果） | `chat_session_{sessionId}` | LLM 输出每个 Token 时推送 |
| 星梦邀请卡 | `user_{userId}_invite` | 意图识别触发后立即推送 |
| 开场白内容（星梦进入时） | `xingmeng_{saveId}` | 实时改写 Agent 生成完成 |
| 回忆卡片通知 | `user_{userId}_memory_card` | 星梦结束后异步生成完成 |

**为什么必须用长连接而非轮询**：
AI 回复是流式生成的（模型逐 Token 输出），用户体验要求看到"打字机效果"，如果用轮询（500ms 一次）会有明显卡顿感；星梦邀请卡要求"实时到达"，用户正在聊天时不能等下次轮询才看到邀请。长连接的服务端主动推送是这两个场景的唯一合理方案。

---

## 二、Python AI Agent 层

### 8. LangGraph

**是什么**：基于图（Graph）的 AI 工作流编排框架，将多步 AI 任务定义为有向状态图（StateGraph），节点是处理步骤，边定义执行路径。

**在项目中的应用场景**：
项目 4 个 Agent 全部基于 LangGraph StateGraph 构建：

```
stranger-feed:    validate → generate → validate_output → translate(并行) → persist
acquaintance-feed: validate → generate → assemble_post → return
xingmeng-content: validate → Phase1 → Phase2 → Phase3 → Phase4 → Phase5 → assemble
xingmeng-opening: validate → query_mcp(并行) → assemble_prompt → invoke_llm → cleanup
```

每个节点是一个 async 函数，输入输出是 TypedDict 状态对象；每个节点配条件边，出错时 `state.error` 写入 ErrorCode，条件边路由到 END，主流程不继续执行。

**为什么选 LangGraph（对比 LangChain LCEL / 手写串行逻辑）**：

| 维度 | LangGraph StateGraph | LangChain LCEL Chain | 手写串行 |
|------|---------------------|---------------------|---------|
| 分支逻辑 | 条件边原生支持 | 难以处理复杂分支 | 手写 if/else，易混乱 |
| 错误路由 | 条件边自动路由到 END | 需要 try-catch 层层包裹 | 需要层层传递错误状态 |
| 并行节点 | `asyncio.gather` 并行节点天然支持 | LCEL `.batch()` 有局限 | 手写 asyncio 复杂 |
| 可视化调试 | 图结构可导出为 PNG 查看 | 无 | 无 |
| 步骤可定位 | 节点粒度精确日志 | 链路日志不易拆分 | 手写日志 |

LangGraph 的核心价值是把 AI 工作流"图化"，5 阶段批处理这种复杂的多步流程用 LangGraph 定义后每个阶段独立可测试，出错时日志精确到节点级别。

---

### 9. LangChain + langchain-mcp-adapters

**是什么**：
- LangChain：AI 应用开发框架，封装 LLM 调用、Tool 调用、Prompt 模板等通用组件
- langchain-mcp-adapters：LangChain 官方的 MCP 协议适配层，将 MCP Server 暴露的 Tool 转换为 LangChain Tool 格式

**在项目中的应用场景**：
- `create_react_agent(llm, tools)`：用 ReAct 模式包装 LLM + MCP Tools，Agent 自主决策调用哪些 Tool 获取上下文数据
- `ChatOpenAI`（OpenAI 兼容接口）：连接 AgentRun 模型网关（兼容 OpenAI API 格式），统一调用 qwen3-max / deepseek-v4-flash
- `MultiServerMCPClient`：同时连接多个 MCP Server，`get_tools()` 获取所有可用 Tool 列表

```python
# 标准初始化模式（模块级单例）
_mcp_client = MultiServerMCPClient({
    "memory-service": {
        "transport": "streamable_http",
        "url": "http://mcp.internal/xmc-memory-service-mcp"
    },
    "character-service": {...},
    "dream-service": {...}
})
tools = await _mcp_client.get_tools()
llm = ChatOpenAI(base_url=AGENT_RUN_ENDPOINT, model="qwen3-max")
agent = create_react_agent(llm, tools)
```

**为什么选 LangChain（对比直接用 OpenAI SDK）**：
LangChain 把 Tool 调用、ReAct 循环、流式输出、错误重试都封装好了，直接用 OpenAI SDK 需要自己实现 Tool Calling 的解析和多轮推理循环。在 MCP 场景下，`langchain-mcp-adapters` 一行代码把 MCP Tool 接入 LangChain Agent，否则需要手写 MCP 协议解析和 Tool 格式转换。

---

### 10. 阿里云 AgentRun（Serverless Function Computing）

**是什么**：阿里云的 Serverless AI Agent 运行平台，基于 Function Computing 2.0，提供 OpenAI 兼容的模型网关、自动扩缩容、按调用量计费。

**在项目中的应用场景**：
4 个 Python Agent 服务全部部署在 AgentRun，通过 `s.yaml` / `s-prod.yaml` 定义资源规格：

| Agent 服务 | vCPU | 内存 | 并发 | 预热实例 |
|-----------|------|------|------|---------|
| xingmeng-opening-rewrite | 1 | 2GB | 100 | 2（实时链路） |
| stranger-feed | 1 | 2GB | 100 | 0 |
| acquaintance-feed | 1 | 2GB | 50 | 0 |
| xingmeng-content | 2 | 4GB | 10 | 0（批处理，不敏感） |

**为什么选 Serverless（对比自建容器集群）**：

| 维度 | AgentRun Serverless | 自建 K8s + Docker |
|------|--------------------|--------------------|
| 扩缩容 | 自动，按请求量伸缩 | 需要配置 HPA，有扩容延迟 |
| 成本模型 | 按调用量和执行时长计费（闲时零成本） | 24 小时占用固定资源 |
| 运维 | 无需管理集群、节点、Pod | 需要团队维护集群 |
| 冷启动 | 有（已用预热实例缓解） | 无（Pod 常驻） |
| 适用场景 | 流量不均匀的 AI 推理服务 | 持续高负载的业务服务 |

AI Agent 服务的流量特点是"白天有请求、深夜几乎没有"，自建集群深夜也需要预留资源，Serverless 按量计费在这种场景下成本显著更低；批处理任务（xingmeng-content）每日只运行 2-3 小时，自建集群为此保留 2 vCPU / 4GB 资源全天，浪费严重。

---

## 三、数据存储层

### 11. PolarDB 向量引擎（阿里云 Memory API）

**是什么**：阿里云 PolarDB 的向量存储扩展，并内置了完整的记忆管理 pipeline（事实提取 → 向量化 → 相似度检索 → 去重合并），对外提供 REST API（`/v1/memories` add/search/merge）。

**在项目中的应用场景**：

| API | 调用时机 | 作用 |
|-----|---------|------|
| `POST /v1/memories`（infer=true） | 记忆总结触发后 | LLM 从对话提取事实，决策 ADD/UPDATE/NONE，写入向量库 |
| `POST /v1/memories`（infer=false） | LLM 推断失败降级 | 原始对话文本直接向量化存储，后续补跑推断 |
| `GET /v1/memories/search` | Agent 组装 Prompt 时 | TopK 向量相似度检索，注入长期记忆段落 |
| `POST /v1/memories/merge` | miniMerge 积累到阈值 | 多批记忆语义融合，保留最具体版本，删除冗余 |
| `POST /v1/memories/retry` | XXL-Job 重试扫描 | 对 FAILED_DEGRADED 记录补跑 LLM 推断 |

**为什么选 PolarDB Memory API（对比 Pinecone / Weaviate / 自建 Mem0）**：

| 维度 | PolarDB Memory API | Pinecone / Weaviate | 自建 Mem0 |
|------|-------------------|--------------------|-----------|
| 事实提取 | 内置（FACT_RETRIEVAL_PROMPT） | 需要自己实现 | 需要自己接 LLM |
| 去重决策 | 内置（ADD/UPDATE/NONE） | 需要自己实现 | Mem0 自带 |
| 合并压缩 | 内置 mergeMemories | 需要自己实现 | Mem0 自带 |
| 国内合规 | 阿里云国内节点，数据不出境 | 海外节点 | 取决于自建环境 |
| 运维成本 | 全托管，0 运维 | 需要 K8s 部署 | 需要自己维护 |
| 成本模型 | 按 API 调用量计费 | 按存储量 + 查询量 | 服务器固定成本 |

选 PolarDB 的核心原因：内置的事实提取 + 去重合并 pipeline 相当于"托管版 Mem0"，省去了自建这套逻辑的工程成本；国内合规要求数据不出境；且与阿里云其他服务（Nacos、AgentRun、RocketMQ）在同一体系内，账单统一、监控统一。

---

## 四、AI 模型选型

### 12. 双模型策略：qwen3-max（实时）vs deepseek-v4-flash（批处理）

**选型逻辑**：AI 推理场景分为两类，各自对模型的诉求完全不同。

| 维度 | 实时场景（开场白改写） | 批处理场景（内容生产） |
|------|---------------------|-------------------|
| 对延迟的要求 | P99 < 2 秒，极度敏感 | 允许分钟级，不敏感 |
| 对成本的要求 | 可以贵，因为请求量低 | 必须便宜，因为每日大量调用 |
| 对质量的要求 | 高（用户实时体验） | 中等（预制内容，有验证环节） |
| 模型选择 | qwen3-max（低延迟，高质量） | deepseek-v4-flash（低成本，速度快） |
| Context Window | **262,144 tokens**（≈ 33 万汉字） | **1,000,000 tokens**（≈ 130 万汉字） |
| 最大输出 | 32,768 tokens | 384,000 tokens |

**qwen3-max 用于实时链路**：
- 通义千问系列在中文叙事、情感表达上效果优秀（契合情感陪伴产品调性）
- 响应速度快，首 Token 延迟低
- 国内节点，网络延迟稳定
- Context Window 262K，实际每次调用 Prompt ≈ 1,800–2,500 tokens，占用率 < 1%

**deepseek-v4-flash 用于批处理**：
- 价格约为 qwen3-max 的 1/5~1/8
- 批处理场景对延迟不敏感，可以接受更长生成时间换取成本节省
- Flash 系列在指令遵循（生成结构化 JSON）方面表现稳定
- Context Window 1M，实际每次调用 Prompt ≈ 2,000–4,200 tokens，占用率 < 0.5%

**每日成本对比估算**（假设 1 万活跃用户）：
```
批处理（deepseek-v4-flash）：10000 用户 × 50 次 LLM 调用 × 约 0.002元/次 ≈ 1000元/天
若全用 qwen3-max：10000 × 50 × 约 0.012元/次 ≈ 6000元/天
双模型策略节省约 5000元/天
```

---

## 五、序列化与协议

### 13. Protobuf（Protocol Buffers）

**是什么**：Google 开发的语言中立、平台无关的二进制序列化格式，通过 `.proto` IDL 文件定义数据结构和 RPC 接口，编译生成各语言的代码。

**在项目中的应用场景**：
- 所有 Dubbo 微服务的接口契约全部用 `.proto` 文件定义（放在 `xxx-service-api` 模块）
- BFF → 业务服务的请求/响应对象全部是 Protobuf 生成的 Java 类
- 服务端和客户端共享同一份 IDL，字段类型错误在编译期暴露而非运行时

**为什么用 Protobuf（对比 JSON）**：

| 维度 | Protobuf | JSON |
|------|----------|------|
| 序列化大小 | 小约 3~10 倍 | 字段名以字符串存储，冗余大 |
| 解析速度 | 快约 5~10 倍 | 需要字符串解析 |
| 类型安全 | 编译期强类型，字段类型错误编译失败 | 运行时才发现类型错误 |
| 接口契约 | `.proto` 文件即文档，强制版本化 | 无强制约束，容易漂移 |
| 可读性 | 二进制，需要工具查看 | 人类可直接阅读 |

内部高频调用（BFF 每次请求可能触发 5+ 个 Dubbo 调用）用 Protobuf 可以显著减少序列化 CPU 开销和网络流量；对外 API（App → BFF）仍用 JSON，对可读性要求高。

---

## 六、技术选型总图

> 交互式全景图见 [`diagrams/15-技术栈全景图.html`](diagrams/15-技术栈全景图.html)（浏览器直接打开）  
> PlantUML 源文件见 [`diagrams/15-技术栈全景图.puml`](diagrams/15-技术栈全景图.puml)

全景图覆盖 7 层架构，从上到下依次为：

| 层级 | 内容 | 核心技术 |
|------|------|---------|
| ① 客户端 | App (iOS / Android) | HTTPS · JSON |
| ② BFF 接入层 | soul-haven-web-service | 协议转换 · 鉴权路由 |
| ③ Java 微服务层 | 13 个业务服务 | Dubbo 3 Triple · Protobuf · Nacos |
| ③ 基础设施 | 存储 · 消息 · 调度 · 推送 | PostgreSQL · Redis · RocketMQ · XXL-Job · Hermes |
| ④ MCP Layer | 6 个 MCP Server（AI↔数据解耦边界） | HTTP Streamable · langchain-mcp-adapters |
| ⑤ Python Agent 层 | 4 个 Agent 函数（差异化部署） | LangGraph · LangChain · AgentRun Serverless |
| ⑥ LLM 模型网关 | 双配额组隔离 | qwen3-max（实时）· deepseek-v4-flash（批处理） |
| ⑦ PolarDB 向量引擎 | 托管版 Mem0 | addMemory · searchMemory · mergeMemories |
