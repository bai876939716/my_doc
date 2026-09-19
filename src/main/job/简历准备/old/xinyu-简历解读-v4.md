# 心屿项目简历解读 v4（自用备查）

> 本文档供面试前复习使用。结构：每条简历内容 → 技术原理 → 代码位置 → 设计理由 → 高频追问。

---

## 一、系统全貌（先建立整体认知）

```
┌─────────────────────────────────────────────────────────────┐
│                        App (iOS/Android)                     │
│                  Hermes 长连接（soul_haven_short_chat_topic） │
└─────────────────────┬───────────────────────────────────────┘
                      │ ChatMsgReq（用户消息）
                      ▼
┌─────────────────────────────────────────────────────────────┐
│                    Java 微服务层（13 个服务）                  │
│  soul-haven-short-chat-service  /  soul-haven-feed-service  │
│  soul-haven-star-dream-service  /  soul-haven-memory-service │
│  ...                                                         │
│  通信：Dubbo 3 Triple + Protobuf                              │
│  配置：Nacos  任务：XXL-Job  锁：Redisson  MQ：RocketMQ 5    │
└──────────────────────┬──────────────────────────────────────┘
                       │ OpenAI 兼容 HTTP（POST /v1/chat/completions）
                       ▼
┌─────────────────────────────────────────────────────────────┐
│               Python AI Agent 层（AgentRun Serverless）       │
│  LangGraph StateGraph × 5 个 Agent                           │
│  工具调用 → 6 个 MCP 服务 → 封装 Java Core 接口               │
│  LLM：Qwen3-Max / DeepSeek-V4-Flash / MiniMax-M2.7          │
└─────────────────────────────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────┐
│                        存储层                                 │
│  PostgreSQL（消息/会话/记忆任务）                              │
│  PolarDB 向量引擎（长期记忆 + 用户画像）                       │
│  Redis（短期缓存/Rolling状态/幂等键）                          │
└─────────────────────────────────────────────────────────────┘
```

### 三大功能模块

| 模块 | 触发方式 | 核心 Agent |
|------|----------|-----------|
| 短对话 | 用户主动发消息 | WangSu Rolling LLM Client（非 Agent，直调） |
| Feed 动态 | 角色主动发布（定时/事件驱动） | 陌生角色 Feed Agent / 熟人 Feed Agent |
| 星梦 | 用户进入剧本（贝壳消耗） | 星梦批量生产 Agent / 开场白改写 Agent |

---

## 二、技术栈逐项解读

### LangGraph
- **是什么**：Python 框架，用 `StateGraph` 描述 Agent 工作流。节点（Node）= 一个处理步骤，边（Edge）= 跳转逻辑。
- **在项目里干什么**：5 个 Agent 各自是一个 StateGraph，节点包括"调 MCP 读数据 → 调 LLM 生成 → 解析结果 → 写回"等步骤。`add_conditional_edges` 实现"LLM 返回异常 → 走错误路由短路退出"。

### MCP（Model Context Protocol）
- **是什么**：Anthropic 发布的标准协议，让 LLM 能通过"工具调用"方式访问外部系统，类似 REST API 但专为 Agent 设计。
- **在项目里干什么**：Agent 不直连 DB，所有数据操作通过 6 个 MCP 服务（Java 实现）暴露为工具，Agent 按工具名调用。例如 `get_character_profile`、`save_memory_summary` 等。
- **为什么这样做**：AI 层和业务层解耦，Java 侧可以加业务校验、权限控制；MCP 可独立扩缩容。

### PolarDB 向量引擎（Memory API）
- **是什么**：阿里云 PolarDB 内置的向量存储 + 检索能力，对外暴露类 Mem0 的 HTTP API。
- **在项目里干什么**：存储长期记忆（提取的事实条目），按 `runId` 命名空间隔离不同场景，用 TopK 语义检索召回与当前对话相关的记忆注入 Prompt。

### Hermes 长连接
- **是什么**：喜马拉雅自研长连接 Pub/Sub 框架（类 WebSocket），App 与服务端保持持久连接。
- **Topic**：`soul_haven_short_chat_topic`，Channel：`chat_session_{sessionId}`
- **Sub 端**：`@SubRequestMsgHandler(ChatMsgReq.class)` 注解路由到 `ShortChatHermesSubHandler`
- **Pub 端**：`pubMsgHandler.sendMsgRsp()` 推送给指定 Channel 的所有订阅者（即该用户 App）

### RocketMQ
- **用途一**：记忆总结触发（`MemorySummaryReadyEventPayload`），解耦主链路与记忆写入
- **用途二**：星梦结束后 4 类后处理任务（旁路异步并行）

### Redisson 分布式锁
- **用途**：防止同一用户同时触发多次星梦流程 / 同一 MQ 消息被多实例并发消费

---

## 三、核心工作内容逐条深度解读

---

### 条目一：三层分级记忆架构

**简历原文：** 短期（最近 40 轮原文）+ 长期（PolarDB TopK 语义检索）+ 用户画像三层分级注入上下文；runId 命名空间隔离 7 种场景。

#### 3.1 为什么需要记忆？

LLM 本身无状态，每次调用的 context window 有限（通常 8K~128K token）。用户第 50 轮说"上次提到的那首歌"，LLM 不知道"上次"是哪次。记忆系统就是把"过去发生了什么"结构化存储，在每次 LLM 调用前注入 Prompt。

#### 3.2 三层的具体内容

| 层级 | 数据来源 | 检索方式 | 注入位置 |
|------|----------|----------|----------|
| 短期记忆 | 最近 40 轮对话原文（`tb_soul_haven_short_chat_message`） | 直接 SELECT，按 msgId 排序取最新 40 条 | Prompt 的 `<history>` 节 |
| 长期记忆 | PolarDB 向量存储的事实条目（如"用户喜欢爵士乐"） | TopK 向量语义检索（与当前对话相关度排序） | Prompt 的 `<long_term_memory>` 节 |
| 用户画像 | PolarDB 画像提取结果（性格标签、偏好、情感状态） | 按 runId 全量读取（画像条目少，不需要向量检索） | Prompt 的 `<user_profile>` 节 |

#### 3.3 runId 命名空间隔离

PolarDB Memory API 用 `runId` 区分不同"记忆空间"。如果多个场景共用一个 runId，不同角色的记忆会相互污染。

**命名规则：**
```
短对话：  {userId}__{characterId}          例：10001__char_007
群聊：    group__{groupId}__{userId}
星梦：    stardream__{roleId}__{saveId}
```

7 种场景各有独立格式，物理上隔离在 PolarDB 不同分区，查询时只返回当前场景的记忆。

**代码位置：** `soul-haven-memory-service` → 记忆写入时构造 `runId` 的逻辑（各场景 Processor 分别实现）

#### 3.4 追问准备

**Q：为什么短期记忆不也用向量检索？**
A：最近 40 轮是强相关的，直接全量注入比检索准确。向量检索有召回误差，对近期对话反而可能漏掉重要上下文。

**Q：TopK 的 K 取多少？怎么决定？**
A：根据 Prompt 剩余 token 预算动态调整，一般 K=5~10，按相关度降序，超出预算截断。

**Q：用户画像和长期记忆有什么区别？**
A：长期记忆是"事件/事实"（"用户说他有一只猫叫花花"），用户画像是"特征标签"（"内向型、喜欢音乐、情感需求高"）。画像由 LLM 从大量对话中提取，更稳定；长期记忆更细粒度、实时更新。

**Q：长期记忆在 PolarDB 里是怎么存的？一条记忆是一个向量吗？5 条之后就合并吗？**
A：每条记忆 = 一段文本 + 该文本的 embedding 向量（1536 维浮点数），是 PolarDB 里的一行。5 不是记忆条目的上限，是触发合并的**批次数**。每次调 `/v1/memories` 是一个批次，可能产生 3~8 条新条目；积累 5 个批次（约 200 轮对话）后触发 miniMerge，把 20~30 条碎片语义融合为 3~5 条精华，旧条目删除、新条目重新生成 embedding。TopK 检索时用查询向量和所有条目的向量做余弦相似度计算，返回最相关的 K 条文本注入 Prompt。

---

### 条目二：LLM 驱动的记忆事实提取与两级去重合并

**简历原文：** PolarDB Memory API `infer=true` → ADD/UPDATE/NONE 三路决策；miniMerge 以 PostgreSQL 行数触发；XXL-Job 定时补跑 retry。

#### 4.1 整体流程

```
用户完成一批对话（N 轮，约 40 轮触发一次）
    ↓ RocketMQ 消息：MemorySummaryReadyEventPayload
    ↓
MemorySummaryProcessor.handleSummaryReadyEvent()
    ↓
1. Redis 幂等检查（TTL 7天，防重复消费）
2. 从 PostgreSQL 查 20 轮对话原文
3. POST PolarDB /v1/memories（infer=true）
   ↓ PolarDB 内部：
   ① LLM（FACT_RETRIEVAL_PROMPT）提取事实列表
   ② 向量化 + 检索已有记忆
   ③ LLM（UPDATE_MEMORY_PROMPT）对每条事实决策：ADD / UPDATE / NONE
   ↓
4. INSERT tb_memory_summary_task（summary_type="mini"）
5. 检查 unmergedMiniCount >= 5？
   是 → POST PolarDB /v1/memories/merge（MERGE_MEMORY_PROMPT）
      → 5 条 mini 合并为 1 条 merged，旧条目删除
```

**代码位置：** `soul-haven-memory-service/...service/MemorySummaryProcessor.java`
- L88：`handleSummaryReadyEvent()` MQ 消费入口
- L114：`idempotencyStore.tryAcquire(key)` Redis 幂等
- L163：`POST /v1/memories`

#### 4.2 为什么用 PostgreSQL 行数而不是内存计数器？

**错误做法：** 用内存变量 `int count = 0; count++` 计数，到 5 触发 merge。

**问题：** Serverless 实例随时可能被回收，内存变量丢失。重启后 count 归零，已经写入的 4 条 mini 永远不会触发 merge，记忆碎片堆积。

**正确做法：** 每次触发前 `SELECT COUNT(*) FROM tb_memory_summary_task WHERE summary_type='mini' AND memory_summarized=false AND user_id=? AND character_id=?`，计数持久化在 DB，重启不影响。

#### 4.3 miniMerge 的作用

PolarDB 每次 `infer=true` 调用产生多条细粒度事实（"用户叫小明"、"用户喜欢喝咖啡"）。随着对话增多，同一用户的事实越来越多，向量检索召回时噪声增大。

`mergeMemories` 把 5 条 mini 语义融合为 1 条 merged，去掉重复/矛盾，保留精华。长期来看记忆库保持精简，检索质量不随对话轮数下降。

#### 4.4 降级 + XXL-Job 保障

- LLM 提取失败 → 降级：把对话原文直接存入 PolarDB（raw text），打标 `infer_failed=true`
- XXL-Job 每小时扫描 `infer_failed=true` 的条目，重新调用 PolarDB API 补提取
- 保障记忆写入 SLA：即使 LLM 瞬时抖动，最终一定会提取成功

#### 4.5 追问准备

**Q：ADD/UPDATE/NONE 三路决策的逻辑是什么？**
A：PolarDB 把新事实和已有记忆都喂给 LLM，LLM 判断：完全新事实→ADD，已有记忆需要修正→UPDATE（如用户改了名字），无新信息→NONE 丢弃。

**Q：幂等键格式是什么？**
A：`short_chat_summary_ready:{userId}:{saveId}:{sessionId}:{startCursor}:{endCursor}`，TTL 7 天。同一批次消息无论被消费多少次，第一次成功后后续全部短路跳过。

**Q：miniMerge 阈值为什么是 5？**
A：经验值，平衡了"太频繁 merge 浪费 LLM 调用"和"太少 merge 记忆碎片化"。每 40 轮触发一次 mini，5 次 mini = 200 轮对话触发一次 merge，约 1~2 周的对话量，合理。

**Q：SLA 是什么？简历里"保障记忆写入 SLA"具体是什么意思？**
A：SLA = Service Level Agreement，服务等级协议，本质是一个承诺。"记忆写入 SLA"不是承诺实时成功，而是承诺**最终一定写入**：LLM 当时失败 → 降级存原文打标 → XXL-Job 定时补跑，无论中间出什么问题，记忆不会永久丢失。

**Q：语义融合和删除时，PolarDB 怎么知道要合并和删哪些条目？数据是怎么关联的？**
A：靠 PostgreSQL 做索引桥接。每次调 `/v1/memories` 后，PolarDB 返回 `AddMemoryResponse`，里面有本批次创建/更新的所有 memory ID（如 `["mem_001","mem_002",...]`）。这些 ID 存入 `tb_memory_summary_task` 的 `polar_memory_ids` 字段。触发 miniMerge 时，从 5 行 mini 任务里取出所有 `polar_memory_ids`、去重后传给 `/v1/memories/merge` 的 `messageIds` 参数；PolarDB 按这些 ID 读出文本做语义融合，`deleteOld=true` 则按同样的 ID 列表删除旧条目，写入新的 merged 条目并返回新 ID；最后把新 ID 存回 PostgreSQL 的 merged 任务行。PostgreSQL 是索引，PolarDB 是实体，两边通过 memory ID 关联。

---

### 条目三：LangGraph 多 Agent 编排框架

**简历原文：** 5 个 Agent，StateGraph + add_conditional_edges 节点级错误路由；MultiServerMCPClient 工具名路由表；asyncio.Semaphore 限制并发。

#### 5.1 5 个 Agent 的分工

| Agent | 触发场景 | 核心任务 |
|-------|----------|----------|
| 陌生角色 Feed Agent | 角色与用户陌生（低亲密度）时主动发 Feed | 按角色人设生成符合当前关系阶段的动态文本 |
| 熟人 Feed Agent | 高亲密度角色主动发 Feed | 引用用户历史偏好，生成个性化内容 |
| 星梦批量生产 Agent | 每日 06:00 XXL-Job 触发 | 批量生产 12 个时间窗的剧本内容 |
| 开场白改写 Agent | 用户进入星梦时 | 将通用开场白改写为符合当前用户状态的个性化版本 |
| 意图识别 Agent | 短对话每次用户发消息旁路触发 | 判断是否触发星梦邀请（topicIntent） |

#### 5.2 StateGraph 节点级错误路由

```python
# 简化示意
from langgraph.graph import StateGraph

graph = StateGraph(FeedAgentState)

graph.add_node("load_context", load_context_node)      # 调 MCP 读用户数据
graph.add_node("generate_content", generate_node)       # 调 LLM 生成
graph.add_node("parse_result", parse_node)              # 解析 JSON
graph.add_node("save_result", save_node)                # 调 MCP 写回
graph.add_node("error_handler", error_node)             # 统一错误处理

# 条件边：parse_node 后根据解析结果决定走哪条路
graph.add_conditional_edges(
    "parse_result",
    lambda state: "error_handler" if state.get("parse_failed") else "save_result"
)
```

每个节点发生异常时更新 State 中的错误标记，条件边读取标记决定下一节点，而不是抛异常中断整个图。

#### 5.3 MultiServerMCPClient 工具名路由

```python
# 6 个 MCP 服务，每个服务注册不同工具
mcp_client = MultiServerMCPClient({
    "character-service": {"url": "http://character-mcp:8080/mcp"},
    "memory-service":    {"url": "http://memory-mcp:8080/mcp"},
    "feed-service":      {"url": "http://feed-mcp:8080/mcp"},
    "user-service":      {"url": "http://user-mcp:8080/mcp"},
    "session-service":   {"url": "http://session-mcp:8080/mcp"},
    "stardream-service": {"url": "http://stardream-mcp:8080/mcp"},
})
```

Agent 调用工具时只传工具名（如 `get_user_profile`），MultiServerMCPClient 查路由表找到对应 MCP 服务发请求。Session 失效（网络抖动/服务重启）时自动重连，对 Agent 透明。

#### 5.4 asyncio.Semaphore 并发控制

```python
# Serverless 实例内存有限，防止并发爆炸
llm_semaphore = asyncio.Semaphore(3)    # 最多 3 个并发 LLM 调用
mcp_semaphore = asyncio.Semaphore(10)   # 最多 10 个并发 MCP 调用

async def call_llm_bounded(prompt):
    async with llm_semaphore:
        return await llm_client.generate(prompt)
```

星梦批量生产场景需要同时处理多个用户，不加限制会导致 AgentRun 实例 OOM。Semaphore 是 asyncio 原语，轻量无额外依赖。

#### 5.5 追问准备

**Q：为什么不用一个 Agent 处理所有场景？**
A：不同场景的 Prompt 模板、MCP 工具调用顺序、错误处理策略差异很大。合并成一个会让 StateGraph 节点图变得复杂难维护，条件分支爆炸。独立 Agent 职责单一，可以针对各场景独立调优 Prompt 和错误处理。

**Q：LangGraph 和直接用 LangChain 有什么区别？**
A：LangChain 是工具库（提供 LLM 调用、Prompt 模板、Output Parser），LangGraph 是在此基础上的编排框架，提供有向图（DAG/循环图）的状态管理和节点路由。复杂多步 Agent 用 LangGraph 比用 LangChain LCEL 链更清晰，支持条件跳转和循环。

**Q：MCP 和直接 HTTP 调用 Java 接口有什么区别？**
A：MCP 是标准协议，工具描述（schema）可以直接喂给 LLM，让 LLM 自主决定调哪个工具传什么参数（Tool Use 模式）。直接 HTTP 调用需要 Agent 代码显式写死调用逻辑。MCP 更灵活，未来换 LLM 不需要改 Agent 代码。

**Q：Feed 是什么？为什么分陌生和熟人两个 Agent？**
A：Feed = AI 角色主动发给用户的动态内容，类似朋友圈/微博，区别于用户主动发消息的短对话。分两个 Agent 是因为陌生阶段和熟人阶段的内容策略完全不同：陌生阶段角色不了解用户，发通用的角色人设内容，不引用记忆；熟人阶段引用用户长期记忆（"花花好些了吗？"），强调"被记得"的情感连接。Prompt 模板、MCP 工具调用顺序都不同，合并成一个 Agent 会让条件分支爆炸。

**Q：StateGraph 和 add_conditional_edges 分别是什么？节点级错误路由怎么实现的？**
A：StateGraph 是 LangGraph 的有向图，每个节点是一个函数，接收共享的 State 对象、返回对 State 的部分更新；节点间通过边决定执行顺序。add_conditional_edges 是条件边，节点执行完后调用一个路由函数，根据 State 当前内容动态决定下一个节点是谁。错误路由的实现：每个节点内部 try-catch，异常时不 raise 而是把错误写入 `state["error"]` 字段；条件边的路由函数检查 `state.get("error")`，有错误就返回 `"error_handler"` 节点名，没有就返回下一个正常节点名。这样任何节点失败都能短路跳到统一错误处理，后续节点全部跳过。

**Q：MultiServerMCPClient 是怎么封装的？工具路由怎么实现的？**
A：来自 `langchain-mcp-adapters`，初始化时配置 6 个 MCP 服务的 URL，客户端向每个服务发握手请求拿回各自的工具列表，内部建工具名→服务地址的路由表。Agent 调工具只传工具名，客户端查路由表分发到对应服务。项目在此基础上包了一层 `ResilientMCPClient`，处理 Session 失效时自动重连（MCP 是有状态连接，网络抖动会断），重连对 Agent 透明。外层再用 `asyncio.Semaphore` 限制并发（LLM 3 个、MCP 10 个），防止星梦批量生产时多用户并发把 Serverless 实例打爆 OOM。

---

### 条目四：五阶段星梦日内容批处理流水线

**简历原文：** 5 阶段（扫描 → 改写旧内容 → 缺口分析 → 补漏 → 钩子补充）；库存优先 LLM 兜底；复用率 60%+，Token 成本降低 40%+。

#### 6.1 为什么需要批处理？

星梦是沉浸式剧本体验，用户进入时需要立刻看到当天的"开场内容"。如果用户进入时实时生成，LLM 调用需要 3~10 秒，体验差。

方案：每天凌晨预生产全天 12 个时间窗的内容，用户进入时直接读库存，P99 < 100ms。

#### 6.2 五阶段详解

```
阶段1：扫描升级角色
  → 查询今日有内容需求的角色（亲密度升级、特殊事件触发等）
  → 建立"任务清单"

阶段2：批量改写旧内容
  → 取历史同类场景的旧剧本
  → 调 LLM 按当前用户状态改写（人名替换、时间线更新、个性化调整）
  → 改写成功 → 标记为可用库存
  → 复用率约 60%（省去 60% 的全新生成成本）

阶段3：时间窗缺口分析
  → 统计 12 个时间窗（早 6 点 / 早 8 点 / ... / 晚 10 点）哪些还没有内容
  → 生成"缺口列表"

阶段4：全覆盖补漏
  → 对缺口列表中每个时间窗，调 LLM 全新生成
  → 8 线程并行，XXL-Job 调度

阶段5：星事钩子补充
  → 检查是否有特殊"星事"（节日/角色生日/用户纪念日）
  → 注入钩子内容（在对应时间窗内容里追加特殊段落）
```

#### 6.3 "库存优先、LLM 兜底"策略

```
用户进入星梦时：
  1. 查今日对应时间窗库存（SELECT FROM tb_stardream_content WHERE date=today AND window=current_window）
  2. 有库存 → 直接返回（< 100ms）
  3. 无库存（批处理未完成 / 特殊情况）→ 实时调 LLM 生成（3~10s，作为兜底）
```

#### 6.4 追问准备

**Q：12 个时间窗怎么定义的？**
A：按用户活跃时段设计，大约每 1~2 小时一个窗口：06:00、08:00、10:00、12:00、14:00、16:00、18:00、20:00、21:00、22:00 等，具体时间可以调整。

**Q：旧内容改写和全新生成分别用什么 Prompt？**
A：改写 Prompt 重点是"保留故事框架，替换人物状态/时间/个性化细节"，输入是旧剧本 + 当前用户画像。全新生成 Prompt 包含角色人设、当前亲密度等级、时间窗对应的场景设定。

**Q：8 线程并行怎么控制不超载？**
A：XXL-Job 配置执行器线程池大小，每个线程处理一个用户的完整 5 阶段。线程内部 LLM 调用是串行的（阶段 2 和阶段 4 内部可以并发，但外层线程数控制整体并发）。

---

### 条目五：XML 结构化 Prompt + 六层 JSON 解析容错

**简历原文：** 12 节语义标签 XML 提示词；v4.0-stranger-lv0 版本管理；8% → 0.5% 解析失败率。

#### 7.1 XML Prompt 结构

```xml
<prompt version="v4.0-stranger-lv0">
  <role_identity>你是{character_name}，{character_description}</role_identity>
  <relationship_stage>当前关系阶段：{intimacy_level}级，陌生人</relationship_stage>
  <user_profile>{user_profile_text}</user_profile>
  <long_term_memory>{long_term_memory_text}</long_term_memory>
  <history>{recent_40_rounds}</history>
  <current_context>当前场景：{scene_description}</current_context>
  <constraints>
    <length>回复字数：{min}~{max}字</length>
    <tone>{tone_description}</tone>
    <forbidden>{forbidden_topics}</forbidden>
  </constraints>
  <output_format>
    请严格按以下 JSON 格式输出：
    {"reply": "...", "emotion": "...", "next_action": "..."}
  </output_format>
  <!-- 还有 4 个节，共 12 节 -->
</prompt>
```

**版本字符串含义：** `v4.0-stranger-lv0`
- `v4.0`：第 4 代 Prompt 体系（大版本，结构有变化）
- `stranger`：陌生角色 Agent 专用（vs `familiar`、`stardream` 等）
- `lv0`：亲密度等级 0（不同等级 Prompt 措辞和约束不同）

版本字符串存 Nacos 配置中心，灰度发布时新旧版本并存，按 userId 哈希路由到不同版本。

#### 7.2 为什么是 XML 而不是 Markdown？

- XML 有明确的开闭标签，LLM 不容易把结构混淆为内容
- 12 个节有清晰的语义边界，便于 Prompt 工程师按节调整
- 相比 Markdown 的 `###` 标题，XML 标签对 LLM 的结构理解更稳定

#### 7.3 六层 JSON 解析容错

**背景：** LLM 有时输出格式不完美，直接 `json.loads()` 失败率约 8%。

```python
def parse_llm_output(raw: str) -> dict:
    # 第1层：剥离 DeepSeek <think>...</think> 块
    raw = re.sub(r'<think>.*?</think>', '', raw, flags=re.DOTALL)
    
    # 第2层：提取 Markdown 代码围栏 ```json ... ```
    fence_match = re.search(r'```json\s*(.*?)\s*```', raw, re.DOTALL)
    if fence_match:
        raw = fence_match.group(1)
    
    # 第3层：边界定位，找第一个 { 和最后一个 }
    start = raw.find('{')
    end = raw.rfind('}')
    if start != -1 and end != -1:
        raw = raw[start:end+1]
    
    # 第4层：标准解析
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        pass
    
    # 第5层：尾逗号修复（LLM 偶发在最后一个字段后加逗号）
    raw = re.sub(r',\s*([}\]])', r'\1', raw)
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        pass
    
    # 第6层：json-repair 库（处理更复杂的截断/引号问题）
    from json_repair import repair_json
    return json.loads(repair_json(raw))
```

**效果：** 解析失败率从 8% 降至 < 0.5%。

#### 7.4 追问准备

**Q：DeepSeek 的 think 块是什么？**
A：DeepSeek-R1 系列模型会输出 `<think>...</think>` 包裹的推理过程（Chain of Thought），然后才是实际回答。这段推理不是给用户看的，必须在解析前剥离，否则 JSON 提取会把思维链内容也包进去导致失败。

**Q：为什么不直接让 LLM 严格按 JSON 输出，省掉容错？**
A：即使要求严格输出，LLM 在边界情况下（超长输出被截断、对话上下文复杂时）仍会偶发格式问题。容错层是兜底，不是替代 Prompt 约束。

---

### 条目六：高可靠 Agent 调度体系

**简历原文：** RocketMQ At-Least-Once 三类重投场景 → Redisson 锁 + Redis 幂等键双层兜底；星梦入口三级限流覆盖三类场景；4 类后处理 MQ 异步剥离，30ms 主链路。

#### 8.1 Redis SET NX 保障 MQ 消费幂等

**为什么需要：** RocketMQ At-Least-Once，三类场景必然触发重投：

```
场景1：消费超时重投
  Instance B 处理耗时 4s，超过 Broker consumeTimeout 3s
  → Broker 认为失败，重新投递 → 消息被二次消费

场景2：ACK 丢失重投
  Instance B 处理完毕，ACK 因网络抖动丢失
  → Broker 未收到确认，重新投递 → 消息被二次消费

场景3：Rebalance 窗口期重投
  新实例加入 Consumer Group，触发分区重分配
  → B 未 ACK 的消息转给新实例 D → 同一消息被两个实例处理
```

**具体场景举例（记忆总结）：**

```
用户完成第 40 轮对话，触发记忆总结 MQ 消息

正常消费：
  Instance B：SET idempotency_key NX → 成功
            → 调 PolarDB /v1/memories 提取事实
            → "用户喜欢爵士乐" 写入向量库
            → 处理完毕，但 ACK 丢失

重投到 Instance C：
  Instance C：SET idempotency_key NX → 失败（key 已存在）
            → 直接返回，不执行任何操作

结果："用户喜欢爵士乐" 只写入一次，向量库不出现重复条目
```

SET NX 是 Redis 原子操作，并发到达时也只有一个实例能成功，不需要额外加锁。

```
幂等键格式：
  short_chat_summary_ready:{userId}:{saveId}:{sessionId}:{startCursor}:{endCursor}
TTL 7 天：覆盖所有可能的重试时间窗口
```

#### 8.2 Redisson 分布式锁防止星梦会话并发创建

**为什么不用 SET NX：** 星梦会话创建是一个**需要等待的互斥操作**，不能直接跳过。

**具体场景举例：**

```
用户手滑连续点了两次"进入星梦"，两个请求几乎同时到达

用 SET NX 的问题：
  请求1：SET key NX → 成功 → 创建会话
  请求2：SET key NX → 失败 → 直接报错返回给用户（体验差）

用 Redisson 锁：
  请求1：tryLock() → 成功 → 创建会话 → 释放锁
  请求2：tryLock() → 等待（锁被持有）→ 获取锁
       → 检查会话已存在 → 返回已有会话（用户无感知）
```

**Watch Dog 的作用：**

```
Redisson 默认锁 TTL = 30s
如果会话创建（包含 LLM 生成开场内容）耗时超过 30s：
  没有 Watch Dog → 锁自动过期 → 第二个请求进来 → 重复创建
  有 Watch Dog   → 每 10s 自动续期 → 锁持续有效到业务完成
```

#### 8.4 星梦入口三级限流

三级分别覆盖三类不同场景，不能用一级替代：

```
第一级：10 分钟冷却
  针对：用户误操作连续点击
  数据结构：Redis Key = "stardream:cooldown:{userId}"，TTL 600s
  进入成功时写入，存在则拒绝

第二级：每日 8 次上限
  针对：正常用户日累计高频体验
  数据结构：Redis Key = "stardream:daily:{userId}:{date}"，当日自然过期
  INCR 原子递增，>= 8 拒绝

第三级：拒绝后 5 分钟冻结
  针对：被拒绝后立刻重试的脚本行为
  任意一级拒绝 → 写冻结 Key，TTL 300s
  冻结期内所有请求不做其他检查直接拒绝
```

全程 Redis 内存校验，单次 < 5ms，不打数据库。

#### 8.6 星梦结束后 4 类后处理异步解耦

**问题根因：** 4 类后处理对用户当前操作无即时依赖，但同步执行各自耗时：

```
记忆总结（调 PolarDB）：~3 秒
亲密度计算（复杂查询）：~500ms
成就/徽章检查：~300ms
下一场内容预生产（调 LLM）：~5 秒
同步合计：~9 秒
```

**解法：主链路最小化 + MQ 异步并行**

```
星梦结束
  → 更新会话状态（1 次 DB 写，~10ms）
  → 发 4 条 RocketMQ 消息（生产者异步，~5ms × 4）
  → 立即返回 App（总耗时 < 30ms）

后台 4 个 Consumer 并行消费，互不阻塞
每类 Consumer 有独立幂等键，重试安全
```

#### 8.7 追问准备

**Q：集群模式不是已经保证每条消息只投给一个实例了吗，为什么还需要分布式锁和幂等键？**
A：集群模式只解决正常路由，不能保证 Exactly-Once。Rebalance 窗口期、ACK 丢失、消费超时三类场景下 Broker 仍会重投，这是 At-Least-Once 的设计本质。分布式锁防并发重入，幂等键防顺序重试，两者补集群模式覆盖不到的边界情况。

**Q：为什么主链路能控制在 30ms 内？**
A：主链路只做两件事：1 次 DB 写（更新会话状态）+ 发 4 条 MQ 消息（生产者异步不等 ACK）。所有重操作全部在 MQ Consumer 里异步完成，主链路不等任何后处理结果。

**Q：星梦三级限流为什么不能用一级统一处理？**
A：三类场景的时间粒度和触发条件不同。冷却是连续操作防误触，每日上限是跨时段的总量控制，冻结是针对被拒绝后仍重试的异常行为。一级限流只能处理一种时间维度，三级组合才能覆盖全部场景。

---

## 四、短对话全链路快速回顾

面试常考："用户发一条消息，系统经历了什么？"

```
1. App 通过 Hermes 长连接发 ChatMsgReq
   → ShortChatHermesSubHandler.onChatMsg()
   
2. ShortChatHermesSubServiceImpl.sendMessage()
   → INSERT 用户消息到 DB（ShortChatMessage，senderType=0）
   → SELECT 会话信息（ShortChatSession）
   → INSERT/UPDATE 本批次 Rolling 日志（ShortChatRollingWaitLog）
   → 立即 ACK 给 App（返回 msgId，不等 AI）

3. Rolling Flush（ShortChatRollingWaitEngine）
   → Redis 存 Rolling 状态（msgIds 列表、计时）
   → 800ms 内无新消息 → flush
   → submitAfterFlushCommitted() → 进入编排器

4. 主编排器（ShortChatRollingFlushAiOrchestrator）
   a. 捞本批用户消息
   b. 更新"AI已读"游标，推已读通知
   c. 意图识别（是否触发星梦邀请）
   d. 贝壳预扣费
   e. INSERT 占位消息（空内容，GENERATING 状态）
   f. 推占位消息给 App（显示"●●●正在输入"）
   g. 调 WangSu LLM Client → GenerateTextService.generate()（阻塞等完整回复）
   h. 机审 + Guardrail 过滤
   i. 切分为 1~5 段 + 计算各段延时
   j. INSERT 分片消息，逐段推给 App（模拟打字效果）
   k. 触发记忆总结（发 MQ，异步旁路）

5. 异步记忆总结（RocketMQ → MemorySummaryProcessor）
   → 幂等检查 → 查对话原文 → POST PolarDB /v1/memories
   → miniMerge（5条合并）→ 画像提取更新
```

**关键设计决策对照：**
| 现象 | 设计原因 |
|------|----------|
| 发消息立即返回 ACK，不等 AI | 用户体验：不能让用户等 5 秒发送才完成 |
| Rolling 攒批再调 LLM | 防止快速连发触发多次 LLM，浪费成本 |
| 先插占位消息再调 LLM | 给用户"AI 在思考"的即时反馈 |
| 切分 + 延时队列模拟打字 | LLM 返回完整文本，但体验上要逐渐出现 |
| 记忆总结走 MQ 异步 | 记忆总结不是主链路，不能阻塞 AI 回复推送 |

**Q：为什么要用占位消息？直接等 LLM 返回再推不行吗？**
A：三个原因。① 即时反馈：LLM 阻塞调用需要 2~5 秒，这段时间没有任何响应用户会以为系统挂了，占位消息让 App 显示"●●●正在输入"；② 时序锁位：占位消息插入时就生成了 msgId 和 sendTime，代表"AI 开始回复的时刻"，若等 LLM 回来再 INSERT，时间戳是"完成时刻"，消息列表会时序错乱；③ 错误承载：LLM 失败时把占位消息 UPDATE 为 FAILED 状态推给 App 显示"回复失败，点击重试"，没有占位消息就无处承载失败状态。

**Q：Java 和 Python 之间的 OpenAI 兼容 HTTP 是什么意思？**
A：OpenAI API（`POST /v1/chat/completions`，请求体为 messages 数组，响应体含 choices）已成为行业事实标准。项目里 Python AgentRun 把整个 LangGraph 工作流包装成一个 HTTP 服务，对外暴露和 OpenAI 完全相同格式的端点。Java 的 `ShortChatWangSuRollingLlmClient` 发送 OpenAI 格式请求、解析 OpenAI 格式响应，完全不感知背后是 LangGraph 还是直接调 Qwen。好处：Java 可以用任何 OpenAI SDK；Python 内部重构（换 LLM、改 Agent 逻辑）不影响 Java；两层可独立部署扩缩容。

---

## 五、面试高频场景问题

### "说说你们的记忆系统设计"
> 分三层：短期直接取最近 40 轮 SQL 查询，长期用 PolarDB 向量检索 TopK，用户画像全量注入。写入靠 RocketMQ 触发异步总结，PolarDB infer=true 让 LLM 自动做 ADD/UPDATE/NONE 决策。miniMerge 机制保证记忆不碎片化，XXL-Job 兜底 LLM 失败场景。

### "你们怎么处理 LLM 输出不稳定的问题？"
> 两个层面：Prompt 层用版本化 XML 结构化模板约束输出格式；解析层有六层递进容错，从剥离 think 块到 json-repair 兜底，失败率从 8% 降到 0.5% 以下。

### "介绍一下你们的 Agent 架构"
> Java + Python 双层：Java 处理业务状态和数据，Python 用 LangGraph 编排 5 个独立 Agent，按场景分工。两层通过 OpenAI 兼容协议解耦，Agent 访问数据只能通过 6 个 MCP 服务（封装 Java Core 接口），不直连 DB，保证 AI 层和业务层可独立迭代。

### "你们的系统怎么保障可靠性？"
> 四个维度：① 幂等：MQ 消费 Redis key 防重 + Redisson 分布式锁防并发；② 限流：星梦多级冷却/次数/冻结；③ 资产安全：贝壳预扣费 + 成功结算 + 失败退款；④ 降级：LLM 失败存原文打标，XXL-Job 定时补跑。

### "LangGraph 和直接写顺序代码有什么区别，用它的理由是什么？"
> 顺序代码处理不了条件路由和局部重试。LangGraph 的图结构可以在任意节点失败后走错误路由，而不是整个流程崩溃。State 对象贯穿所有节点，不需要函数间大量传参。多 Agent 并行执行时图结构更清晰，便于调试和可视化。
