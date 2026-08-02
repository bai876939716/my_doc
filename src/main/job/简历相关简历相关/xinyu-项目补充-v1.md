# 心屿 AI 项目 — 面试补充材料

---

## 一、项目介绍

### Q: 介绍一下这个项目

> 这个项目来自喜马被腾讯收购后，直播业务剥离，我们团队转型做的一款 AI 虚拟角色情感陪伴 App。
>
> 产品有三层递进体验，我们有将近 300 多个角色分布在地图上。最基础的是日常短对话，随时可以跟角色聊天，角色会记得你之前说过的事；第二层是新鲜事，就像朋友圈，角色会主动发和用户相关的动态，说"想到你之前聊的那件事，笑了很久"；第三层叫星梦，是带背景故事的沉浸式互动，模拟和角色线下见面的场景，有开场白、有情绪感知、有故事推进。
>
> 架构上，因为 AI 层和业务层技术栈不一样，语言也不一样，加上数据安全的考虑，模型相关操作一定要做好数据操作隔离，所以借鉴微服务的思想分成了两层：Java 微服务处理业务逻辑和数据存储，Python AI Agent 层处理模型推理和内容生成。两层通过 OpenAI 兼容的 HTTP 协议通信，Agent 不直接碰数据库，所有数据操作通过 MCP 工具调 Java 接口，两层完全解耦。
>
> 项目里主要做了三个方向的系统性工作：角色怎么跨会话记住用户、同时不同场景的记忆不能互相污染；几百个角色每天怎么给每个用户生产个性化内容同时把成本控住；LLM 输出在生产环境很不稳定但要求系统高可用。
>
> 项目持续大概 3 个月，上线后 2 个月有 40 万用户，对转型产品来说还挺超预期的。

---

## 【简历第1、2条】三层记忆架构 & 记忆事实提取

### Q: 记忆系统是怎么设计的？

> 先说记忆设计的核心约束，这是我们做设计的出发点。第一，别输入太多，输多了有噪声，影响模型判断；第二，放的越多越费钱；第三，上下文溢出，内容不断累积肯定会溢出。核心目标就是把重要的、精简的、最需要的传给大模型，这也是记忆管理的一个约束。
>
> 情感陪伴对记忆的要求比普通对话高得多，它不仅要知道现在在聊什么，还要知道之前聊过什么，以及在和什么样的人聊。围绕这三个问题，我们定义了三层记忆：短期记忆是最近 20 轮原文，存在 PostgreSQL 里；长期记忆是所有对话阶段性压缩的事实，存在 PolarDB 向量库里，底层是 mem0；用户画像是阶段性提取的用户特征，PolarDB KV 存 JSON。
>
> 写的时候，每轮对话实时写入 PostgreSQL，累积到 20 轮触发记忆总结，LLM 对每条新事实做 ADD、UPDATE、NONE、DELETE 四路决策，这是第一层去重。总结达到 5 次再触发 miniMerge，把多条事实语义合并成更精简的一份，防止向量库无限膨胀，这是第二层去重。
>
> 用户发消息时，Agent 并行拉三层数据，PostgreSQL 原文加向量 Top5 加用户画像，拼成 Prompt 注入给 LLM。

**三层记忆存储：**

| 层级 | 内容 | 存储 |
|------|------|------|
| 短期记忆 | 最近 40 轮原文 | PostgreSQL |
| 长期记忆 | 阶段性压缩事实 | PolarDB 向量库（mem0） |
| 用户画像 | 阶段性用户特征 | PolarDB KV（JSON） |

**额外解决的问题：**

1. 记忆污染：用 `userId + characterId + roleId` 作为 PolarDB `runId` 命名空间，多种场景彻底隔离
2. 写入可靠性：LLM 失败降级写原文并打标，XXL-Job 定时扫描重试
3. 阈值选取：20 轮触发总结、5 次触发合并，是在实际使用中平衡上下文成本和信息衰减后的结果

---

### Q: 记忆设计有什么不足？

> 做完回头看有三个地方想改。第一，用户画像是全局共享的，但一个人面对不同角色是多面的，对治愈系角色倾诉脆弱，对活泼角色表现开朗，混在一个画像里会互相干扰，理想做法是按 userId + characterId 维度单独维护。第二，没有 TTL 机制，"最近在准备面试"这类阶段性信息几个月后还在影响对话，应该有时间衰减。第三，合并频率是固定阈值，高频用户合并太频繁，低频用户向量库冗余长期积累，一刀切对不同类用户不是最优解。

---

### Q: mem0 是什么，怎么工作的？

> mem0 是我们用的长期记忆库，底层是 PolarDB 向量引擎。核心是一个调度器，LLM 引擎负责事实提取和去重决策，Embedding 层把事实向量化写进存储，召回时有 Reranker 对 TopK 结果二次排序。我们用 runId 命名空间做场景隔离，每个角色每个场景的记忆互不干扰。

**合并流程：** 写入时，新事实经 Embedding 向量化，相似度检索找到已有事实，LLM 决策新增、更新还是忽略。miniMerge 触发时，多条事实打包给 LLM 语义融合成更精简的表述，更新向量库对应条目。

**mem0 核心组件：**

- LLM 引擎：事实提取，ADD / UPDATE / NONE / DELETE 四路决策
- Embedding 嵌入层：事实向量化写入存储
- Reranker：召回时对 TopK 结果按相关性二次排序
- 向量存储：持久化事实向量，支持语义检索
- 元数据：通过 `runId` 命名空间隔离，支持按场景过滤

---

## 【简历第3条】LangGraph 构建 Agent 工作流 & MCP 工具集成

### Q: 为什么用 StateGraph + add_conditional_edges 实现节点级错误路由与短路退出？

> 每个节点出错时把 error 写进共享 State 数据包中，add_conditional_edges（条件边）在每个节点后检查 State 有没有 error，有就跳到 END，没有就继续下一个节点。这样有三个好处：
> 
> 1、每个节点只管自己，出错只写自己的 error，不影响其他节点的代码；
> 
> 2、路由逻辑集中在一个 _route_on_error 函数里，所有节点共用；
> 
> 3、哪个节点出错、出什么错，State 里都有记录，可观测。

**三个组成部分：**

**1. 每个节点出错写 error，不抛异常**

```python
async def node_generate(state, *, agent):
    try:
        raw = await invoke_feed_llm_raw(...)
        return {"llm_raw": raw}                      # 正常：只写业务字段
    except Exception as e:
        return {"error": f"模型调用失败: {e}",       # 出错：写 error 到 State
                "error_code": "LLM_FAILED"}
```

**2. 路由函数检查 State**

```python
def _route_on_error(state) -> Literal["end", "continue"]:
    return "end" if state.get("error") else "continue"
```

**3. 条件边挂在每个节点后面**

```python
g.add_conditional_edges(
    "generate",
    _route_on_error,
    {"end": END, "continue": "translate"},
)
```

**执行路径（陌生角色 Feed）：**

```
正常：validate_params → generate → translate → persist → END

LLM 超时（generate 写 error）：
validate_params → generate ─(error)→ END
translate 和 persist 都不会跑，不会把脏数据写进库

入参错误（validate_params 写 error）：
validate_params ─(error)→ END
LLM 根本没被调用
```

**注：星梦批处理的差异**

星梦出错跳到 `assemble_result` 而不是 END。批处理是离线任务，即使中途某阶段失败，也要汇总前几个阶段的执行结果返回给调用方，不能什么都不返回。

---

### Q: 工具是怎么实现的，怎么调用的？

> AI 层不能直接碰数据库，所有数据操作都通过 MCP 工具调 Java 接口完成。具体来说，Java 侧把各个微服务的核心接口包装成 MCP Server，比如角色信息、用户画像、短期对话、星梦库存这几类，Python 侧封装了一个统一的 MCPClient，内部有一张工具名路由表，工具名到服务名的映射，调用时按工具名自动找到对应的 MCP Server 发请求。Agent 层只需要知道工具名和参数，完全不感知背后是哪个 Java 服务、走的哪个接口。

**整体架构：多服务分散注册**

不是一个 MCP Server 包所有接口，而是 6 个独立服务，每个服务维护自己的 URL 和连接：

| 服务 | 覆盖能力 |
|------|---------|
| user-service | 角色信息、羁绊等级、角色列表 |
| soul-user-service | 用户基础信息（昵称、性别） |
| dream-service | 星梦库存、日计划、星事 |
| profile-service | 用户画像 |
| short-service | 短期对话记录 |
| core-service | 星球世界观、地点信息 |

**工具名路由表（`_TOOL_ROUTING`）**

一张静态字典，工具名映射到服务名，25+ 个工具全部在这里注册：

```python
_TOOL_ROUTING = {
    "characterDetail":     "user-service",
    "queryDailyPlan":      "dream-service",
    "getUserProfile":      "profile-service",
    "getAppShortChatMessage": "short-service",
    # ...共 25+ 个工具
}
```

**初始化过程：启动时连接全部服务**

`McpClient` 是全局单例，第一次调用 `get_mcp_client()` 触发 `load_tools()`，对每个已配置 URL 的服务创建 `MultiServerMCPClient`，拉取该服务暴露的工具列表，构建 `_tool_map`（工具名 → 工具对象）和 `_tool_registry`（工具名 → 服务名）：

```python
client = MultiServerMCPClient(
    {server_key: {"transport": "streamable_http", "url": url}},
    tool_name_prefix=False,
)
tools = await client.get_tools()   # 拉取该服务的工具列表
```

**调用过程：`_invoke(tool_name, args)`**

Agent 调用任何一个工具都走这一个方法：

```
_find_tool(tool_name)          # 从 _tool_map 按名查找工具对象
↓
_get_mcp_invoke_semaphore()    # 拿 Semaphore，控制并发上限（默认 8）
↓
tool.ainvoke(args)             # 异步发 HTTP 请求到 Java MCP Server
↓
_coerce_mcp_result(raw)        # 解包响应（LangChain 把响应包成 text 格式）
```

**响应解包：`_coerce_mcp_result`**

LangChain MCP 适配层把 Java 返回的 JSON 包成了这样的格式：

```
[{"type": "text", "text": "## Original Response\n{\"code\":0,\"data\":{...}}"}]
```

所以需要先从 `"## Original Response"` 后面切出来，再做 JSON 解析。

**可靠性：重试 + Session 自动重连**

MCP 是长连接，Serverless 实例在空闲后 Session 可能过期。遇到连接类瞬时错误（`connection reset`、`session terminated`、`timed out` 等），会指数退避重试（0.5s → 1s）；如果是 Session 失效，先触发 `reload_tools()` 重新建连，再重试：

```python
if _needs_mcp_reconnect(e):
    await self.reload_tools()   # 丢弃旧连接，重新连接全部服务
tool = self._find_tool(tool_name)
# 再重试
```

**并发控制**

`asyncio.Semaphore(8)` 懒初始化，全局共用，限制同时在飞的 MCP HTTP 连接数，防止 Serverless 实例打出去的并发请求把 Java 服务打满，也防止本实例内存撑爆。

**流程图：** [MCP 初始化 & 调用序列图](./xinyu-mcp-sequence.md)

---

## 【简历第4条】五阶段星梦批处理流水线 & 成本控制

### Q: Token 成本怎么控制的？

> 主要是三个思路。第一是库存优先，先看当前时间窗有没有现成内容，有就复用，没有才调 LLM 生成，存量复用率 60% 以上。第二是改写而不是全量生成，旧内容拿来改写比从头生成 Token 消耗小得多，单用户每日成本降了 40% 左右。第三是模型分级，批处理用 DeepSeek-V4-Flash，成本低速度快；实时对话才用高质量模型。

**其他手段：**

- Prompt 缓存：系统 Prompt 内容固定，利用 prefix cache，批处理场景下重复前缀不重复计费
- 精简上下文：三层记忆只注入 Top5 向量召回和最近 40 轮原文，不是全量历史

---

## 【简历第6条】高可靠 Agent 调度体系

### Q: 大模型超时怎么处理？

> 按场景分层处理的。实时对话是关键链路，把所有能异步的都剥离出去——记忆写入、用户画像更新都走 RocketMQ 异步消费，主链路只留 LLM 推理这一个阻塞点。同时用流式输出加"你先说，我在听"的过渡态，用户感知延迟降很多。超时就返回兜底话术，不让用户看到报错页。
>
> 批处理容忍度高，超时直接重试，XXL-Job 支持自动重跑，单用户失败也不影响其他人。
>
> 记忆写入是异步链路，LLM 提取事实失败就降级写原文打标，XXL-Job 定时扫描补跑，保障最终一致性。

---

### Q: 并发场景举个例子？

> 拿星梦批处理来说。每天凌晨要给所有活跃用户生产全天 12 个时间窗的星梦内容。XXL-Job 触发后，Java 层把用户按批次发 RocketMQ 消息，多个 Consumer 实例并行消费，每个实例处理不同用户，天然并行。单用户内部用 asyncio.gather 并行检查 12 个时间窗的库存，缺口分析完再并发发起 LLM 生成任务，Semaphore 限流防 OOM。写入用 Redis SET NX 保幂等，防止 Rebalance 重投导致重复生产。

**流程：**

```
XXL-Job 触发
    └─ Java 层按批次发 RocketMQ 消息
         └─ 多个 Consumer 实例并行消费（每个实例处理不同用户）
              └─ 单用户内部 asyncio 并发：
                   ├─ asyncio.gather 并行检查 12 个时间窗库存
                   ├─ 缺口分析后并发发起 LLM 生成（Semaphore 限流）
                   └─ MCP 工具调 Java 接口持久化
```

**关键控制点：**

1. RocketMQ 多 Consumer 实例：多用户天然并行
2. asyncio.gather：单用户 12 个时间窗并行检查，不串行等待
3. asyncio.Semaphore(10)：同一实例内 LLM 并发不超过 10，防 OOM
4. Redis SET NX 幂等：防 Consumer Rebalance 重投导致重复生产

---

## 综合问题

### Q: 模型是怎么选的？

> 主要考虑四个维度：上下文大小、质量、速度、成本，再加上中文情感表达的稳定性。实时对话用 Qwen3-Max，原生支持阿里云 AgentRun，中文情感输出稳定；星梦用 MiniMax-M2.7，角色扮演和长文叙事能力强；批处理用 DeepSeek-V4-Flash，成本和速度平衡最合适。评测的话整理了一批测试用例，覆盖用户倾诉情绪、日常闲聊、话题转折几种场景，让各模型跑一遍，人工打分语气自然度、情感契合度、回复长度，同时对比延迟和 Token 消耗。

| 场景 | 模型 | 原因 |
|------|------|------|
| 实时对话 | Qwen3-Max | 原生支持 AgentRun，中文情感稳定 |
| 星梦互动 | MiniMax-M2.7 | 角色扮演和长文叙事能力强 |
| 批处理生产 | DeepSeek-V4-Flash | 成本和速度平衡最合适 |

---

### Q: 产品实现有哪些难点？

> 主要三个。第一是记忆准确性和跨场景污染，用三层分级记忆加命名空间隔离解决的。第二是几百角色每日千级个性化内容生产和成本控制，批处理流水线加库存复用，单用户日均 Token 成本降 40%。第三是 LLM 输出不稳定和高可用的矛盾，六层 JSON 容错机制，解析失败率从 8% 压到 0.5% 以下。

---

### Q: Python 怎么处理并发的？

> 全部基于 asyncio 异步模型。召回记忆时三层数据用 asyncio.gather 并行拉取，不串行等待。并发上限用 Semaphore 控制，LLM 调用和 MCP 工具调用共用一个，防 OOM。没用多进程，因为 LLM 和 MCP 调用都是 IO 密集型，asyncio 已经够用，Serverless 实例按请求隔离，多进程收益有限。

```python
# 三层并行召回
short_term, long_term, profile = await asyncio.gather(
    fetch_short_term(user_id, character_id),
    fetch_long_term(user_id, character_id),
    fetch_profile(user_id)
)

# 并发限流
sem = asyncio.Semaphore(10)
async def bounded_call(task):
    async with sem:
        return await task()
```

---

### Q: 向量检索效果怎么保证？

> 主要三个手段。命名空间隔离，用 userId + characterId + roleId 作为 runId，检索时只在当前场景的向量空间内搜，不会混入其他角色的记忆。写入质量，mem0 用 LLM 做事实提取，写进去的是结构化事实句，不是原始对话噪声，质量高了召回相关性自然好。miniMerge 减冗余，相似事实定期合并，避免向量库里大量同质化条目影响召回多样性。
>
> 不足是没有 Reranker 对 Top5 做二次排序，表达方式差异大时可能漏掉相关记忆。

---

### Q: 如果重新设计，会改什么？

> 三个地方。用户画像按角色隔离，现在全局共享会互相干扰。合并频率动态化，固定阈值对高频用户太频繁，对低频用户又太慢，应该按活跃度调整。加记忆 TTL，阶段性的信息不该永久有效，应该随时间衰减或定期清理。

---

## 在聚水潭做什么

聚水潭是商家一站式服务平台，对接小红书、京东、淘宝、拼多多等平台的铺货和订单处理全流程。我在分销部门，负责分销订单和售后开发。

分销链路是：供应商 → 中间商（1-2 层）→ 分销商 → 消费者。

一个典型难点是售后退货地址穿透。退货应该退给供应商，但链路中间有中间商，退货地址不能直接暴露给下游。处理方式是下单时把售后地址绑定在商品和订单上，退货发起时系统沿链路向上穿透查询，拿到供应商实际地址返回给用户，各层地址对下游透明，权限边界清晰。
