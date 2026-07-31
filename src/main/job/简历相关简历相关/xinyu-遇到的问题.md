# 心屿 AI 项目 — 遇到的问题（深度版）

> 三个问题，覆盖：前期架构设计 / 系统级资源争抢 / 基础设施与业务的结构性矛盾

---

## 问题一：Prompt 上下文预算管理——固定估算槽位浪费空间，重度用户面临超限风险

### 业务背景

心屿的每次对话请求，Prompt 由四部分构成：

| 内容 | 说明 | 估算大小 |
|------|------|---------|
| 角色完整设定 | 人格基础、世界观背景、语言风格 | 1200~2800 token（因角色复杂度差异大）|
| 用户画像 | 情绪偏好、近期话题标签 | 200~600 token |
| 长期记忆 | PolarDB 向量检索召回的事实摘要，固定 5 条 | ~600 token |
| 短期记忆 | 最近 N 轮对话原文，目标 40 轮 | 最多 ~5600 token |

满配估算总量约 **8700 token**，而模型可用预算为 8192 × 85% ≈ **6963 token**。对于 Lv4/Lv5 的重度用户（历史对话充足，能满 40 轮），超限是可预见的风险：超限后要么请求 400 报错，要么网关从头部截断——角色设定排在 Prompt 最前面，截掉就是 AI 人格崩溃。

---

### 初始思路：按经验值固定预留

最简单的做法是按经验估算给每个组件预留固定 token 槽：

| 组件 | 固定预留 |
|------|---------|
| 角色设定 | 2000 token |
| 用户画像 | 500 token |
| 长期记忆 | 600 token |
| **短期记忆** | **剩余 = 6963 - 2000 - 500 - 600 = 3863 token ≈ 27 轮** |

---

### 发现问题：固定预留浪费了角色设定的 surplus

不同角色的设定长度差异很大：
- 简单日常型角色：实际约 1200 token
- 带世界观背景的复杂角色：实际约 2500 token

若始终按 2000 token 预留，简单角色就白白浪费约 800 token——这 800 token 完全可以让短期记忆多装 5~6 轮历史。在固定预留方案下，这部分空间消失了，短期记忆永远只能用剩余的 3863 token，即约 27 轮。

📎 `diagrams/16-prompt-token-预算分配.puml` — 固定预留（❌）vs 实测分配（✅）对比

---

### 设计目标

| 目标 | 说明 |
|------|------|
| 充分利用 | 角色设定实际用多少就扣多少，surplus 全部流向短期记忆 |
| 安全约束 | 总量控制在 model_max_tokens × 85% 以内，留足输出空间 |
| 有序降级 | 超限时只从最旧的短期轮次开始删，长期记忆 5 条始终保留 |
| 易扩展 | 所有阈值 Nacos 热更新，换模型只改一个参数，零代码改动 |
| 可观测 | 记录每次实际注入轮数 vs 目标 40 轮，高差值作为升级决策信号 |

---

### 最终方案

#### 1.1 实测代替预留 `[充分利用 · 安全约束]`

每次 Prompt 组装时，实时计算角色设定和用户画像的真实 token 数，剩余全部给短期记忆：

```python
available = int(model_max_tokens * safety_ratio)   # 6963

# 实测实际大小，不按估算值扣
char_tokens    = count_tokens(character_setting)   # 简单角色 ~1200，复杂角色 ~2500
profile_tokens = count_tokens(user_profile)        # 稀疏 ~200，丰富 ~600
long_term_tokens = count_tokens(long_term_5_items) # 固定 5 条 ~600

# 短期记忆预算 = 实际剩余
short_term_budget = available - char_tokens - profile_tokens - long_term_tokens
```

效果对比：

| 场景 | 固定预留方案 | 实测方案 |
|------|------------|---------|
| 简单角色（char=1200, profile=400）| 固定 27 轮 | 6963-1200-400-600 = **4763 token ≈ 34 轮**（+7 轮）|
| 复杂角色（char=2500, profile=600）| 固定 27 轮 | 6963-2500-600-600 = **3263 token ≈ 23 轮**（自动收缩，正确）|

角色设定和用户画像内容几乎不变，MCP 拉取后缓存计算结果。短期记忆实时用启发式近似（中文字符 × 0.6），误差约 10%，在 15% safety margin 内可接受。

#### 1.2 优先级与降级路径 `[安全约束 · 有序降级]`

四类内容的优先级基于**信息密度**确定：

```
P0  角色设定 + 用户画像（实测大小，不可压缩）
        截掉 = AI 人格崩溃

P1  长期记忆 5 条（永远全量保留）
        600 token 承载几个月提炼的关键事实
        丢一条可能是 AI 忘了"用户母亲去年去世"

P2  短期记忆（从最新轮次往回填，budget 用完为止，最多 40 轮）
        旧轮次信息密度低，且已被长期记忆部分摘要覆盖
        超限时从最旧轮次开始删
```

超限只有一条降级路径：逐轮删除最旧的短期历史，直到满足预算。长期记忆 5 条不参与任何裁剪。

📎 `diagrams/17-prompt-token-裁剪流程.puml`

```python
async def assemble_prompt(ctx: PromptContext) -> str:
    available = int(ctx.model_max_tokens * ctx.safety_ratio)

    char_tokens    = count_tokens(ctx.character_setting)   # 缓存实测值
    profile_tokens = count_tokens(ctx.user_profile)        # 缓存实测值
    long_term      = ctx.long_term_memories                # 固定 5 条，全量保留
    lt_tokens      = count_tokens(long_term)

    short_term_budget = available - char_tokens - profile_tokens - lt_tokens

    # 从最新轮次往回填，超出 budget 时自然截止
    short_term, actual_turns = fill_turns_by_budget(
        messages=ctx.short_term_messages,
        budget=short_term_budget,
        max_turns=40
    )

    if actual_turns < ctx.target_turns:  # target = 40
        log_trim_event(ctx.user_id, ctx.char_id,
                       actual=actual_turns, target=ctx.target_turns)

    return render_prompt(ctx.character_setting, ctx.user_profile, long_term, short_term)
```

#### 1.3 静态内容前置 + Prompt 前缀缓存 `[充分利用]`

角色设定和用户画像（P0）始终放在 Prompt 最前面。大多数模型提供商对请求前缀做服务端缓存——相同前缀命中后该部分 token 计费大幅降低（部分平台降至原价 10%）。同一用户对同一角色的连续对话，P0 前缀几乎每次命中缓存，降低输入 token 成本是顺带的额外收益。

#### 1.4 配置化 `[易扩展]`

```yaml
# Nacos 配置：soul-haven.prompt.budget
model_max_tokens: 8192   # 换模型只改这一个
safety_ratio: 0.85
long_term_topk: 5        # 长期记忆固定条数
short_term_max_turns: 40 # 短期记忆目标轮数
```

当模型升级到 32K context window 时，**只需修改 `model_max_tokens: 32768`**，短期记忆可用预算自动扩大，零代码改动。

#### 1.5 可观测性 `[可观测]`

```json
{
  "event": "PROMPT_ASSEMBLED",
  "user_id": "...",
  "char_id": "...",
  "relationship_level": 5,
  "char_tokens": 1480,
  "short_term_actual_turns": 27,
  "short_term_target_turns": 40,
  "trimmed_turns": 13,
  "scene": "short_chat"
}
```

监控：Lv5 用户 `trimmed_turns` 持续 > 10 → context window 升级决策信号。

---

### 方案评判标准

| 目标 | 衡量指标 | 固定预留方案 | 实测方案 |
|------|---------|-----------|---------|
| 充分利用 | 简单角色短期可用轮数 | 固定 27 轮 | 最多 34 轮（+7 轮）|
| 安全约束 | 超限导致截断 / 报错 | 仍可能发生（重度用户）| 精确控制，从未出现 |
| 有序降级 | 长期记忆是否受影响 | 无专门保护 | 5 条永不裁剪 |
| 易扩展 | 换模型工作量 | 手动重算所有预留 | 改一个配置 |
| 可观测 | 裁剪行为是否可见 | 无 | 完整结构化日志 |

---

### 实施效果

- 简单角色的短期记忆从固定 27 轮提升至 31~34 轮，对话历史更充分
- Lv5 重度用户（复杂角色 + 满 40 轮历史）约 12% 的请求触发轮次裁剪，裁剪后回复质量评分与未裁剪用户持平
- 模型从 qwen2.5-7B 升级到 qwen3-max 时，仅修改 Nacos 一个配置项，零代码改动

---

### 面试话术

**上下文的组成**

在设计记忆注入系统时，我们先把 Prompt 的构成梳理了一遍：四块内容——角色完整设定（人格背景，因角色复杂度在 1200~2800 token 之间差异很大）、用户画像（偏好标签，约 200~600 token）、长期记忆（PolarDB 向量召回的 5 条事实摘要，约 600 token）、短期记忆（最近 40 轮对话原文，约 5600 token）。满配总量约 8700 token，8K 模型可用预算约 6963 token，对 Lv4/Lv5 重度用户超限是可预见的风险。

**初始方案的问题**

最直觉的做法是按经验估算给每个组件预留固定 token 槽，比如角色设定统一预留 2000、用户画像预留 500，剩下约 3863 token 给短期记忆。能保证总量不超限，但有个浪费问题：不同角色的设定长度差异很大，简单角色实际只用 1200 token，按 2000 预留就浪费了 800 token——这 800 token 本可以让短期记忆多装 5~6 轮历史。在固定预留方案里这部分空间直接消失了。

**核心改法**

改法很简单：把固定预留改成运行时实测。每次 Prompt 组装时实时计算角色设定和用户画像的真实 token 数，内容几乎不变所以 MCP 拉取后缓存计算结果，剩余预算全部给短期记忆。简单角色实际用 1200 token，短期就自动多出 7 轮；复杂角色用了 2500 token，短期自然收缩——不同角色自适应，surplus 零浪费。

**优先级的设计逻辑**

四类内容的优先级是按信息密度定的：角色设定和用户画像不可压缩（截掉是人格崩溃）；长期记忆 5 条永远全量保留——600 token 承载了几个月提炼的关键事实，丢一条可能就是 AI 忘了用户某个重要的生活事件，每条信息密度远高于一轮原始对话；短期记忆从最新轮次往回填，budget 用完为止，超限时只从最旧轮次开始删——旧轮次已被长期记忆系统部分摘要，不会真正失忆。

**顺带的工程优化**

角色设定和用户画像放在 Prompt 最前面，除了优先级逻辑正确，还能利用大模型提供商的 Prompt 前缀缓存机制——同一用户同一角色的连续对话，前缀几乎不变，命中缓存后输入 token 计费大幅降低，是顺带的成本优化。

**效果**

所有阈值 Nacos 热配置，换 qwen3-max 只改了一个 `model_max_tokens`，零代码改动。Lv5 重度用户约 12% 触发轮次裁剪，裁剪后回复质量评分与未裁剪持平，说明这套优先级设计是合理的。

---

## 问题二：LLM 网关限流——批处理与实时链路流量互相踩踏，早高峰用户体验崩溃

### 业务背景

星梦是心屿的核心付费功能，用户消耗"贝壳"（平台虚拟货币）进入后，体验的第一秒是 AI 角色发出 300~800 字的沉浸式开场白——这是产品最关键的情感时刻，决定用户能否立刻进入状态。
中国用户的使用高峰有两个窗口：早高峰（07:00~09:00 通勤时段）和夜间（22:00~24:00）。为保证用户全天随时进入都有内容，我们把每日批处理时间设在了 06:00——与早高峰几乎重叠。

### 问题发现

上线初期监控显示，每天 06:00 ~ 07:30 之间，实时改写链路（xingmeng-opening-rewrite）的 P99 从正常的 1.5s 定期飙升至 8s+，持续约 90 分钟后恢复正常。日志关联分析发现：xingmeng-content 批处理 Agent 在 06:00 多实例同时启动，向 LLM 网关集中发包，峰值约 200+ RPS，触发网关 QPS 限流（HTTP 429）；而实时改写 Agent 与批处理 Agent 共用同一个 LLM 网关 endpoint 和 QPS 配额，限流波及实时链路。

对业务的实际影响：**用户花钱（贝壳）进入星梦，开场白生成等了 8 秒，高于用户耐心阈值（体感约 3 秒），导致部分用户直接退出，贝壳已扣但体验未完成，引发投诉。**

### 根因拆解

```
根因 1：批处理没有流量控制，各实例全速发包 → 网关侧形成 200+ RPS 尖峰
根因 2：实时与批处理共用同一配额池 → 批处理挤占实时链路带宽
根因 3：批处理启动时间与用户早高峰重叠 → 最坏时间点发生最坏问题
```

### 解决方案

**① 网关层：按优先级拆分独立配额组**

在 AgentRun 模型网关配置两个相互隔离的模型组：

| 配额组 | 模型 | QPS 上限 | 超限行为 | 使用方 |
|--------|------|---------|---------|--------|
| 实时组 | qwen3-max | 严格限制，不排队 | 直接 429，主调方快速失败 | xingmeng-opening-rewrite |
| 批处理组 | deepseek-v4-flash | 宽松，允许排队等待 | 队列缓冲，不影响实时组 | xingmeng-content / feed |

两组配额完全独立，批处理组打满对实时组零影响。

**② Agent 层：Semaphore 主动削峰**

批处理 Agent 内部加 `asyncio.Semaphore(max_concurrent=10)`，控制单实例最大并发 LLM 调用数，从发包端约束速率，将峰值 200+ RPS 铲平为平滑的受控速率。

```python
# xingmeng-content agent 内部
_llm_semaphore = asyncio.Semaphore(10)  # 单实例最大并发 LLM 调用

async def call_llm_with_limit(prompt):
    async with _llm_semaphore:
        return await llm.ainvoke(prompt)
```

**③ 时间错峰：批处理从 06:00 提前至 04:30**

将批处理主体在用户早高峰（07:00+）前完成，两条链路时间重叠窗口从 90 分钟缩短至近 0。04:30 的 QPS 压力不影响任何在线用户。

### 效果

| 指标 | 优化前 | 优化后 |
|------|--------|--------|
| 实时链路早高峰 P99 | 8s+ | < 2s |
| 批处理总耗时 | 基准 | +15%（Semaphore 限流代价，可接受）|
| 用户早高峰投诉 | 有 | 消失 |

---

## 问题三：Serverless 冷启动重建 MCP 连接——首个进入星梦的用户体验最差

### 业务背景

xingmeng-opening-rewrite-agent 负责在用户点击进入星梦的瞬间实时生成开场白。星梦是模拟"线下约会"的沉浸式场景——用户进入后期待的是"角色已经在那里等你了"，而不是"角色还没到"。

心屿用户的行为规律是：深夜 00:00 之后使用量快速下降，凌晨到早上 06:00 几乎没有流量。AgentRun Serverless 在实例空闲一段时间后会自动回收，早上第一个进入星梦的用户总是"踩"在冷启动上。

这带来了一个结构性问题：**早起打开 App、满怀期待进入星梦的第一个用户，体验反而是全天最差的。** 这与"越深度使用越应该越好"的产品逻辑相悖，且容易在用户社群引发"为什么早上用感觉变慢了"的负面讨论。

### 问题发现

通过 ARMS 监控发现实时改写 Agent 的 P99 呈现周期性双峰：

```
正常时段 P99：~1.5s
每天 06:00~07:30 首批请求 P99：2.4~3.1s（冷启动叠加）
深夜低谷后首批请求 P99：2.2~2.8s（同样是冷启动）
```

日志分析显示耗时集中在 MCP 初始化阶段（建立 HTTP 长连接 + 拉取 Tool Schema），耗时约 900ms。

### 根因拆解

**根因 1：MCP Client 初始化在 handler 函数内部**

```python
# 问题写法：每次请求都执行一次初始化
async def handler(event, context):
    client = MultiServerMCPClient({...})   # 900ms
    tools = await client.get_tools()       # 已包含在上面
    agent = create_react_agent(llm, tools)
    result = await agent.ainvoke(...)
    return result
```

热实例（已运行过的实例）接到新请求时，同样会执行 `MultiServerMCPClient.__init__()`，重新建连，完全没有利用实例复用的优势。

**根因 2：实时 Agent 没有预热实例配置**

实时 Agent 与批处理 Agent 用了相同的 Serverless 配置（无预热实例），低谷期全部回收，流量恢复时必须冷启动。

### 解决方案

**① 模块级单例：利用 Serverless 实例复用机制**

```python
# 模块顶层（函数外部），模块加载时执行一次，同实例复用
_mcp_client: Optional[MultiServerMCPClient] = None
_agent = None

async def _ensure_initialized():
    """热实例直接复用，冷启动才真正初始化"""
    global _mcp_client, _agent
    if _mcp_client is None:
        _mcp_client = MultiServerMCPClient({
            "character-service": {"transport": "streamable_http", "url": MCP_CHARACTER_URL},
            "memory-service":    {"transport": "streamable_http", "url": MCP_MEMORY_URL},
            "user-service":      {"transport": "streamable_http", "url": MCP_USER_URL},
        })
        await _mcp_client.__aenter__()
        tools = await _mcp_client.get_tools()
        _agent = create_react_agent(llm, tools)

async def handler(event, context):
    await _ensure_initialized()   # 热实例：纳秒级；冷启动：900ms
    result = await _agent.ainvoke(...)
    return result
```

AgentRun Serverless 同一实例处理多个请求时，Python 模块状态保留——`_mcp_client` 只在冷启动时初始化一次，后续请求直接复用，MCP 查询耗时从 900ms 降至 200ms。

**② 差异化预热策略：实时与批处理分开配置**

```yaml
# s-prod.yaml
functions:
  xingmeng-opening-rewrite:
    minReadyInstances: 2          # 始终保活 2 个热实例，低谷期不完全回收
    instanceConcurrency: 100
    cpu: 1
    memory: 2048

  xingmeng-content:
    minReadyInstances: 0          # 批处理不预热，闲时不占资源
    instanceConcurrency: 10
    cpu: 2
    memory: 4096
```

预热实例（minReadyInstances=2）保证低谷期始终有 2 个热实例在线，早起第一个用户直接落在热实例上，不再触发冷启动。

**③ 健康探针：冷启动完成后再接流量**

```python
# /health 端点：触发 MCP 连接预建立，再开始接真实请求
async def health_handler(event, context):
    await _ensure_initialized()
    return {"status": "ready", "mcp_connected": _mcp_client is not None}
```

AgentRun 在实例启动时先调用 `/health` 探针，确认 MCP 连接建立完成后才将实例加入负载均衡池，保证进入实例的请求不会命中"正在初始化"的状态。

### 效果

| 指标 | 优化前 | 优化后 |
|------|--------|--------|
| 热实例 MCP 查询耗时 | 900ms（重建连接）| 200ms（复用连接）|
| 低谷期后首批请求 P99 | 2.4~3.1s | < 2s（落在预热实例）|
| 冷启动频率 | 高（每次低谷后必现）| 降低 80%+（预热实例保活）|
| "早上用变慢"用户反馈 | 偶发 | 消失 |
