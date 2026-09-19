# 心屿 AI 项目 — 简历埋点与面试解答

> 以下埋点均对应简历原文中的具体表述，面试官大概率会顺着这些点追问。  
> 每条格式：**【埋点原文】→ 面试官可能的提问 → 标准回答**

---

## 埋点 1：MCP（Model Context Protocol）HTTP 接口解耦

**简历原文**：`两层通过 MCP（Model Context Protocol）HTTP 接口解耦，Agent 不直接操作数据库`

**面试官追问**：MCP 是什么？为什么不直接让 Agent 访问 PostgreSQL？

**回答**：
MCP 是 Anthropic 提出的 Model Context Protocol，定义了 AI 模型与外部工具/数据源之间的标准化接口协议，本质上是 Agent 通过 HTTP 调用"工具"（Tool）的标准约定。

在心屿中，我们没有让 Python Agent 直接连接 PostgreSQL，原因有三：
- **职责隔离**：Agent 只做 AI 推理，数据库的业务规则（权限校验、时区转换、业务约束）由 Java Core 统一执行，不让 AI 层绕过业务逻辑直接写库
- **独立演进**：Java 侧可以随时修改数据库表结构、迁移数据库，Agent 层完全无感知，只要 MCP Tool 的接口契约不变
- **安全边界**：Agent 无法直接执行任意 SQL，只能调用预定义的 MCP 工具方法，防止 LLM 产生不可控的数据库操作

具体实现：我们用 Python `langchain-mcp-adapters` 的 `MultiServerMCPClient` 初始化连接，`get_tools()` 获取工具列表，再 `create_react_agent(llm, tools)` 包装为可调用 Agent，整个 MCP 通信走 HTTP Streamable Transport。

---

## 埋点 2：LangGraph 状态图 + 条件边

**简历原文**：`每个节点挂载条件边，节点内出错时直接路由至终止节点，错误步骤精确可定位`

**面试官追问**：为什么选 LangGraph 而不是直接用 LangChain 的 Chain？条件边怎么工作？

**回答**：
LangChain Chain 是线性的——步骤 A → B → C → D，中间某步出错要么抛异常要么静默跳过，难以精确控制分支逻辑。LangGraph 的 StateGraph 本质是一个有向图，节点之间的边可以带条件（Conditional Edge），根据当前状态决定走哪条分支。

**条件边工作原理**：
```python
# 每个节点执行后，检查 state.error 决定下一节点
def route_after_generate(state: WorkflowState) -> str:
    if state.get("error"):
        return END          # 出错直接结束，不继续执行后续节点
    return "validate_output"  # 正常继续

graph.add_conditional_edges("generate", route_after_generate)
```

每个节点执行时若出错，把 `ErrorCode` 写入 `state.error`，条件边检测到 error 后路由到 `END`，整个工作流停止，不再执行后续节点。主调方拿到的出参中带 `error_code` 字段，可以精确知道是哪个步骤出了什么类型的错（`PARAM_INVALID` / `LLM_FAILED` / `MCP_FAILED` 等）。

**对比链式调用的优势**：LangGraph 的图结构天然支持分支、并行（asyncio 并行节点）、可视化调试，复杂 AI 工作流用 Chain 写完全是硬编码，LangGraph 是结构化工程。

---

## 埋点 3：asyncio.gather 并行翻译，耗时降低约 40%

**简历原文**：`翻译节点用 asyncio.gather 将 zh-TW + en-US 并行生成，翻译耗时降低约 40%`

**面试官追问**：Python 异步怎么实现的？是真正的并行吗？40% 是怎么测出来的？

**回答**：
`asyncio.gather` 是 Python 协程并发，不是多线程并行，在 GIL 层面是单线程的。但 LLM 调用本质是 IO 密集型（HTTP 请求等待模型响应），协程在等待 HTTP 响应时会释放事件循环让另一个协程执行——这已经足够实现"两个翻译请求同时发出，同时等待响应"的效果，实际吞吐等同于真并行。

```python
# 并行发起 zh-TW 和 en-US 翻译
tw_result, en_result = await asyncio.gather(
    translate_posts(posts, "zh-TW"),
    translate_posts(posts, "en-US")
)
```

**40% 来源**：翻译是纯 LLM 调用，串行执行两次翻译耗时 ≈ T1 + T2（假设各 1.5s，合计 3s）；并行后耗时 ≈ max(T1, T2)（约 1.5s），节省约 50% 翻译时间。翻译占整体 Agent 耗时约 80%，综合下来整体 RT 降低约 40%。这是基于压测日志中的 P50 耗时对比得出的估算值。

---

## 埋点 4：5 阶段批处理 + 库存优先 + 复用率 60%+

**简历原文**：`Phase3 采用库存优先策略，现有内容复用率 60%+，显著降低 LLM Token 调用成本`

**面试官追问**：库存优先是什么意思？复用率怎么统计的？库存不够怎么补？

**回答**：
**库存**指之前批次已生产、但用户还未消费的星梦内容（存在 DB 的 `tb_xingmeng_stock` 表）。Phase3b 分配时间窗时，优先从库存中取已有内容放入当日菜单，只有某时间窗库存不足（低于阈值，如 `< 8 条`）才调 LLM 生产新内容。

**复用率统计**：
```
复用率 = 当日直接使用库存数量 / 当日总计划数量
```
每次 Phase3 执行后，在批处理日志中记录两个计数器（复用数 / 总数），通过运营日志分析得出 60%+ 的数据。实际上这个比例会随产品运营时间增长（库存越来越丰富），早期可能只有 30%，稳定期能到 70%+。

**库存不足时补充流程**：计算当前时间窗缺口 = 目标数量 - 库存数量，调用 xingmeng-content agent 生产缺口数量的星梦，生产完成后同时写入 DB 和当日计划，双重保障。

---

## 埋点 5：miniMerge 滚动收敛机制

**简历原文**：`设计 miniMerge 滚动收敛机制，每积累 5 条 mini 记忆自动调 mergeMemories API 语义融合，合并结果继续参与下轮`

**面试官追问**：为什么不直接限制向量库最大条数？滚动收敛是什么意思？

**回答**：
**为什么不直接限制条数**：限制条数是硬截断，会丢失早期的重要记忆（比如"用户半年前说过不喜欢浪花"这条对情感陪伴至关重要）。我们要的是"信息不丢失，但表达更精炼"——滚动收敛做的是语义融合而非删除。

**滚动收敛工作原理**：
```
第 1 轮：[批次A, 批次B, 批次C, 批次D, 批次E]  →  mergeMemories  →  M1
第 2 轮：[M1, 批次F, 批次G, 批次H, 批次I]       →  mergeMemories  →  M2
第 3 轮：[M2, 批次J, 批次K, 批次L, 批次M]       →  mergeMemories  →  M3
```

每次合并的输入都包含上一轮的合并结果（M1、M2…），所以历史上所有批次的精华都被"压缩"进最新的合并记录里。每轮合并后，5 条被消费的源记录标记 `memory_summarized = true`，合并结果写入新行继续参与下轮计数。

**关键实现细节**：合并计数不用内存变量，而是查 PostgreSQL 的 `tb_memory_summary_task` 表中 `memory_summarized = false` 的行数。这样服务重启不会丢失计数状态，且多实例消费同一 MQ 时不会重复触发合并（结合 Redis 幂等键）。

---

## 埋点 6：Redis 幂等键 + XXL-Job 重试

**简历原文**：`Redis 幂等键防 MQ 重复消费 + XXL-Job 每 10 分钟扫描 FAILED 记录重试 + 降级时 infer=false 存原文兜底`

**面试官追问**：如何保证记忆总结恰好只执行一次？降级是什么情况下触发的？

**回答**：
**幂等保障**：
```
投递 MQ 前，生成幂等键：memory:summary:idempotency:{userId}:{characterId}:{sessionId}
设置 Redis TTL = 7 天

消费 MQ 时：
  1. 检查幂等键是否存在
  2. 存在 → 跳过（已处理过）
  3. 不存在 → 执行总结 → 设置幂等键
```
这解决了 MQ 重复投递的问题（Exactly-Once 语义）。

**三层兜底**：
- 第一层：`addMemory` 正常执行，`infer=true`，LLM 提取事实写入向量库
- 第二层：`addMemory` 超时（16s）或 LLM 静默失败 → `infer=false`，把原始对话文本直接存入向量库，状态标记 `FAILED_DEGRADED`，后续可通过 `/v1/memories/retry` 补跑推断
- 第三层：XXL-Job 每 10 分钟扫描 `FAILED_DEGRADED` 状态记录（最多重试 3 次，间隔 5s），作为最终兜底

**为什么 FAILED_DEGRADED 记录还要纳入合并计数**：即使是降级存原文，这些内容最终也会通过重试被补推断。纳入计数保证流程不因降级而卡住（合并阈值永远能被触发）。

---

## 埋点 7：PolarDB 向量引擎 + "Mem0 同款 API"

**简历原文**：`PolarDB 向量引擎，事实提取 + 向量相似度检索 TopK 注入`

**面试官追问**：为什么用 PolarDB 而不是 Pinecone 或 Weaviate 这类专业向量数据库？向量化是你们自己做的吗？

**回答**：
PolarDB Memory API 相当于阿里云托管的 Mem0——它不只是一个向量存储，而是内置了完整的记忆管理 pipeline：

| 能力 | 自建方案需要 | PolarDB 提供 |
|------|------------|-------------|
| 文本 → 事实提取 | 自己写 Prompt + 调用 LLM | 内置 `FACT_RETRIEVAL_PROMPT` |
| 向量化 | 自建 Embedding 服务 | 内置 |
| 新旧记忆冲突决策 | 自己实现 ADD/UPDATE/NONE 逻辑 | 内置 `UPDATE_MEMORY_PROMPT` |
| 语义去重合并 | 自己实现 | 内置 `mergeMemories` |

选 PolarDB 而非 Pinecone/Weaviate 的原因：
1. **开箱即用**：上述 pipeline 全部托管，我们只需组装请求参数，不需要自己维护 Embedding 模型和合并策略
2. **国内合规**：阿里云国内节点，数据不出境
3. **生态集成**：与 Nacos、RocketMQ 在同一套阿里云体系内，运维成本低

向量化由 PolarDB 内部完成，我们只传 `messages` 文本，`addMemory` 接口自动完成：LLM 事实提取 → Embedding → 向量存储。

---

## 埋点 8：实时改写 P99 < 2 秒

**简历原文**：`并行 MCP 查询（角色信息 + 用户画像 + 历史记忆）→ 拼接 XML 结构化提示词 → 调用快模型（qwen3-max）→ Hermes 流式推送；MCP 日志写入异步化；全链路 P99 < 2 秒`

**面试官追问**：2 秒内怎么做到的？MCP 日志异步化是什么意思？

**回答**：
**延迟拆解**（目标 < 2000ms）：
```
并行 MCP 查询（三个工具同时发出）：~200ms
提示词拼接（本地计算）：~5ms
LLM 推理（qwen3-max，快模型）：~1200ms
Hermes 连接建立 + 首 Token 推送：~100ms
---
合计约 1500ms，P99 留有 500ms 余量
```

**三个优化点**：
1. **MCP 并行查询**：角色信息、用户画像、历史记忆三个 MCP 工具用 `asyncio.gather` 同时发起，而非串行（串行会是 3 × 200ms = 600ms）
2. **快模型选型**：实时场景用 `qwen3-max`（低延迟），批处理场景用 `deepseek-v4-flash`（低成本），针对性选型
3. **MCP 日志异步化**：改写完成后需要把调用记录写入 MCP 日志服务（用于运营分析），这个写入操作用 `asyncio.create_task()` 异步提交，主链路不等待结果，节省约 100~200ms

流式推送通过 Hermes 长连接实现"打字机效果"（分 Token 逐步到达），用户不需要等 AI 生成完整内容才开始看到文字，主观体验上延迟更低。

---

## 埋点 9：XML 结构化提示词 + 版本化管理

**简历原文**：`统一采用 XML 结构化提示词，按语义拆分为 12 个标签块，版本化管理（v1.0 ~ v4.0），新版本新增函数而不覆盖旧函数`

**面试官追问**：为什么用 XML 而不是直接写 Markdown？提示词版本怎么管理的？

**回答**：
**为什么 XML**：
- LLM 对有语义标签的结构理解更好——`<角色设定>` 明确告诉模型"这段是角色信息"，`<红线禁止>` 告诉模型"这些绝对不能做"，比 Markdown 标题的语义更强
- 标签边界清晰，程序化替换占位符更安全（不会误改其他段落）
- 各段落独立，可以只更新某个 tag 的内容而不影响整体

**版本管理**：
```python
DEFAULT_PROMPT_VERSION = "v4.0-stranger-lv0"

def build_prompt_v1(params):  # 历史版本保留
    ...

def build_prompt_v4(params):  # 当前版本
    return f"""
    <系统信息>{params.system}</系统信息>
    <任务目标>{params.goal}</任务目标>
    <角色设定>{params.character}</角色设定>
    ...
    """
```

新版本新增函数（不删旧函数），原因：AB 测试时可能需要同时运行两个版本（灰度），旧版本保留便于对比效果；线上出问题时可以快速回退到旧版本函数而不用 git revert。版本号写入出参的 `workflow_context` 字段，可以追踪每次 LLM 调用用的是哪个版本的提示词。

---

## 埋点 10：四层 JSON 解析兜底，失败率从 8% 降至 < 0.5%

**简历原文**：`设计四层 JSON 解析兜底（剥离 <think> 推理块 → 提取 json 代码围栏 → 标准 json.loads → json_repair 终极修复），将 LLM 输出解析失败率从约 8% 降至 < 0.5%`

**面试官追问**：LLM 输出 JSON 失败有哪些具体情况？json_repair 是怎么工作的？

**回答**：
**常见的 LLM JSON 输出问题**：
1. **思维链前缀**：DeepSeek R1 等 CoT 模型先输出 `<think>我需要生成一个关于...的内容</think>`，然后才是 JSON
2. **Markdown 围栏**：模型"礼貌性"加上 ` ```json ... ``` ` 包裹
3. **末尾多余逗号**：`{"a": 1, "b": 2,}` 最后一个元素后多逗号，标准 JSON 规范不允许
4. **中途截断**：token limit 时模型在 JSON 中间被截断，输出不完整的 JSON

**四层处理顺序**：
```python
# 第1层：剥离 <think> 块
raw = re.sub(r'<think>.*?</think>', '', raw, flags=re.DOTALL)

# 第2层：提取 ```json 围栏内容
match = re.search(r'```json\s*(.*?)\s*```', raw, re.DOTALL)
content = match.group(1) if match else raw

# 第3层：定位 [ ] 边界后标准解析
content = content[content.index('['):content.rindex(']')+1]
try:
    result = json.loads(content)
except json.JSONDecodeError:
    # 第4层：去尾逗号 + json_repair
    content = re.sub(r',\s*([}\]])', r'\1', content)
    result = json_repair.loads(content)
```

`json_repair` 是一个开源库，能修复常见的 JSON 格式问题（补齐缺失括号、移除非法字符等），作为最后一道防线。8% → 0.5% 这个数据来自上线前后的日志对比统计。

---

## 埋点 11：短期记忆 40 轮

**简历原文**：`短期记忆（最近 40 轮对话原文，PostgreSQL 存储，直接全量注入 Prompt）`

**面试官追问**：为什么是 40 轮？这个数字怎么定的？超过 40 轮的消息删掉了吗？

**回答**：
**40 轮的依据**（Token 预算和记忆效果的折衷）：
```
平均每条消息约 50 字 = 约 70 tokens
40 轮 × 2 条（user + assistant） × 70 tokens ≈ 5600 tokens
```
注入 Prompt 后，还需要留给角色设定（约 2000 token）、长期记忆（约 1000 token）、用户画像（约 500 token）、输出空间（约 2000 token），模型 context window 8K 能塞下，留有足够余量。这个值通过 Nacos 热更新，实际上可以根据模型 context window 大小随时调整，不需要改代码。

**超过 40 轮的消息没有删除**，而是"退出窗口"：
- 消息永久存在 PostgreSQL 的 `tb_short_chat_message` 表（审计 / 用户历史查看 / 未来可能需要重处理）
- 已被总结的批次标记 `memory_summarized = true`，`getAppShortChatMessage` 查询时 SQL 中 `WHERE memory_summarized = false` 过滤掉这些记录
- 这些消息的内容通过 PolarDB 的事实提取，以更精炼的形式（事实记忆）继续影响 AI，只是从原文形式变成了向量形式

---

## 埋点 12：runId 命名空间隔离

**简历原文**：`写入各自独立的 runId = charX__saveId 命名空间，彻底隔离多角色记忆`

**面试官追问**：为什么多角色记忆要隔离？如果不隔离会有什么问题？

**回答**：
**不隔离的后果**：
- 用户和角色 A 的对话记忆会出现在和角色 B 的对话检索结果中（比如用户告诉 A"我不喜欢辣的"，B 也知道了，但用户从来没跟 B 说过）
- 多人星梦里角色 A 视角的记忆（A 看到用户哭了）和角色 B 视角（B 没看到）混在一起，AI 行为不可预期

**runId 是 PolarDB Memory API 的核心隔离维度**，所有 `addMemory`、`searchMemory`、`mergeMemories` 操作都必须带 `runId` 参数，PolarDB 在 runId 空间内做向量检索，天然隔离。

**命名空间设计**：
```
短对话：     runId = userId__characterId
             → 同用户与不同角色的记忆完全隔离
单人星梦：   runId = roleId__saveId
             → 按存档（save）隔离，每次星梦体验独立
多人星梦角色：runId = charX__saveId
             → 同一存档内不同角色各自独立视角
```

这套命名空间是设计时最先确定的约束，一旦生产环境有记忆数据后，runId 格式就不能随意修改（否则历史记忆检索不到），属于系统的核心不变量。

---

## 埋点 13：整体高并发防护体系

**面试官追问**：你们系统怎么防止高并发打垮？从哪几个层面做的？

**回答**：

这个问题我可以从我们系统实际面临的并发压力说起。

心屿的并发来源主要有三块：用户高频发消息触发 LLM 调用、每天凌晨批量生产星梦内容、以及 AI 记忆的异步写入。这三块的特征完全不同，不能用一套方案统一处理，所以我们是分层设计的。

**整体思路是四个字：削、隔、剥、幂。**

**削**是削峰。用户连续快速发消息时，我们不是每条都打 LLM，而是用 Rolling Flush 在一个时间窗口内把连续消息攒成一批，一批只触发一次 LLM 调用，实际 LLM 并发量能降低 60% 以上。星梦这种高成本操作则在用户入口直接做多级限流，10 分钟冷却、每日 8 次上限、被拒绝后 5 分钟冻结，三道卡关，从源头控制频率。

**隔**是隔离关键资源的并发上限。Python Agent 层用 `asyncio.Semaphore` 控制同时进行的 LLM 调用不超过 3 个、MCP 调用不超过 10 个，防止批量处理时把 Serverless 实例内存打爆。批处理任务 XXL-Job 限制 8 线程，这个数字是根据 LLM 服务侧 QPS 配额和数据库连接池上限压测出来的。对于并发进入星梦这类关键流程，加 Redisson 分布式锁保证同一用户只有一个流程在跑。

**剥**是把重操作从主链路剥离出去。用户发消息之后，消息落库立刻 ACK 给 App，LLM 调用是异步的，不阻塞接收链路。星梦结束后的记忆总结、亲密度更新、成就检查这 4 类后处理，全部发 RocketMQ 异步并行，主链路 30ms 内返回。这样主链路始终只做最轻的事，重的操作错开在后台消化。

**幂**是幂等，让重试安全。高并发下 MQ 重复消费是常态，我们用 Redis 幂等键（`SET key NX EX`）保证同一批次记忆总结只消费一次。贝壳扣费用预扣 + 结算两步，每步有独立流水 ID，崩溃重启后 XXL-Job 扫描超时冻结记录自动退款，资产操作不会因为重试出现脏状态。

存储层上，核心消息表按 `user_id` 做 Hash 分区，高活跃用户的写入压力打散到不同分区；Rolling 状态、幂等键、会话缓存都放 Redis，高频读不打数据库。

总的来说，这套方案没有一个"银弹"，是针对每种并发场景各自设计的：**能不让并发进来就在入口拦，进来了就给资源设上限，重操作就异步剥离，重试就幂等兜底**。每层解决自己该解决的问题，没有哪个单点会成为系统崩溃的原因。

---

### 各层详细说明

#### 第一层：流量控制（削峰）

**1.1 Rolling Flush 攒批——把消息级并发降为批次级并发**

用户快速连发多条消息时，每条消息如果都触发一次 LLM 调用，10 个并发用户各发 5 条就是 50 次 LLM 并发，成本和延迟都不可控。Rolling Flush 用 Redis 存每个会话的消息积累状态，配合 `ScheduledExecutorService` 定时器，在一个时间窗口内（约 800ms）把连续消息合并为一批，一批只触发一次 LLM，将消息级并发压缩为批次级并发，**实际 LLM 并发量降低约 60~70%**。

**1.2 星梦多级限流——用户级频率硬控制**

星梦是高成本操作（调用 LLM 生成剧本内容），设计三道限流：
```
① 10 分钟冷却：同一用户 10 分钟内只能进入一次
② 每日 8 次上限：当日累计超过 8 次直接拒绝
③ 5 分钟冻结：被拒绝后冻结 5 分钟，防止脚本快速重试
```
三层限流存 Redis，校验全在内存完成，不打数据库。

**1.3 Hermes 长连接数限制**

Hermes 配置 `max-connection-size: 20`，限制单服务实例的长连接上限，超出连接排队等待，避免新连接把服务撑爆。`io-thread-size: 10` 控制 IO 线程数，防止线程膨胀。

---

### 第二层：并发隔离（给关键资源设上限）

**2.1 asyncio.Semaphore——Python Agent 层并发上限**

Python 层用 `asyncio` 协程处理并发，但 Serverless 实例内存有限。批量处理场景（如星梦批处理 8 个用户同时触发 12 个时间窗）如果不加限制，同时有 96 个 LLM 请求在途，每个携带完整 Prompt 上下文（~10KB），内存直接 OOM。

解法：全局 `asyncio.Semaphore(3)` 限制 LLM 并发，`asyncio.Semaphore(10)` 限制 MCP 工具调用并发，任何时刻内存中最多 3 个 LLM 上下文，实例不会 OOM。

**2.2 XXL-Job 线程池——批处理并发上限**

星梦批处理任务配置 8 线程并行，每个线程处理一个用户的全量内容生产。线程数不是越多越好——线程数 × 单线程 LLM 并发峰值要在数据库连接池上限和 LLM 服务 QPS 配额之内。8 线程是在压测后根据 LLM 服务侧 QPS 限制（约 30 次/秒）和数据库连接池（HikariCP 20 连接）综合确定的。

**2.3 Redisson 分布式锁——关键流程互斥**

两类场景：
- 星梦进入流程：用户同时点多次，多个请求并发到达，加锁保证同一用户同一时刻只有一个流程在创建会话，防止重复创建
- 记忆合并触发：多实例部署时 MQ 消息可能被多台机器并发消费，加 Redisson 锁保证同一批次的 merge 只执行一次

---

### 第三层：异步解耦（主链路减负）

**3.1 RocketMQ 削峰——重操作从主链路剥离**

主链路（用户发消息 → 收到 AI 回复）只做核心步骤：消息落库、LLM 调用、推送结果。所有"重但不紧急"的操作全部通过 RocketMQ 异步化：

```
记忆总结（LLM 事实提取 + PolarDB 写入）  → MQ 异步，主链路无感知
亲密度计算更新                            → MQ 异步
成就/徽章检查                             → MQ 异步
下一场星梦内容预生产触发                   → MQ 异步
```

星梦结束时 4 类后处理任务同时发 4 条 MQ 消息，主链路 30ms 内返回，4 个 Consumer 并行消费，互不阻塞。

**3.2 占位消息——LLM 生成与消息接收解耦**

消息落库后立刻 ACK 给 App（< 50ms），插入占位消息后异步调 LLM（2~5 秒），LLM 生成完再更新占位消息推真实内容。这样 LLM 的慢速不阻塞消息接收链路，Hermes 连接也不会被占用等待 LLM。

---

### 第四层：幂等防重（重试安全）

高并发场景下重试是必然的（网络抖动、消费者重启），幂等保证重试不产生副作用：

**4.1 Redis 幂等键——MQ 消费幂等**
```
键：short_chat_summary_ready:{userId}:{saveId}:{sessionId}:{startCursor}:{endCursor}
TTL：7 天
原子操作：SET key 1 NX EX 604800
```
第一次消费成功后 key 存在，后续重复消费直接跳过，Exactly-Once 语义。

**4.2 贝壳两步结算——资产操作幂等**

预扣费（冻结）+ 正式结算两步，每步有独立的流水 ID（`freeze_id`）。崩溃重启后 XXL-Job 扫描超时未结算的冻结记录自动退款，不会出现"扣了钱但没内容"或"有内容但没扣钱"的脏状态。

---

### 第五层：存储层保护

**5.1 PostgreSQL 分区表——分散热点**

核心消息表 `tb_soul_haven_short_chat_message` 按 `PARTITION BY HASH(user_id)` 分区，高活跃用户的写入压力分散到不同分区，单分区不会成为写入瓶颈，也便于按分区做 VACUUM 维护。

**5.2 Redis 多级缓存——减少 DB 读压力**

```
Rolling 状态（每条消息都要读写）→ 存 Redis，不打 DB
幂等键（每条 MQ 消费都要查）   → 存 Redis
会话基础信息（高频读）         → Redis 缓存，TTL 10 分钟
```

核心原则：**高频读用 Redis 挡，慢速写用 MQ 缓冲，关键互斥用分布式锁，重试安全靠幂等键。**

---

**总结一句话**：

> 接入层限连接数，业务层限频率，AI 层限并发，主链路剥重任务，重试靠幂等，存储层分区扛写入——五层联动，每层只解决自己该解决的问题，没有一个单点能被打垮。
