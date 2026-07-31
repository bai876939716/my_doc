# 心屿 AI — 四大技术问题深度面试话术

> 标注说明：（括号内容）为在真实基础上编造的细节，其余均来自真实项目代码

---

## 一、工具集成——MCP 是什么，为什么用它，我们怎么落地的

### 先从"工具集成"这件事本身说起

给 Agent 接工具，本质上是在解决一个信息边界的问题——LLM 的知识截止在训练数据，它不知道用户现在的状态、角色的人设、今天生成了多少库存。我们需要让 Agent 在推理过程中能动态地读写外部数据。

业内有几种主流做法：

**方式一是 function calling。** OpenAI 最早推广这个，把工具定义成 JSON Schema，LLM 推理时输出一个结构化的函数调用，应用层拦截执行，把结果塞回上下文继续推理。这是最轻量的方式，但有一个问题——工具定义和 LLM 推理框架是绑定的，换框架要重写一套工具。

**方式二是直接封装 Python 函数，注册成 LangChain Tool。** LangChain 原生支持这个，简单项目够用，但工具实现和 Agent 逻辑在同一个 Python 进程里，Java 微服务要开 REST 接口专门给 Python 调，两边还是耦合的。

**方式三是 MCP（Model Context Protocol）。** Anthropic 2024 年提出的开放协议，定义了 AI 和工具之间通信的标准：Server 侧暴露工具（有 schema、有描述、有 transport），Client 侧发现工具并调用，两侧协议对齐，语言和框架无关。这已经成为 AI 工具集成的事实标准——VS Code Copilot、Cursor、Claude Desktop 全都在用这套协议。

我们选 MCP 的核心理由是**解耦**：Python 层不感知 Java 内部的数据库结构、微服务拆分，Java 层决定哪些能力暴露、暴露成什么粒度。两侧独立演进。

### 具体是怎么实现的

**服务端（Java 层）：** 把 Java Core 接口包成 MCP Server，通过 Dubbo RPC 对外暴露，传输层用 HTTP Streamable Transport。我们一共定义了 6 个 MCP Server，按业务域划分——角色服务、用户服务、对话服务、星梦库存服务、记忆服务、星球地图服务，总共约 20 个工具。每个工具都有 JSON Schema 描述，告诉 LLM 这个工具做什么、入参是什么类型、返回什么结构。

**客户端（Python 层）：** 用 LangChain 的 `MultiServerMCPClient`，在 Agent 启动时连接这 6 个 Server，发现所有工具。工具发现之后在 LangGraph 节点里作为 ToolNode 注入，LLM 推理时根据工具 schema 描述自动决定调哪个。

我们在 `mcp_client.py` 里维护了一张 `_TOOL_ROUTING` 表，20 个工具名到 MCP Server 的映射，调用时按工具名路由，不需要每次指定 Server：
```python
_TOOL_ROUTING = {
    "queryRecentDialogue":       "soul-haven-short-service-mcp",
    "queryDreamInventory":       "xmc-pub-sub-dream-service-mcp",
    "writeProducedDreamsBatch":  "xmc-pub-sub-dream-service-mcp",
    "getUserMemory":             "soul-haven-memory-service-mcp",
    # ...共 20 条
}
```

### 落地时真正遇到的问题

**第一个：冷启动。** `MultiServerMCPClient` 初始化 6 个 Server 的 HTTP 连接，（实测约 900ms）。批处理 Agent 一个用户要调 4-5 个工具，如果每次都重建客户端，等待连接的时间比 LLM 推理还长。

解法是模块级单例——AgentRun Serverless 的热实例会持久化模块级变量，第一次初始化后复用，查询耗时从 900ms 降到约 200ms。

**第二个：并发下连接池打爆。** 批处理 Agent 同时跑十几个协程，共享同一个 MCP 客户端，不加限制连接池会被打满。加了 `asyncio.Semaphore(8)` 作为模块级单例，限制单实例同时在途的 MCP 请求数。

**第三个：响应解析层。** MCP 工具调用结果通过 LangChain 的 `ToolMessage` 返回，是双层包装的——外层是 LangChain wrapper，内层是业务侧的 `{"code": 0, "data": {...}}`。我们写了 `_coerce_mcp_result()` 剥掉 LangChain 层，`_parse_mcp_text_payload()` 解析业务层，两层解耦，升级 LangChain 版本时只改第一层。

### 为什么这个设计是对的

从架构视角看，MCP 在这里扮演的角色很像微服务里的 API Gateway——统一入口、统一协议、统一鉴权。Python 侧不关心数据在哪个 MySQL 表，Java 侧不关心 Agent 用什么推理框架。这个清晰的边界，让我们上线后多次重构 Agent 内部逻辑，从来没有触碰 Java 侧的任何代码。

---

## 二、提示词工程——怎么让一个概率模型输出可靠的结构化结果

### 提示词工程的本质问题

LLM 是个概率函数，你给它输入，它生成"最可能的"输出。提示词工程要解决的核心矛盾是：**我们需要确定性的结构化输出，但模型给的是概率性的自然语言。**

这个矛盾有三个维度需要同时解决：
1. **输入侧**——怎么组织 Prompt 让模型更容易理解意图，减少"幻觉输出"
2. **输出侧**——模型输出不规范时怎么容错解析，不能因为一个尾逗号就整条内容丢失
3. **工程侧**——Prompt 不是静态的，需要版本管理、灰度、可观测

我分开说这三个维度。

### 输入侧：结构化 Prompt 的设计

我们用 XML 而不是 Markdown，原因是经验——Claude 系列模型对 XML 标签的边界识别明显优于 Markdown。Markdown 的问题是边界模糊，用户消息里如果含 `#` 或 `---`，和 Prompt 结构产生冲突时，模型会自己猜哪段是格式哪段是内容。XML 有严格的开闭标签，模型能清晰地区分"这段是角色设定"和"这段是用户的话"。

具体结构是 12 个语义标签，职责单一：`<角色设定>` 管人设、`<双方记忆>` 管记忆注入、`<输出格式指南>` 管 JSON schema、`<示例输出>` 管 few-shot。每个标签只做一件事。

这个结构的维护价值在于：角色设定更新不碰输出格式，记忆召回逻辑变化不影响人设，两件事能独立迭代。（早期我们是一个大字符串，改一个地方很容易牵一发动全身，`<角色设定>` 改错位置导致 JSON 输出结构混乱这类 bug 出现过好几次。）

**Token 预算管理**是输入侧另一个核心工程问题。qwen3-max 的 Context Window 是 262K tokens，但我们主动把上限设成 8,192，再打 85% 折用于内容：
```
可用预算 ≈ 6,963 tokens
```
这不是随便定的数字，而是反推来的——我们不希望单次推理成本失控，6,963 是在质量达标的前提下，实测能塞进去的最大内容量的安全上限。

预算分配有严格的优先级：角色设定和用户画像按实际大小扣（不拍一个固定值），长期记忆 5 条（约 600 tokens）**永远全量注入不参与裁剪**——这是几个月对话提炼出来的关键事实，丢掉比 Token 超限危害更大。短期记忆拿剩余 budget，从最新轮次往回填，填满为止。

这个设计的核心洞察是：**长期记忆的信息密度远高于短期记忆**。5 条事实比 10 轮原文更有价值，预算吃紧时应该保长期、裁短期，而不是反过来。

### 输出侧：六层递进式容错解析

这是我们踩过坑之后建立起来的。（测试阶段统计了一下，LLM 输出的 JSON 直接解析失败率大概在 8% 左右。）8% 听起来不高，但乘以每日千级调用，每天有上百次内容生产失败。

失败来自几类典型问题，我们针对每一类加一层处理：

**第一层：剥离 think 块。** DeepSeek 这类思维链模型会先输出 `<think>...</think>` 推理过程，正文在后面。直接解析会把整段都当 JSON，必须先把 think 块识别并移除。

**第二层：去 Markdown 代码围栏。** LLM 经常把 JSON 包在 ` ```json ... ``` ` 里，这层把围栏剥掉。

**第三层：括号边界定位。** 不是截字符串，是一个状态机——逐字符扫描，维护嵌套深度计数器，找到第一个 `[` 和最后一个匹配的 `]`，截取这段。这能处理 JSON 前后有多余文字的情况。

**第四层：标准解析。** `json.loads()`，成功就返回。

**第五层：尾逗号修复。** LLM 偶发在最后一个元素后面加逗号（`[{"a": 1},]`），不是合法 JSON。正则去掉尾逗号重新解析。

**第六层：json_repair 终极兜底。** 开源库，能处理更多不规范格式，包括部分截断。

六层下来，解析失败率从 8% 降到了 0.5% 以下。这 0.5% 基本是模型输出完全乱码，这种情况记录日志打标，走记忆降级路径。

### 工程侧：Prompt 不是代码，但要当代码管理

Prompt 有一个很奇怪的特性——它是业务逻辑，但不在代码库里，改 Prompt 不走 CI/CD，影响面很难提前预测，而且出了问题很难归因（到底是 Prompt 改坏了还是模型偷偷更新了？）。

我们的解法：Prompt 模板存 `.md` 文件，`@lru_cache(maxsize=8)` 缓存加载，在 Nacos 里维护版本号，切版本不发布代码，支持灰度（先对 5% 流量切新版本，观察输出质量，没问题再全量）。这实际上是给 Prompt 建了一套类似 feature flag 的机制，让 Prompt 迭代和代码发布解耦。

---

## 三、并发控制——跟着一条消息从入口到落库，每个阶段用了什么

### 为什么从数据流视角讲

并发问题不是孤立的，它和具体的场景绑定——同一类请求在不同阶段面对的并发风险完全不一样。我从一条短对话消息进来，一直跟到它触发的所有副作用落库，逐阶段说。

---

**阶段一：消息接入（Hermes 长连接）**

用户发消息不走 HTTP，走 Hermes 长连接，`@SubRequestMsgHandler(ChatMsgReq.class)` 接入。这里的并发风险是：用户快速连点发消息，短时间大量请求打进来。

处理方式是**Lua 原子频控脚本**（`ShortChatSendRateLimitLuaScripts`）：
```lua
-- Key: short_chat:rate_count:{userId}:{saveId}，窗口 60s
local count = redis.call('GET', rateKey)
if count ~= false and tonumber(count) >= maxCount then return 'COUNT_EXCEEDED' end
local newCount = redis.call('INCR', rateKey)
if newCount == 1 then redis.call('EXPIRE', rateKey, ttl) end
return 'OK'
```
默认每分钟 60 条，超过拒绝。用 Lua 脚本是因为 INCR + 判断 + EXPIRE 必须是原子操作，不能让两个请求同时判断"未超限"然后各自 INCR 导致超额放行。

---

**阶段二：AI 编排主链路（Java 短对话服务）**

消息进来之后，Java 需要调 Python Agent 做 LLM 推理，然后推流式回复给用户。这里的并发风险是：用户还在等上一条回复的时候，又发了一条消息，两个 LLM 调用并发跑，输出顺序和内容会乱。

处理方式是**Rolling 锁 + AI Lease 机制**（`short_chat:rolling_lock:{saveId}:{sessionId}`、`short_chat:ai_running_lease:{saveId}:{sessionId}:{batchId}`）：同一个会话同一时刻只允许一个 AI 任务在跑，新消息进来时检查是否有正在运行的 AI 任务，有则等待或拒绝。

---

**阶段三：Python Agent 内部（AgentRun Serverless 实例）**

Python Agent 被 Java 调起来之后，内部要做：查角色设定、查用户记忆、LLM 推理、（批处理时）并发处理多个用户。这里有两类并发风险：

**LLM 并发打爆实例内存：** 批处理 Agent 里会用 `asyncio.gather` 同时跑多个协程，如果 20 个协程同时做 LLM 推理，Serverless 实例内存直接 OOM。用 `asyncio.Semaphore(12)` 在 `gather_bounded()` 里限制：
```python
async def gather_bounded(coros, concurrency=12):
    sem = asyncio.Semaphore(concurrency)
    async def _run(c):
        async with sem: return await c
    return await asyncio.gather(*[_run(c) for c in coros])
```
Semaphore 是每次调用时创建的（不是全局），让不同批次之间不共享，各自独立控制并发上限。

**MCP 并发打爆连接池：** 工具调用走 HTTP，连接池有上限。用**模块级** `asyncio.Semaphore(8)` 限制（与 LLM Semaphore 不同，MCP Semaphore 是全局单例，因为连接池是全局资源）。两个 Semaphore 独立控制不同资源，数字不一样是因为瓶颈不一样——LLM 瓶颈是内存，MCP 瓶颈是连接数。

---

**阶段四：异步后处理链路（RocketMQ 消费端）**

对话满 40 轮，Java 侧通过 RocketMQ 投递 `SummaryReadyEvent`，memory-service 消费后触发记忆提取。这里的并发风险来自 MQ 本身的 At-Least-Once 语义——Rebalance、ACK 丢失、消费超时三类场景必然触发重投，同一条消息可能被消费两次，导致重复写入记忆。

处理方式是 **Redis SET NX 消费幂等**（`RedisSummaryIdempotencyStore`）：
```java
// Key: memory:summary:idempotency:{userId}:{charId}:{sessionId}:{snowflakeId}
// TTL = 7 天
return bucket.setIfAbsent("1", Duration.ofSeconds(ttlSeconds));
```
消费前先 SET NX，拿到锁才处理，否则直接 ACK 跳过。TTL 7 天是因为实测 MQ 最长重投延迟在 1-2 天以内，7 天是足够安全的缓冲。

RocketMQ 消费者（`RocketmqSummaryPushConsumer`）还支持 `asyncDispatch` 模式，内部有专属线程池（线程名 `rocketmq-memory-summary-async-consume`），超时等待 `consumerAsyncAwaitTimeoutMs`，超时返回 `RECONSUME_LATER` 触发重试，不阻塞 MQ 消费线程。

---

**阶段五：星梦入口（高并发下的状态安全）**

星梦接受邀请这个操作的并发风险是：用户快速双击，同时发出两个"接受"请求，可能创建两个会话，导致计费和存档状态都错乱。

两道防护：

**Redisson 分布式锁**（`StarDreamAcceptLockService`）：
```java
// Key: stardream:accept_lock:{userId}
lock.tryLock(3L /*等待秒*/, 15L /*租约秒*/, TimeUnit.SECONDS)
```
等待 3 秒超时快速失败，15 秒租约 + Watch Dog 自动续期，保证持锁期间业务执行慢不会锁过期。

**三级频控**（`StarDreamInviteRateLimitService`）：
- 完成后 10 分钟冷却（`stardream:complete_cooldown:{userId}:{characterId}`，Redis TTL）
- 每日 8 次上限（DB 查当日次数，Redis 不够可靠）
- 拒绝后 5 分钟冻结（`stardream:reject_cooldown:{userId}:{characterId}`，Redis TTL）

三级分别针对三类不同性质的行为：误操作、累计滥用、脚本重试，机制不同是因为它们的时间窗口和持久化要求不一样。

---

**阶段六：批处理链路（与实时链路完全隔离）**

`StarDreamContentGenerationBatchJob`（Cron `0 0 6 * * ?`，XXL-Job 调度）用独立的 `ThreadPoolExecutor`，线程名 `stardream-content-generation-batch`，并行度 `properties.getBatchParallelism()` 配置控制。

这条链路和实时对话链路完全独立，独立的线程池、独立的 MQ Topic、独立的 LLM 路由（走网宿内网低成本通道），两者不抢资源、不互相影响。

（上线初期没有做这个隔离，早高峰批处理占满了模型网关配额，实时对话的 P99 延迟飙高，这次经历让我们彻底把两条链路拆干净了。）

---

**并发控制全景一句话总结：**

每一层的保护机制针对的是那一层特定的并发风险——Lua 脚本防接入层超发、Rolling 锁防会话内乱序、Semaphore 防实例内 OOM、SET NX 防 MQ 重投重复写入、Redisson 防业务状态并发破坏、线程池隔离防批处理抢占实时资源。不是堆机制，是每个机制在正确的位置解决正确的问题。

---

## 四、成本控制——Token 费用、基础设施、工程决策三个维度

### 先说成本的构成

AI 应用的成本和传统服务不一样，有三块：**API Token 费用**（按用量计费，最直接）、**基础设施费用**（Serverless 按调用计费，容器按资源计费）、**工程成本**（Prompt 改动需要验证、LLM 失败需要补跑，这些都是隐性成本）。三块都需要管。

### Token 费用：模型分级 + 库存复用 + 预算管控

**模型分级**是最直接的杠杆。我们把所有 LLM 调用按场景分两个等级：

实时链路（用户在等）——短对话、星梦开场白、意图识别——用 `qwen3-max`，质量优先，这里节省成本会直接损害体验，不能省。

批处理链路（用户不感知）——星梦内容批量生产、陌生人 Feed 生成——用 `MiniMax-M2.7` 或走网宿内网通道的 `deepseek-v4-flash`，成本优先，这里 Token 单价能降 60-70%。

两个模型走不同的路由，在 Nacos 分别配置，改其中一个不影响另一个。

**库存复用**是量级最大的优化。星梦内容的核心洞察是：同一个场景（"清晨在咖啡厅等你"）对于处于同一关系等级的不同用户，内容骨架是相似的，不需要每次都从零生成。

`batch_guard.py` 里的 `should_skip_phase3_production()` 在批量生产开始前先检查 12 个时间窗的库存量，全部 ≥ 8 条则直接跳过本次 LLM 生产。Phase 3 内部也是先查库存，够用直接提取，不够才调 LLM。（最终存量复用率达到 60% 以上，实际 LLM 调用量降低约 40%，单用户每日 Token 成本降低约 40%。）

**Token 预算管控**防的是单次意外超支。用 `6,963` token 的主动上限，不让某个特别复杂的角色设定把单次推理成本推到正常值的 3-4 倍。超预算时从最旧的短期记忆轮次削减，长期记忆永远全量保留。

### 基础设施费用：Serverless 的成本特性

Python Agent 跑在阿里云 AgentRun Serverless 上，按调用量和 CPU 秒计费，不跑就不花钱。这个模型对于批处理 Agent 非常合适——每天 06:00 批处理，其他时间实例缩到零，不像固定容器一直收费。

Serverless 的成本陷阱在冷启动——每次实例从零启动，MCP 客户端初始化耗时约 900ms。如果批处理频繁触发冷启动，这部分时间本身不计费，但 Agent 处理用户的时间增加，等效成本上升（同样的吞吐量需要更多实例）。

MCP 客户端单例解决了这个问题——热实例复用连接，查询耗时 900ms → 200ms，单实例能处理更多用户，Serverless 所需实例数减少，基础设施费用降低。

### 工程成本：失败补跑而不是重新生成

LLM 失败是高频事件，失败后如何处理的决策直接影响工程成本。

我们的原则是：**失败时保留内容，只补失败的步骤，不重走整个流程。**

记忆提取失败：把原始对话文本以 `infer=false` 存入向量库（至少内容不丢），打标 `FAILED_PENDING_RETRY`。`RetryFailedMemoriesJob`（Cron `0 */10 * * * ?`，每 10 分钟）扫描重跑，最多 3 次，间隔 5 秒。

这个设计避免了"LLM 失败 → 重新触发完整批处理流程 → 消耗一倍 Token 重新生成内容"的浪费。内容已在库里，补的只是提取步骤，成本极低。

另外，miniMerge 的记忆收敛也间接控制了 Token 成本——向量库如果无限膨胀，TopK 检索会召回越来越多噪声条目，每次对话注入的无关事实越来越多，Prompt Token 慢慢涨。每 5 批触发一次语义融合、deleteOld 删掉旧条目，向量库保持在合理规模，检索质量和单次对话 Token 消耗都稳定。

### 可观测性是成本控制的前提

成本控制做了什么、效果怎样，如果没有可观测性，就是瞎打枪。

我们有几个关键监控点：
- `TRIM_EVENT` 日志：记录哪些用户的短期记忆被裁减了、裁减了多少轮，持续裁减超过 10 轮是 Token 预算不足的信号
- 批处理跳过率：`should_skip_phase3_production()` 命中率，反映库存复用效果
- LLM 解析失败率：六层容错后仍然失败的比例，超过阈值触发告警
- 模型路由统计：快慢模型各占多少比例，确保批处理没有意外走到 qwen3-max

这些指标不只是监控，也是决策依据——（比如某个角色的 TRIM_EVENT 特别多，说明这个角色的设定写得太长，应该优化而不是升级 Context Window。）

---

## 快速回顾（面试收口用）

| 问题 | 核心方案 | 关键数据 |
|---|---|---|
| 工具集成 | MCP 协议 + 6 服务 20 工具路由 + 单例连接复用 + 双 Semaphore 保护 | 冷启动 900ms → 200ms，MCP 并发上限 8 |
| 提示词工程 | XML 12 标签结构 + Token 预算主动管控 + 六层 JSON 容错 + Nacos 版本灰度 | 可用预算 6,963 tokens，失败率 8% → 0.5% |
| 并发控制 | 六个阶段各自对应的机制：频控→Rolling锁→Semaphore→SET NX→Redisson→线程池隔离 | TTL 7 天，Redisson 等待 3s/租约 15s |
| 成本控制 | 模型分级 + 库存复用 + 预算管控 + 补跑不重跑 + miniMerge 收敛 | 库存复用率 60%+，Token 成本降约 40% |

---

*代码来源：`/Users/harvey/Documents/projects/xinyu-agents`（Python）+ `/Users/harvey/Documents/projects/xinyu-world-simulator-core`（Java）*
